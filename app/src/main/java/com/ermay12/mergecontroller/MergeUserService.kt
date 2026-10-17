package com.ermay12.mergecontroller

import android.os.SystemClock
import android.util.Log
import kotlin.system.exitProcess

/**
 * Shizuku runs this in its own process as the shell user, which may read and
 * grab /dev/input/event*, create devices through /dev/uinput, and send reports
 * through the Bluetooth HID host. It is started as a daemon, so merging
 * continues after the app's own process is gone.
 */
class MergeUserService : IMergeService.Stub() {
    @Volatile private var flags = 0
    private val hidHost = ShellHidHost()

    init {
        JoyConRumble(hidHost) { flags and MergeStatus.FLAG_RUMBLE != 0 }.start()
    }

    override fun destroy() {
        NativeMerger.stop()
        exitProcess(0)
    }

    override fun start(flags: Int): Int {
        this.flags = flags
        restartIfIdentityChanged(flags)
        return NativeMerger.start(flags)
    }

    override fun stop() = NativeMerger.stop()

    override fun setFlags(flags: Int) {
        this.flags = flags
        if (!restartIfIdentityChanged(flags)) NativeMerger.setFlags(flags)
    }

    /** The identity is fixed when the virtual controller is created, so changing it means recreating it. */
    private fun restartIfIdentityChanged(flags: Int): Boolean {
        val status = MergeStatus.parse(NativeMerger.status())
        val xbox = flags and MergeStatus.FLAG_XBOX_IDENTITY != 0
        if (!status.running || status.xboxIdentity == xbox) return false
        Log.i(TAG, "switching the virtual controller's identity (Xbox: $xbox)")
        NativeMerger.stop()
        NativeMerger.start(flags)
        return true
    }

    override fun status(): String = NativeMerger.status()

    override fun buildTime(): Long = BuildConfig.BUILD_TIME

    /**
     * Games like GTA: San Andreas give player 1 to the gamepad with the lowest
     * device ID, and Android assigns IDs in connection order. Joy-Cons can't be
     * reconnected by the phone, but a button press reconnects them, after which
     * they get IDs above the virtual controller's.
     */
    override fun reconnectJoyCons(): Boolean {
        val status = MergeStatus.parse(NativeMerger.status())
        val addresses = listOfNotNull(status.left?.address, status.right?.address).filter { it.isNotEmpty() }
        if (!status.running || addresses.isEmpty()) return false
        if (addresses.count { hidHost.disconnect(it) } == 0) return false

        // Recreate the virtual controller only once the Joy-Cons are gone, so a
        // game that's already running frees their slots before it reappears.
        val deadline = SystemClock.elapsedRealtime() + DISCONNECT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = MergeStatus.parse(NativeMerger.status())
            // Stopped meanwhile: don't undo the stop.
            if (!current.running) return false
            if (current.left == null && current.right == null) break
            SystemClock.sleep(POLL_MS)
        }
        Log.i(TAG, "Joy-Cons disconnected; recreating the virtual controller")
        NativeMerger.stop()
        return NativeMerger.start(flags) == 0
    }

    private companion object {
        const val TAG = "MergeUserService"
        const val DISCONNECT_TIMEOUT_MS = 4000L
        const val POLL_MS = 100L
    }
}
