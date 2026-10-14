/*
 * Joy-Con merge engine.
 *
 * The kernel's hid-nintendo driver exposes each Joy-Con as its own evdev
 * gamepad. This engine grabs both nodes with EVIOCGRAB, which stops the kernel
 * from delivering their events to any other reader (including Android's
 * InputReader), and re-emits the combined state through a single uinput
 * gamepad. Joy-Cons that disconnect and reconnect are re-attached automatically.
 */
#include "merger.h"

#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TAG "JoyMerge"

#define INPUT_DIR "/dev/input"
#define RESCAN_INTERVAL_MS 2000

#define DEFAULT_LEFT_NAME "Nintendo Switch Left Joy-Con"
#define DEFAULT_RIGHT_NAME "Nintendo Switch Right Joy-Con"

// The virtual controller uses an ID with no device-specific key layout, so
// Android falls back to Generic.kl. The output codes below are picked for
// that layout.
#define VIRTUAL_NAME "Merged Joy-Con Controller"
#define VIRTUAL_VENDOR 0x1209
#define VIRTUAL_PRODUCT 0x0001
#define VIRTUAL_VERSION 0x0001

#define OUT_BUTTON_A BTN_SOUTH  // 304 -> KEYCODE_BUTTON_A
#define OUT_BUTTON_B BTN_EAST   // 305 -> KEYCODE_BUTTON_B
#define OUT_BUTTON_C BTN_C      // 306 -> KEYCODE_BUTTON_C
#define OUT_BUTTON_X BTN_X      // 307 -> KEYCODE_BUTTON_X (Generic.kl treats 307 as the west button)
#define OUT_BUTTON_Y BTN_Y      // 308 -> KEYCODE_BUTTON_Y

#define STICK_MAX 32767
#define STICK_FLAT 500
#define TRIGGER_MAX 255

enum { SIDE_LEFT = 0, SIDE_RIGHT = 1, SIDE_COUNT = 2 };

typedef struct {
    int fd;
    char path[64];
    char name[128];
    bool keys[KEY_CNT];
    int abs[ABS_CNT];
    bool dropped;
} source_t;

typedef struct {
    bool keys[KEY_CNT];
    int abs[ABS_CNT];
} output_t;

typedef struct {
    int side;
    int src;
    int dst;
} keymap_t;

static const keymap_t kCommonKeys[] = {
    {SIDE_LEFT, BTN_TL, BTN_TL},          // L
    {SIDE_LEFT, BTN_TL2, BTN_TL2},        // ZL
    {SIDE_LEFT, BTN_SELECT, BTN_SELECT},  // -
    {SIDE_LEFT, BTN_THUMBL, BTN_THUMBL},  // left stick click
    {SIDE_LEFT, BTN_Z, OUT_BUTTON_C},     // Capture
    {SIDE_RIGHT, BTN_TR, BTN_TR},         // R
    {SIDE_RIGHT, BTN_TR2, BTN_TR2},       // ZR
    {SIDE_RIGHT, BTN_START, BTN_START},   // +
    {SIDE_RIGHT, BTN_MODE, BTN_MODE},     // Home
    {SIDE_RIGHT, BTN_THUMBR, BTN_THUMBR}, // right stick click
};

// hid-nintendo reports face buttons by position: A=EAST, B=SOUTH, X=NORTH, Y=WEST.
static const keymap_t kFacePositional[] = {
    {SIDE_RIGHT, BTN_SOUTH, OUT_BUTTON_A},
    {SIDE_RIGHT, BTN_EAST, OUT_BUTTON_B},
    {SIDE_RIGHT, BTN_WEST, OUT_BUTTON_X},
    {SIDE_RIGHT, BTN_NORTH, OUT_BUTTON_Y},
};

static const keymap_t kFaceNintendo[] = {
    {SIDE_RIGHT, BTN_EAST, OUT_BUTTON_A},
    {SIDE_RIGHT, BTN_SOUTH, OUT_BUTTON_B},
    {SIDE_RIGHT, BTN_NORTH, OUT_BUTTON_X},
    {SIDE_RIGHT, BTN_WEST, OUT_BUTTON_Y},
};

