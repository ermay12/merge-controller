#include <jni.h>

#include "merger.h"

JNIEXPORT jint JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_startWithRemap(JNIEnv *env, jclass clazz, jint flags,
                                                             jintArray remap) {
    (void)clazz;
    merger_config config = {.flags = flags, .left_name = NULL, .right_name = NULL};
    jsize len = remap != NULL ? (*env)->GetArrayLength(env, remap) : 0;
    jint *elems = len > 0 ? (*env)->GetIntArrayElements(env, remap, NULL) : NULL;
    config.remap = elems;
    config.remap_count = (size_t)(len / 3);
    int result = merger_start(&config);
    if (elems != NULL) (*env)->ReleaseIntArrayElements(env, remap, elems, JNI_ABORT);
    return result;
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

JNIEXPORT jlong JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_awaitRumble(JNIEnv *env, jclass clazz, jint seq,
                                                          jint timeout_ms) {
    (void)env;
    (void)clazz;
    return (jlong)merger_await_rumble((uint32_t)seq, timeout_ms);
}

JNIEXPORT jlong JNICALL
Java_com_ermay12_mergecontroller_NativeMerger_takeRawPress(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return (jlong)merger_take_raw_press();
}
