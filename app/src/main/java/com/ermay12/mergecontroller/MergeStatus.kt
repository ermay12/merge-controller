package com.ermay12.mergecontroller

import android.view.InputDevice
import org.json.JSONObject
import java.security.MessageDigest

/** Engine state reported by [IMergeService.status]. */
data class MergeStatus(
    val running: Boolean,
    val virtualPath: String,
    val virtualName: String,
    val xboxIdentity: Boolean,
    val left: Source?,
    val right: Source?,
    val error: String,
) {
    /** A grabbed Joy-Con; [address] is its Bluetooth address. */
    data class Source(val path: String, val name: String, val address: String)

    companion object {
        const val FLAG_NINTENDO_LAYOUT = 0x1

        /** Handled by [JoyConRumble]; the native engine ignores it. */
        const val FLAG_RUMBLE = 0x2

        const val FLAG_XBOX_IDENTITY = 0x4

        /** Names of the hid-nintendo devices merger.c grabs. */
        const val LEFT_JOYCON_NAME = "Nintendo Switch Left Joy-Con"
        const val RIGHT_JOYCON_NAME = "Nintendo Switch Right Joy-Con"

        /**
         * Android's descriptors for the virtual controller's identities in
         * merger.c (generic 1209:0001, Xbox 045e:0b13). A real controller with the
         * same name and IDs also has a unique ID (its Bluetooth address) in its
         * descriptor; the virtual one doesn't, so its descriptor is the SHA-1 of
         * just ":vendor:product:".
         */
        private val VIRTUAL_DESCRIPTORS = listOf(0x1209 to 0x0001, 0x045e to 0x0b13)
            .map { (vendor, product) -> sha1Hex(":%04x:%04x:".format(vendor, product)) }
            .toSet()

        fun isMergedController(device: InputDevice) = device.descriptor in VIRTUAL_DESCRIPTORS

        fun parse(json: String): MergeStatus {
            val o = JSONObject(json)
            fun source(key: String): Source? =
                o.optJSONObject(key)?.let {
                    Source(it.getString("path"), it.getString("name"), it.optString("address"))
                }
            return MergeStatus(
                running = o.getBoolean("running"),
                virtualPath = o.optString("virtualPath"),
                virtualName = o.optString("virtualName"),
                xboxIdentity = o.optBoolean("xboxIdentity"),
                left = source("left"),
                right = source("right"),
                error = o.optString("error"),
            )
        }

        private fun sha1Hex(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