static const int kOutKeys[] = {
    OUT_BUTTON_A, OUT_BUTTON_B, OUT_BUTTON_C, OUT_BUTTON_X, OUT_BUTTON_Y,
    BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE,
    BTN_THUMBL, BTN_THUMBR,
};

static const struct {
    int code;
    int min;
    int max;
    int flat;
} kOutAxes[] = {
    {ABS_X, -STICK_MAX, STICK_MAX, STICK_FLAT},   // left stick  -> AXIS_X
    {ABS_Y, -STICK_MAX, STICK_MAX, STICK_FLAT},   //             -> AXIS_Y
    {ABS_Z, -STICK_MAX, STICK_MAX, STICK_FLAT},   // right stick -> AXIS_Z
    {ABS_RZ, -STICK_MAX, STICK_MAX, STICK_FLAT},  //             -> AXIS_RZ
    {ABS_BRAKE, 0, TRIGGER_MAX, 0},               // ZL -> AXIS_LTRIGGER
    {ABS_GAS, 0, TRIGGER_MAX, 0},                 // ZR -> AXIS_RTRIGGER
    {ABS_HAT0X, -1, 1, 0},                        // D-pad -> AXIS_HAT_X
    {ABS_HAT0Y, -1, 1, 0},                        //       -> AXIS_HAT_Y
};

#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

// Serializes merger_start()/merger_stop(), which may arrive on different binder threads.
static pthread_mutex_t g_control_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_thread_started;  // a joinable merge thread exists (guarded by g_control_lock)

// Guards the fields read by merger_status_json().
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static bool g_active;  // the merge loop is running
static pthread_t g_thread;
static char g_error[256];
static char g_virtual_path[64];

// Owned by the merge thread while it runs.
static int g_stop_fd = -1;
static int g_uinput_fd = -1;
static char g_names[SIDE_COUNT][128];
static source_t g_src[SIDE_COUNT];
static output_t g_out;

static atomic_int g_flags;
static atomic_bool g_log_stderr;

static void log_msg(int prio, const char *fmt, ...) {
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_write(prio, TAG, msg);
    if (atomic_load(&g_log_stderr)) fprintf(stderr, "%s\n", msg);
}

#define LOGI(...) log_msg(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) log_msg(ANDROID_LOG_WARN, __VA_ARGS__)

// Caller must hold g_lock.
static void set_error_locked(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, sizeof g_error, fmt, ap);
    va_end(ap);
    LOGW("%s", g_error);
}

static void set_error(const char *fmt, ...) {
    char msg[sizeof g_error];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    pthread_mutex_lock(&g_lock);
    set_error_locked("%s", msg);
    pthread_mutex_unlock(&g_lock);
}

static void apply_keymap(output_t *out, const keymap_t *map, size_t len) {
    for (size_t i = 0; i < len; i++) {
        if (g_src[map[i].side].keys[map[i].src]) out->keys[map[i].dst] = true;
    }
}

static void compute_output(output_t *out) {
    const source_t *l = &g_src[SIDE_LEFT];
    const source_t *r = &g_src[SIDE_RIGHT];

    memset(out, 0, sizeof *out);
    apply_keymap(out, kCommonKeys, ARRAY_LEN(kCommonKeys));
    if (atomic_load(&g_flags) & MERGER_FLAG_NINTENDO_LAYOUT) {
        apply_keymap(out, kFaceNintendo, ARRAY_LEN(kFaceNintendo));
    } else {
        apply_keymap(out, kFacePositional, ARRAY_LEN(kFacePositional));
    }

    out->abs[ABS_X] = l->abs[ABS_X];
    out->abs[ABS_Y] = l->abs[ABS_Y];
    out->abs[ABS_Z] = r->abs[ABS_RX];
    out->abs[ABS_RZ] = r->abs[ABS_RY];
    out->abs[ABS_BRAKE] = l->keys[BTN_TL2] ? TRIGGER_MAX : 0;
    out->abs[ABS_GAS] = r->keys[BTN_TR2] ? TRIGGER_MAX : 0;
    out->abs[ABS_HAT0X] = (int)l->keys[BTN_DPAD_RIGHT] - (int)l->keys[BTN_DPAD_LEFT];
    out->abs[ABS_HAT0Y] = (int)l->keys[BTN_DPAD_DOWN] - (int)l->keys[BTN_DPAD_UP];
}

