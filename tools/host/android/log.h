// Host-build stand-in for the NDK's <android/log.h>, so merger.c compiles
// unchanged for local testing. See tools/build_tools.sh --host.
#pragma once

enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5 };

void __android_log_write(int prio, const char *tag, const char *text);