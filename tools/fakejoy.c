// Creates two uinput devices that mimic hid-nintendo's Joy-Con nodes and
// plays a fixed input script, for testing the merge engine without hardware:
//   fakejoy [--prefix NAME_PREFIX] [--wait SECONDS] [--face A=304,B=308,X=305,Y=307]
// Devices are named "<prefix> Left Joy-Con" / "<prefix> Right Joy-Con".
// --face overrides which evdev code each printed face-button label reports;
// the default is mainline hid-nintendo's convention. Some phone kernels
// rotate these codes, and --face reproduces that.
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#define ARRAY_LEN(a) (sizeof(a) / sizeof((a)[0]))

static const int kLeftKeys[] = {BTN_Z, BTN_TL, BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_THUMBL,
                                BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT};
static const int kRightKeys[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR,
                                 BTN_TL2, BTN_TR2, BTN_START, BTN_MODE, BTN_THUMBR};

static void die(const char *what) {
    perror(what);
    exit(1);
}

static int create(const char *name, int product, const int *keys, size_t nkeys, int abs_x, int abs_y) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) die("open /dev/uinput");
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) die("UI_SET_EVBIT");
    for (size_t i = 0; i < nkeys; i++) {
        if (ioctl(fd, UI_SET_KEYBIT, keys[i]) < 0) die("UI_SET_KEYBIT");
    }
    int axes[] = {abs_x, abs_y};
    for (size_t i = 0; i < 2; i++) {
        struct uinput_abs_setup abs = {.code = axes[i]};
        abs.absinfo.minimum = -32767;
        abs.absinfo.maximum = 32767;
        abs.absinfo.fuzz = 250;
        abs.absinfo.flat = 500;
        if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) die("UI_ABS_SETUP");
    }
    struct uinput_setup setup = {0};
    setup.id.bustype = BUS_BLUETOOTH;
    setup.id.vendor = 0x057e;
    setup.id.product = product;
    setup.id.version = 0x8001;
    snprintf(setup.name, sizeof setup.name, "%s", name);
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) die("UI_DEV_CREATE");
    return fd;
}

static void send(int fd, int type, int code, int value) {
    struct input_event ev[2];
    memset(ev, 0, sizeof ev);
    ev[0].type = type;
    ev[0].code = code;
    ev[0].value = value;
    ev[1].type = EV_SYN;
    ev[1].code = SYN_REPORT;
    if (write(fd, ev, sizeof ev) != sizeof ev) die("write");
}

static void step(const char *label) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    printf("[%ld.%03ld] %s\n", (long)ts.tv_sec, ts.tv_nsec / 1000000, label);
    fflush(stdout);
}

static void tap(int fd, int code, const char *label) {
    step(label);
    send(fd, EV_KEY, code, 1);
    usleep(150 * 1000);
    send(fd, EV_KEY, code, 0);
    usleep(150 * 1000);
}

static void tap_face(int fd, int code, const char *position, int printed) {
    char buf[80];
    snprintf(buf, sizeof buf, "%c (%s) reports code %d", printed, position, code);
    tap(fd, code, buf);
}