// Writes whatever changed since the last report to the virtual controller.
static void emit_output(void) {
    output_t next;
    struct input_event evs[ARRAY_LEN(kOutKeys) + ARRAY_LEN(kOutAxes) + 1];
    size_t n = 0;

    compute_output(&next);
    memset(evs, 0, sizeof evs);
    for (size_t i = 0; i < ARRAY_LEN(kOutKeys); i++) {
        int code = kOutKeys[i];
        if (next.keys[code] != g_out.keys[code]) {
            evs[n].type = EV_KEY;
            evs[n].code = code;
            evs[n].value = next.keys[code];
            n++;
        }
    }
    for (size_t i = 0; i < ARRAY_LEN(kOutAxes); i++) {
        int code = kOutAxes[i].code;
        if (next.abs[code] != g_out.abs[code]) {
            evs[n].type = EV_ABS;
            evs[n].code = code;
            evs[n].value = next.abs[code];
            n++;
        }
    }
    if (n == 0) return;
    evs[n].type = EV_SYN;
    evs[n].code = SYN_REPORT;
    n++;

    ssize_t written = write(g_uinput_fd, evs, n * sizeof evs[0]);
    if (written != (ssize_t)(n * sizeof evs[0])) {
        LOGW("uinput write failed: %s", strerror(errno));
        return;
    }
    g_out = next;
}

// Reloads key and axis state after attaching or after the kernel dropped events.
static void resync(source_t *s) {
    uint8_t bits[KEY_CNT / 8 + 1];
    memset(bits, 0, sizeof bits);
    if (ioctl(s->fd, EVIOCGKEY(sizeof bits), bits) >= 0) {
        for (int k = 0; k < KEY_CNT; k++) s->keys[k] = bits[k / 8] & (1 << (k % 8));
    }
    static const int kAxes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY};
    for (size_t i = 0; i < ARRAY_LEN(kAxes); i++) {
        struct input_absinfo info;
        if (ioctl(s->fd, EVIOCGABS(kAxes[i]), &info) >= 0) s->abs[kAxes[i]] = info.value;
    }
}

static void detach(int side) {
    source_t *s = &g_src[side];
    if (s->fd < 0) return;
    ioctl(s->fd, EVIOCGRAB, 0);
    close(s->fd);
    LOGI("released %s (%s)", s->name, s->path);

    pthread_mutex_lock(&g_lock);
    s->fd = -1;
    s->path[0] = '\0';
    s->name[0] = '\0';
    pthread_mutex_unlock(&g_lock);

    memset(s->keys, 0, sizeof s->keys);
    memset(s->abs, 0, sizeof s->abs);
    s->dropped = false;
}

static void try_attach(const char *path) {
    for (int side = 0; side < SIDE_COUNT; side++) {
        if (g_src[side].fd >= 0 && strcmp(g_src[side].path, path) == 0) return;
    }
    if (strcmp(path, g_virtual_path) == 0) return;

    // Permissions may not be applied yet for a node that just appeared; the
    // following IN_ATTRIB or periodic rescan retries it.
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return;

    char name[128] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) < 0) {
        close(fd);
        return;
    }
    int side = -1;
    for (int i = 0; i < SIDE_COUNT; i++) {
        if (g_src[i].fd < 0 && strcmp(name, g_names[i]) == 0) side = i;
    }
    if (side < 0) {
        close(fd);
        return;
    }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        set_error("Could not grab %s (%s): %s", name, path, strerror(errno));
        close(fd);
        return;
    }

    source_t *s = &g_src[side];
    pthread_mutex_lock(&g_lock);
    s->fd = fd;
    snprintf(s->path, sizeof s->path, "%s", path);
    snprintf(s->name, sizeof s->name, "%s", name);
    g_error[0] = '\0';
    pthread_mutex_unlock(&g_lock);

    resync(s);
    LOGI("grabbed %s (%s)", name, path);
    emit_output();
}

