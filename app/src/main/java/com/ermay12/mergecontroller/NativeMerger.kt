package com.ermay12.mergecontroller

/** JNI bindings to the merge engine (merger.c). Only used inside [MergeUserService]. */
object NativeMerger {
    init {
        System.loadLibrary("joymerge")
    }

    /**
     * Starts merging. [remap] holds user button overrides as flat triples
     * [side, src, dst]; side 0 = left, 1 = right, dst < 0 unmaps.
     * Returns 0 or a negative errno.
     */
    @JvmStatic external fun startWithRemap(flags: Int, remap: IntArray): Int

    @JvmStatic external fun stop()

    @JvmStatic external fun setFlags(flags: Int)

    @JvmStatic external fun status(): String

    /**
     * Waits until the requested rumble's sequence number differs from [seq] or
     * [timeoutMs] passes. Returns `(sequence shl 32) or (strong shl 16) or weak`.
     */
    @JvmStatic external fun awaitRumble(seq: Int, timeoutMs: Int): Long

    /**
     * Pops one raw Joy-Con button press, oldest first; 0 when none are queued.
     * Returns `(sequence shl 32) or (side shl 16) or evdevCode`.
     */
    @JvmStatic external fun takeRawPress(): Long
}
