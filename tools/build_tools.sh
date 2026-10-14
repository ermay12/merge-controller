#!/bin/sh
# Builds the adb-shell debugging tools (joymerge, fakejoy) for arm64 devices.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -n 1)}"
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android29-clang"
CFLAGS="-O2 -Wall -Wextra -Werror -I../app/src/main/cpp -Wl,-z,max-page-size=16384"

mkdir -p build
"$CC" $CFLAGS -o build/joymerge joymerge_cli.c ../app/src/main/cpp/merger.c -llog
"$CC" $CFLAGS -o build/fakejoy fakejoy.c
echo "Built tools/build/joymerge and tools/build/fakejoy"