static void rescan(void) {
    DIR *dir = opendir(INPUT_DIR);
    if (dir == NULL) {
        set_error("Cannot list %s: %s", INPUT_DIR, strerror(errno));
        return;
    }
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof path, INPUT_DIR "/%s", entry->d_name);
        try_attach(path);
        if (g_src[SIDE_LEFT].fd >= 0 && g_src[SIDE_RIGHT].fd >= 0) break;
    }
    closedir(dir);
}

// Returns false once the device is gone.
static bool read_source(source_t *s) {
    struct input_event evs[64];
    for (;;) {
        ssize_t len = read(s->fd, evs, sizeof evs);
        if (len < 0) return errno == EAGAIN || errno == EINTR;
        if (len == 0) return false;

        size_t count = (size_t)len / sizeof evs[0];
        for (size_t i = 0; i < count; i++) {
            const struct input_event *ev = &evs[i];
            if (ev->type == EV_SYN) {
                if (ev->code == SYN_DROPPED) {
                    s->dropped = true;
                } else if (ev->code == SYN_REPORT) {
                    if (s->dropped) {
                        s->dropped = false;
                        resync(s);
                    }
                    emit_output();
                }
            } else if (s->dropped) {
                continue;
            } else if (ev->type == EV_KEY && ev->code < KEY_CNT) {
                s->keys[ev->code] = ev->value != 0;
            } else if (ev->type == EV_ABS && ev->code < ABS_CNT) {
                s->abs[ev->code] = ev->value;
            }
        }
    }
}

static int create_virtual_device(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    bool ok = ioctl(fd, UI_SET_EVBIT, EV_KEY) >= 0 && ioctl(fd, UI_SET_EVBIT, EV_ABS) >= 0;
    for (size_t i = 0; ok && i < ARRAY_LEN(kOutKeys); i++) {
        ok = ioctl(fd, UI_SET_KEYBIT, kOutKeys[i]) >= 0;
    }
    for (size_t i = 0; ok && i < ARRAY_LEN(kOutAxes); i++) {
        struct uinput_abs_setup abs;
        memset(&abs, 0, sizeof abs);
        abs.code = kOutAxes[i].code;
        abs.absinfo.minimum = kOutAxes[i].min;
        abs.absinfo.maximum = kOutAxes[i].max;
        abs.absinfo.flat = kOutAxes[i].flat;
        ok = ioctl(fd, UI_ABS_SETUP, &abs) >= 0;
    }
    if (ok) {
        struct uinput_setup setup;
        memset(&setup, 0, sizeof setup);
        setup.id.bustype = BUS_USB;
        setup.id.vendor = VIRTUAL_VENDOR;
        setup.id.product = VIRTUAL_PRODUCT;
        setup.id.version = VIRTUAL_VERSION;
        snprintf(setup.name, sizeof setup.name, "%s", VIRTUAL_NAME);
        ok = ioctl(fd, UI_DEV_SETUP, &setup) >= 0 && ioctl(fd, UI_DEV_CREATE) >= 0;
    }
    if (!ok) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

// Finds the /dev/input/eventN node backing the uinput device (informational only).
static void find_virtual_path(int uinput_fd, char *out, size_t len) {
    char sysname[64] = {0};
    out[0] = '\0';
    if (ioctl(uinput_fd, UI_GET_SYSNAME(sizeof sysname - 1), sysname) < 0) return;

    char sys_path[128];
    snprintf(sys_path, sizeof sys_path, "/sys/devices/virtual/input/%s", sysname);
    DIR *dir = opendir(sys_path);
    if (dir == NULL) return;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) == 0) {
            snprintf(out, len, INPUT_DIR "/%s", entry->d_name);
            break;
        }
    }
    closedir(dir);
}

