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
    @Volatile private var remap = IntArray(0)
    private val hidHost = ShellHidHost()

    init {
        JoyConRumble(hidHost) { flags and MergeStatus.FLAG_RUMBLE != 0 }.start()
    }

    override fun destroy() {
        NativeMerger.stop()
        exitProcess(0)
    }

    override fun start(flags: Int): Int = startWithRemap(flags, IntArray(0))

    override fun startWithRemap(flags: Int, remap: IntArray): Int {
        val remapChanged = !remap.contentEquals(this.remap)
        this.flags = flags
        this.remap = remap
        if (restartIfChanged(flags, remapChanged)) return 0
        return NativeMerger.startWithRemap(flags, remap)
    }

    override fun stop() = NativeMerger.stop()

    override fun setFlags(flags: Int) {
        this.flags = flags
        if (!restartIfChanged(flags, remapChanged = false)) NativeMerger.setFlags(flags)
    }

    /**
     * The identity, button layout and overrides are fixed when the virtual
     * controller is created, so changing any of them means recreating it.
     */
    private fun restartIfChanged(flags: Int, remapChanged: Boolean): Boolean {
        val status = MergeStatus.parse(NativeMerger.status())
        if (!status.running) return false
        val xbox = flags and MergeStatus.FLAG_XBOX_IDENTITY != 0
        val nintendo = flags and MergeStatus.FLAG_NINTENDO_LAYOUT != 0
        val wasNintendo = status.flags and MergeStatus.FLAG_NINTENDO_LAYOUT != 0
        if (status.xboxIdentity == xbox && nintendo == wasNintendo && !remapChanged) return false
        Log.i(TAG, "recreating the virtual controller (Xbox: $xbox, Nintendo layout: $nintendo, remap changed: $remapChanged)")
        NativeMerger.stop()
        NativeMerger.startWithRemap(flags, remap)
        return true
    }

    override fun status(): String = NativeMerger.status()

    override fun buildTime(): Long = BuildConfig.BUILD_TIME

    override fun takeRawPress(): Long = NativeMerger.takeRawPress()

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
        return NativeMerger.startWithRemap(flags, remap) == 0
    }

    private companion object {
        const val TAG = "MergeUserService"
        const val DISCONNECT_TIMEOUT_MS = 4000L
        const val POLL_MS = 100L
    }
}