// Parses "A=304,B=308,X=305,Y=307"; every code must be one the fake right
// Joy-Con advertises. Returns 0 on success.
static int parse_face(const char *spec, int *a, int *b, int *x, int *y) {
    char buf[128];
    snprintf(buf, sizeof buf, "%s", spec);
    for (char *tok = strtok(buf, ","); tok != NULL; tok = strtok(NULL, ",")) {
        char label;
        int code;
        if (sscanf(tok, " %c=%d", &label, &code) != 2) return -1;
        size_t i = 0;
        while (i < ARRAY_LEN(kRightKeys) && kRightKeys[i] != code) i++;
        if (i == ARRAY_LEN(kRightKeys)) return -1;
        switch (label) {
            case 'A': *a = code; break;
            case 'B': *b = code; break;
            case 'X': *x = code; break;
            case 'Y': *y = code; break;
            default: return -1;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *prefix = "Test";
    int wait = 3;
    int face_a = BTN_EAST, face_b = BTN_SOUTH, face_x = BTN_NORTH, face_y = BTN_WEST;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--prefix") == 0 && i + 1 < argc) {
            prefix = argv[++i];
        } else if (strcmp(argv[i], "--wait") == 0 && i + 1 < argc) {
            wait = atoi(argv[++i]);
        } else if (strcmp(argv[i], "--face") == 0 && i + 1 < argc) {
            if (parse_face(argv[++i], &face_a, &face_b, &face_x, &face_y) != 0) {
                fprintf(stderr, "invalid --face spec; example: A=304,B=308,X=305,Y=307\n");
                return 2;
            }
        } else {
            fprintf(stderr, "usage: %s [--prefix NAME_PREFIX] [--wait SECONDS] [--face A=304,B=308,X=305,Y=307]\n", argv[0]);
            return 2;
        }
    }

    char left_name[80];
    char right_name[80];
    snprintf(left_name, sizeof left_name, "%s Left Joy-Con", prefix);
    snprintf(right_name, sizeof right_name, "%s Right Joy-Con", prefix);
    int l = create(left_name, 0x2006, kLeftKeys, ARRAY_LEN(kLeftKeys), ABS_X, ABS_Y);
    int r = create(right_name, 0x2007, kRightKeys, ARRAY_LEN(kRightKeys), ABS_RX, ABS_RY);
    step("created fake Joy-Cons");
    sleep(wait);

    tap(l, BTN_TL, "L  -> BTN_TL");
    tap(l, BTN_TL2, "ZL -> BTN_TL2 + ABS_BRAKE");
    tap(l, BTN_SELECT, "Minus -> BTN_SELECT");
    tap(l, BTN_THUMBL, "LStick click -> BTN_THUMBL");
    tap(l, BTN_Z, "Capture -> BTN_C");
    tap(l, BTN_DPAD_UP, "Up -> HAT0Y -1");
    tap(l, BTN_DPAD_DOWN, "Down -> HAT0Y 1");
    tap(l, BTN_DPAD_LEFT, "Left -> HAT0X -1");
    tap(l, BTN_DPAD_RIGHT, "Right -> HAT0X 1");
    tap(l, BTN_TR, "Left SL -> (nothing)");
    tap(l, BTN_TR2, "Left SR -> (nothing)");

    tap_face(r, face_a, "right", 'A');
    tap_face(r, face_b, "bottom", 'B');
    tap_face(r, face_x, "top", 'X');
    tap_face(r, face_y, "left", 'Y');
    tap(r, BTN_TR, "R -> BTN_TR");
    tap(r, BTN_TR2, "ZR -> BTN_TR2 + ABS_GAS");
    tap(r, BTN_START, "Plus -> BTN_START");
    tap(r, BTN_MODE, "Home -> BTN_MODE");
    tap(r, BTN_THUMBR, "RStick click -> BTN_THUMBR");
    tap(r, BTN_TL, "Right SL -> (nothing)");
    tap(r, BTN_TL2, "Right SR -> (nothing)");

    step("left stick X=32767 Y=-32767 -> ABS_X/ABS_Y");
    send(l, EV_ABS, ABS_X, 32767);
    send(l, EV_ABS, ABS_Y, -32767);
    usleep(150 * 1000);
    send(l, EV_ABS, ABS_X, 0);
    send(l, EV_ABS, ABS_Y, 0);
    usleep(150 * 1000);

    step("right stick RX=-20000 RY=15000 -> ABS_Z/ABS_RZ");
    send(r, EV_ABS, ABS_RX, -20000);
    send(r, EV_ABS, ABS_RY, 15000);
    usleep(150 * 1000);
    send(r, EV_ABS, ABS_RX, 0);
    send(r, EV_ABS, ABS_RY, 0);
    usleep(150 * 1000);

    step("Up+Left chord -> HAT0X -1, HAT0Y -1");
    send(l, EV_KEY, BTN_DPAD_UP, 1);
    send(l, EV_KEY, BTN_DPAD_LEFT, 1);
    usleep(150 * 1000);
    send(l, EV_KEY, BTN_DPAD_UP, 0);
    send(l, EV_KEY, BTN_DPAD_LEFT, 0);
    usleep(150 * 1000);

    step("hold R + ZR, then disconnect right Joy-Con -> expect releases");
    send(r, EV_KEY, BTN_TR, 1);
    send(r, EV_KEY, BTN_TR2, 1);
    usleep(150 * 1000);
    ioctl(r, UI_DEV_DESTROY);
    close(r);
    sleep(1);

    step("reconnect right Joy-Con, press A");
    r = create(right_name, 0x2007, kRightKeys, ARRAY_LEN(kRightKeys), ABS_RX, ABS_RY);
    sleep(wait);
    tap_face(r, face_a, "right, after reconnect", 'A');

    step("done; removing fake Joy-Cons");
    ioctl(l, UI_DEV_DESTROY);
    ioctl(r, UI_DEV_DESTROY);
    return 0;
}
