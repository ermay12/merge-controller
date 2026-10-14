package com.ermay12.mergecontroller

/** JNI bindings to the merge engine (merger.c). Only used inside [MergeUserService]. */
object NativeMerger {
    init {
        System.loadLibrary("joymerge")
    }

    /** Returns 0 or a negative errno. */
    @JvmStatic external fun start(flags: Int): Int

    @JvmStatic external fun stop()

    @JvmStatic external fun setFlags(flags: Int)

    @JvmStatic external fun status(): String
}
