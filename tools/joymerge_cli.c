// Runs the merge engine from an adb shell, for debugging without the app:
//   joymerge [--nintendo] [--left-name NAME] [--right-name NAME] [--seconds N]
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

int main(int argc, char **argv) {
    merger_config config = {0};
    int seconds = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--nintendo") == 0) {
            config.flags |= MERGER_FLAG_NINTENDO_LAYOUT;
        } else if (strcmp(argv[i], "--left-name") == 0 && i + 1 < argc) {
            config.left_name = argv[++i];
        } else if (strcmp(argv[i], "--right-name") == 0 && i + 1 < argc) {
            config.right_name = argv[++i];
        } else if (strcmp(argv[i], "--seconds") == 0 && i + 1 < argc) {
            seconds = atoi(argv[++i]);
        } else {
            fprintf(stderr, "usage: %s [--nintendo] [--left-name NAME] [--right-name NAME] [--seconds N]\n", argv[0]);
            return 2;
        }
    }

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    merger_set_log_stderr(true);

    char status[2048];
    char last[2048] = "";
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
