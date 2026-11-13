#!/bin/sh
# Builds the debugging tools (joymerge, fakejoy).
#   build_tools.sh          arm64, for adb push to a device (needs the NDK)
#   build_tools.sh --host   native host build, for local testing with fakejoy
set -e
cd "$(dirname "$0")"

if [ "${1:-}" = "--host" ]; then
    CC="${CC:-cc}"
    # -Wno-format-truncation: host gcc flags safe snprintf truncation of evdev
    # names that the NDK's clang accepts.
    CFLAGS="-O2 -Wall -Wextra -Werror -Wno-format-truncation -D_GNU_SOURCE -I../app/src/main/cpp -Ihost"
    mkdir -p build
    "$CC" $CFLAGS -o build/joymerge joymerge_cli.c ../app/src/main/cpp/merger.c host/android_log.c -pthread
    "$CC" $CFLAGS -o build/fakejoy fakejoy.c
    echo "Built host tools: tools/build/joymerge and tools/build/fakejoy"
    exit 0
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    if [ -d "$HOME/Library/Android/sdk" ]; then SDK="$HOME/Library/Android/sdk"; else SDK="$HOME/Android/Sdk"; fi
fi
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n 1)}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "No Android NDK found. Set ANDROID_NDK_HOME, or ANDROID_HOME to an SDK with an NDK installed." >&2
    exit 1
fi
# The NDK ships only x86_64 host tools for macOS; they're universal binaries that also run on Apple Silicon.
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android31-clang"
CFLAGS="-O2 -Wall -Wextra -Werror -I../app/src/main/cpp -Wl,-z,max-page-size=16384"

mkdir -p build
"$CC" $CFLAGS -o build/joymerge joymerge_cli.c ../app/src/main/cpp/merger.c -llog
"$CC" $CFLAGS -o build/fakejoy fakejoy.c
echo "Built tools/build/joymerge and tools/build/fakejoy"
