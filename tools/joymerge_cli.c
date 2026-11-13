// Runs the merge engine from an adb shell, for debugging without the app:
//   joymerge [--nintendo] [--xbox] [--left-name NAME] [--right-name NAME]
//            [--remap side,src,dst,...] [--seconds N]
// --remap takes flat triples, e.g. --remap 1,308,304,1,304,305,1,305,308
// (right Joy-Con: 308->304, 304->305, 305->308). dst -1 unmaps.
#include <inttypes.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "merger.h"

static volatile sig_atomic_t g_quit;

static void on_signal(int sig) {
    (void)sig;
    g_quit = 1;
}

// Prints rumble requests that apps send to the virtual controller.
static void *rumble_printer(void *unused) {
    (void)unused;
    uint32_t seq = 0;
    for (;;) {
        uint64_t r = merger_await_rumble(seq, 1000);
        if ((uint32_t)(r >> 32) == seq) continue;
        seq = (uint32_t)(r >> 32);
        printf("rumble strong=%" PRIu32 " weak=%" PRIu32 "\n", (uint32_t)(r >> 16) & 0xFFFF, (uint32_t)r & 0xFFFF);
        fflush(stdout);
    }
    return NULL;
}

// Prints raw Joy-Con button presses, for testing merger_take_raw_press().
static void *press_printer(void *unused) {
    (void)unused;
    for (;;) {
        uint64_t p = merger_take_raw_press();
        if (p != 0) {
            printf("raw press side=%" PRIu32 " code=%" PRIu32 "\n",
                   (uint32_t)(p >> 16) & 0xFFFF, (uint32_t)p & 0xFFFF);
            fflush(stdout);
        }
        usleep(50 * 1000);
    }
    return NULL;
}

int main(int argc, char **argv) {
    merger_config config = {0};
    int seconds = 0;
    static int remap[256];
    size_t remap_ints = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--nintendo") == 0) {
            config.flags |= MERGER_FLAG_NINTENDO_LAYOUT;
        } else if (strcmp(argv[i], "--xbox") == 0) {
            config.flags |= MERGER_FLAG_XBOX_IDENTITY;
        } else if (strcmp(argv[i], "--left-name") == 0 && i + 1 < argc) {
            config.left_name = argv[++i];
        } else if (strcmp(argv[i], "--right-name") == 0 && i + 1 < argc) {
            config.right_name = argv[++i];
        } else if (strcmp(argv[i], "--remap") == 0 && i + 1 < argc) {
            char buf[512];
            snprintf(buf, sizeof buf, "%s", argv[++i]);
            for (char *tok = strtok(buf, ","); tok != NULL; tok = strtok(NULL, ",")) {
                if (remap_ints >= sizeof remap / sizeof remap[0]) {
                    fprintf(stderr, "too many --remap values\n");
                    return 2;
                }
                remap[remap_ints++] = atoi(tok);
            }
        } else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) {
            seconds = atoi(argv[++i]);
        } else {
            fprintf(stderr, "usage: %s [--nintendo] [--xbox] [--left-name NAME] [--right-name NAME] "
                            "[--remap side,src,dst,...] [--seconds N]\n", argv[0]);
            return 2;
        }
    }
    if (remap_ints % 3 != 0) {
        fprintf(stderr, "--remap needs triples: side,src,dst[,side,src,dst...]\n");
        return 2;
    }
    config.remap = remap;
    config.remap_count = remap_ints / 3;

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    merger_set_log_stderr(true);

    char status[2048];
    char last[2048] = "";
    pthread_t printer;
    pthread_create(&printer, NULL, rumble_printer, NULL);
    pthread_detach(printer);
    pthread_t presses;
    pthread_create(&presses, NULL, press_printer, NULL);
    pthread_detach(presses);
    if (merger_start(&config) != 0) {
        merger_status_json(status, sizeof status);
        printf("%s\n", status);
        return 1;
    }
    for (int elapsed = 0; !g_quit && (seconds == 0 || elapsed < seconds * 10); elapsed++) {
        merger_status_json(status, sizeof status);
        if (strcmp(status, last) != 0) {
            printf("%s\n", status);
            fflush(stdout);
            strcpy(last, status);
        }
        usleep(100 * 1000);
    }
    merger_stop();
    merger_status_json(status, sizeof status);
    printf("%s\n", status);
    return 0;
}
