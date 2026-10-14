#include <jni.h>

#include "merger.h"

JNIEXPORT jint JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_start(JNIEnv *env, jclass clazz, jint flags) {
    (void)env;
    (void)clazz;
    merger_config config = {.flags = flags, .left_name = NULL, .right_name = NULL};
    return merger_start(&config);
}

JNIEXPORT void JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_stop(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    merger_stop();
}

JNIEXPORT void JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_setFlags(JNIEnv *env, jclass clazz, jint flags) {
    (void)env;
    (void)clazz;
    merger_set_flags(flags);
}

JNIEXPORT jstring JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_status(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char buf[2048];
    merger_status_json(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}