static void *merge_thread(void *unused) {
    (void)unused;
    int inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotify_fd >= 0 && inotify_add_watch(inotify_fd, INPUT_DIR, IN_CREATE | IN_ATTRIB) < 0) {
        LOGW("inotify on %s failed: %s; relying on periodic rescans", INPUT_DIR, strerror(errno));
        close(inotify_fd);
        inotify_fd = -1;
    }

    rescan();
    for (;;) {
        struct pollfd pfds[2 + SIDE_COUNT];
        int side_index[SIDE_COUNT] = {-1, -1};
        int inotify_index = -1;
        nfds_t n = 0;

        pfds[n++] = (struct pollfd){.fd = g_stop_fd, .events = POLLIN};
        if (inotify_fd >= 0) {
            inotify_index = (int)n;
            pfds[n++] = (struct pollfd){.fd = inotify_fd, .events = POLLIN};
        }
        for (int side = 0; side < SIDE_COUNT; side++) {
            if (g_src[side].fd < 0) continue;
            side_index[side] = (int)n;
            pfds[n++] = (struct pollfd){.fd = g_src[side].fd, .events = POLLIN};
        }

        bool missing = g_src[SIDE_LEFT].fd < 0 || g_src[SIDE_RIGHT].fd < 0;
        int ready = poll(pfds, n, missing ? RESCAN_INTERVAL_MS : -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            set_error("poll failed: %s", strerror(errno));
            break;
        }
        if (pfds[0].revents) break;

        bool want_rescan = ready == 0;
        if (inotify_index >= 0 && (pfds[inotify_index].revents & POLLIN)) {
            char buf[4096];
            while (read(inotify_fd, buf, sizeof buf) > 0) {
            }
            want_rescan = true;
        }
        for (int side = 0; side < SIDE_COUNT; side++) {
            if (side_index[side] < 0) continue;
            short revents = pfds[side_index[side]].revents;
            if (revents == 0) continue;
            bool alive = (revents & POLLIN) ? read_source(&g_src[side]) : true;
            if (!alive || (revents & (POLLHUP | POLLERR | POLLNVAL))) {
                detach(side);
                emit_output();
                want_rescan = true;
            }
        }
        if (want_rescan && (g_src[SIDE_LEFT].fd < 0 || g_src[SIDE_RIGHT].fd < 0)) rescan();
    }

    for (int side = 0; side < SIDE_COUNT; side++) detach(side);
    emit_output();
    if (inotify_fd >= 0) close(inotify_fd);

    pthread_mutex_lock(&g_lock);
    g_active = false;
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

static void destroy_virtual_device(void) {
    ioctl(g_uinput_fd, UI_DEV_DESTROY);
    close(g_uinput_fd);
    g_uinput_fd = -1;
    pthread_mutex_lock(&g_lock);
    g_virtual_path[0] = '\0';
    pthread_mutex_unlock(&g_lock);
}

// Caller must hold g_control_lock.
static void join_thread(void) {
    if (!g_thread_started) return;
    uint64_t one = 1;
    write(g_stop_fd, &one, sizeof one);
    pthread_join(g_thread, NULL);
    g_thread_started = false;
    close(g_stop_fd);
    g_stop_fd = -1;
    destroy_virtual_device();
    LOGI("virtual controller removed");
}

