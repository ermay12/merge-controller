package com.ermay12.mergecontroller

import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Forwards rumble that apps request on the virtual controller to the Joy-Cons.
 *
 * Kernels built without hid-nintendo force feedback (like the Pixel's) offer no
 * evdev rumble for the Joy-Cons, so this sends the Joy-Con rumble output report
 * (0x10) through Android's Bluetooth HID host instead. Runs inside
 * [MergeUserService].
 */
class JoyConRumble(private val hidHost: ShellHidHost, private val isEnabled: () -> Boolean) {

    private var packetCounter = 0

    // Where rumble last went, so stop packets still reach Joy-Cons that merging just released.
    private var lastLeft = ""
    private var lastRight = ""

    fun start() {
        thread(name = "JoyConRumble", isDaemon = true) { forwardLoop() }
    }

    private fun forwardLoop() {
        var seq = 0
        var pendingStops = 0
        while (true) {
            try {
                val result = NativeMerger.awaitRumble(seq, if (pendingStops > 0) REFRESH_MS else IDLE_WAIT_MS)
                seq = (result ushr 32).toInt()
                val value = if (isEnabled()) result.toInt() else 0
                if (value != 0) {
                    // Resent every REFRESH_MS while active, like the Linux driver, so
                    // a single lost packet can't cut rumble short or leave it stuck.
                    send(strong = value ushr 16, weak = value and 0xFFFF)
                    pendingStops = STOP_REPEATS
                } else if (pendingStops > 0) {
                    send(strong = 0, weak = 0)
                    pendingStops--
                }
            } catch (t: Throwable) {
                // Uncaught, this would end the whole service process and stop merging.
                Log.w(TAG, "rumble forwarding failed", t)
                SystemClock.sleep(ERROR_BACKOFF_MS)
            }
        }
    }

    private fun send(strong: Int, weak: Int) {
        val status = runCatching { MergeStatus.parse(NativeMerger.status()) }.getOrNull() ?: return
        var left = status.left?.address.orEmpty()
        var right = status.right?.address.orEmpty()
        if (strong == 0 && weak == 0) {
            // Joy-Cons keep their last rumble, so also stop any that merging just released.
            left = left.ifEmpty { lastLeft }
            right = right.ifEmpty { lastRight }
        } else {
            lastLeft = left
            lastRight = right
        }
        // Same split as the Linux hid-nintendo driver: strong motor -> left Joy-Con, weak -> right.
        for ((address, magnitude) in listOf(left to strong, right to weak)) {
            if (address.isEmpty()) continue
            val data = encodeRumble(magnitude / 65535.0)
            val report = "10%02X%s%s".format(packetCounter++ and 0xF, data, data)
            hidHost.sendData(address, report)
        }
    }

    companion object {
        private const val TAG = "JoyConRumble"
        private const val REFRESH_MS = 50
        private const val IDLE_WAIT_MS = 1000
        private const val STOP_REPEATS = 3
        private const val ERROR_BACKOFF_MS = 1000L

        /**
         * Encodes one Joy-Con actuator's rumble data (4 bytes, as hex): the
         * default 320 Hz high band and 160 Hz low band, both at [amplitude]
         * (0..1). Format: https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering/blob/master/rumble_data_table.md
         */
        fun encodeRumble(amplitude: Double): String {
            val amp = encodeAmplitude(amplitude.coerceIn(0.0, 1.0))
            val highFreq = 0x100  // 320 Hz
            val lowFreq = 0x40    // 160 Hz
            val lowAmp = ((amp and 1) shl 15) or (0x40 + amp / 2)
            return "%02X%02X%02X%02X".format(
                highFreq and 0xFF,
                amp * 2 + (highFreq shr 8),
                lowFreq + (lowAmp shr 8),
                lowAmp and 0xFF,
            )
        }

        /** Maps 0..1 onto the Joy-Con's logarithmic amplitude scale (0..100). */
        private fun encodeAmplitude(amp: Double): Int = when {
            amp <= 0.0 -> 0
            amp > 0.23 -> (log2(amp * 8.7) * 32).roundToInt().coerceAtMost(100)
            amp > 0.12 -> (log2(amp * 17) * 16).roundToInt()
            else -> (amp / 0.12 * 16).roundToInt().coerceAtLeast(1)
        }
    }
}
