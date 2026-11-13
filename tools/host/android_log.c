#include <android/log.h>
#include <stdio.h>

void __android_log_write(int prio, const char *tag, const char *text) {
    (void)prio;
    fprintf(stderr, "%s: %s\n", tag, text);
}