int merger_start(const merger_config *config) {
    pthread_mutex_lock(&g_control_lock);
    atomic_store(&g_flags, config->flags);
    pthread_mutex_lock(&g_lock);
    bool active = g_active;
    pthread_mutex_unlock(&g_lock);
    if (active) {
        pthread_mutex_unlock(&g_control_lock);
        return 0;
    }
    join_thread();  // reap a loop that exited on its own

    pthread_mutex_lock(&g_lock);
    snprintf(g_names[SIDE_LEFT], sizeof g_names[0], "%s",
             config->left_name ? config->left_name : DEFAULT_LEFT_NAME);
    snprintf(g_names[SIDE_RIGHT], sizeof g_names[0], "%s",
             config->right_name ? config->right_name : DEFAULT_RIGHT_NAME);
    memset(g_src, 0, sizeof g_src);
    for (int side = 0; side < SIDE_COUNT; side++) g_src[side].fd = -1;
    memset(&g_out, 0, sizeof g_out);

    pthread_mutex_unlock(&g_lock);

    int fd = create_virtual_device();
    if (fd < 0) {
        set_error("Cannot create virtual controller via /dev/uinput: %s", strerror(-fd));
        pthread_mutex_unlock(&g_control_lock);
        return fd;
    }
    g_uinput_fd = fd;
    char virtual_path[sizeof g_virtual_path];
    find_virtual_path(fd, virtual_path, sizeof virtual_path);
    LOGI("virtual controller created at %s", virtual_path[0] ? virtual_path : "(unknown)");

    g_stop_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    int err = g_stop_fd < 0 ? errno : 0;
    if (err == 0) {
        pthread_mutex_lock(&g_lock);
        snprintf(g_virtual_path, sizeof g_virtual_path, "%s", virtual_path);
        g_active = true;
        pthread_mutex_unlock(&g_lock);
        err = pthread_create(&g_thread, NULL, merge_thread, NULL);
    }
    if (err != 0) {
        set_error("Cannot start merge thread: %s", strerror(err));
        pthread_mutex_lock(&g_lock);
        g_active = false;
        pthread_mutex_unlock(&g_lock);
        if (g_stop_fd >= 0) close(g_stop_fd);
        g_stop_fd = -1;
        destroy_virtual_device();
        pthread_mutex_unlock(&g_control_lock);
        return -err;
    }
    g_thread_started = true;
    pthread_mutex_unlock(&g_control_lock);
    return 0;
}

void merger_stop(void) {
    pthread_mutex_lock(&g_control_lock);
    join_thread();
    pthread_mutex_unlock(&g_control_lock);
}

void merger_set_flags(int flags) {
    atomic_store(&g_flags, flags);
}

void merger_set_log_stderr(bool enabled) {
    atomic_store(&g_log_stderr, enabled);
}

static void json_string(char *out, size_t len, const char *s) {
    size_t o = 0;
    if (len < 3) return;
    out[o++] = '"';
    for (; *s && o + 7 < len; s++) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') {
            out[o++] = '\\';
            out[o++] = (char)c;
        } else if (c < 0x20) {
            o += (size_t)snprintf(out + o, len - o, "\\u%04x", c);
        } else {
            out[o++] = (char)c;
        }
    }
    out[o++] = '"';
    out[o] = '\0';
}

static void json_source(char *out, size_t len, const source_t *s) {
    if (s->fd < 0) {
        snprintf(out, len, "null");
        return;
    }
    char path[160];
    char name[300];
    json_string(path, sizeof path, s->path);
    json_string(name, sizeof name, s->name);
    snprintf(out, len, "{\"path\":%s,\"name\":%s}", path, name);
}

void merger_status_json(char *buf, size_t len) {
    char left[512];
    char right[512];
    char error[600];
    char virtual_path[160];

    pthread_mutex_lock(&g_lock);
    json_source(left, sizeof left, &g_src[SIDE_LEFT]);
    json_source(right, sizeof right, &g_src[SIDE_RIGHT]);
    json_string(error, sizeof error, g_error);
    json_string(virtual_path, sizeof virtual_path, g_virtual_path);
    bool active = g_active;
    pthread_mutex_unlock(&g_lock);

    snprintf(buf, len,
             "{\"running\":%s,\"flags\":%d,\"virtualPath\":%s,\"left\":%s,\"right\":%s,\"error\":%s}",
             active ? "true" : "false", atomic_load(&g_flags), virtual_path, left, right, error);
}
