package com.ermay12.mergecontroller

import kotlin.system.exitProcess

/**
 * Shizuku runs this in its own process as the shell user, which may read and
 * grab /dev/input/event* and create devices through /dev/uinput. It is started
 * as a daemon, so merging continues after the app's own process is gone.
 */
class MergeUserService : IMergeService.Stub() {
    override fun destroy() {
        NativeMerger.stop()
        exitProcess(0)
    }

    override fun start(flags: Int): Int = NativeMerger.start(flags)

    override fun stop() = NativeMerger.stop()

    override fun setFlags(flags: Int) = NativeMerger.setFlags(flags)

    override fun status(): String = NativeMerger.status()
}
