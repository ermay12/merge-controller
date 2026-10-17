// Runs the merge engine from an adb shell, for debugging without the app:
//   joymerge [--nintendo] [--xbox] [--left-name NAME] [--right-name NAME] [--seconds N]
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

int main(int argc, char **argv) {
    merger_config config = {0};
    int seconds = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--nintendo") == 0) {
            config.flags |= MERGER_FLAG_NINTENDO_LAYOUT;
        } else if (strcmp(argv[i], "--xbox") == 0) {
            config.flags |= MERGER_FLAG_XBOX_IDENTITY;
        } else if (strcmp(argv[i], "--left-name") == 0 && i + 1 < argc) {
            config.left_name = argv[++i];
        } else if (strcmp(argv[i], "--right-name") == 0 && i + 1 < argc) {
            config.right_name = argv[++i];
        } else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) {
            seconds = atoi(argv[++i]);
        } else {
            fprintf(stderr, "usage: %s [--nintendo] [--xbox] [--left-name NAME] [--right-name NAME] [--seconds N]\n", argv[0]);
            return 2;
        }
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    merger_set_log_stderr(true);

    char status[2048];
    char last[2048] = "";
    pthread_t printer;
    pthread_create(&printer, NULL, rumble_printer, NULL);
    pthread_detach(printer);
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
