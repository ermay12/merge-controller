cmake_minimum_required(VERSION 3.22.1)
project(joymerge C)

add_library(joymerge SHARED merger.c jni_bridge.c)
target_compile_options(joymerge PRIVATE -Wall -Wextra -Werror)
target_link_libraries(joymerge log)
# Required for devices with 16 KB memory pages (NDK r28+ does this by default).
target_link_options(joymerge PRIVATE -Wl,-z,max-page-size=16384)
