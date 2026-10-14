package com.ermay12.mergecontroller

import org.json.JSONObject

/** Engine state reported by [IMergeService.status]. */
data class MergeStatus(
    val running: Boolean,
    val virtualPath: String,
    val left: Source?,
    val right: Source?,
    val error: String,
) {
    data class Source(val path: String, val name: String)

    companion object {
        const val FLAG_NINTENDO_LAYOUT = 0x1

        /** Names used by merger.c: the uinput device it creates and the hid-nintendo devices it grabs. */
        const val VIRTUAL_DEVICE_NAME = "Merged Joy-Con Controller"
        const val LEFT_JOYCON_NAME = "Nintendo Switch Left Joy-Con"
        const val RIGHT_JOYCON_NAME = "Nintendo Switch Right Joy-Con"

        fun parse(json: String): MergeStatus {
            val o = JSONObject(json)
            fun source(key: String): Source? =
                o.optJSONObject(key)?.let { Source(it.getString("path"), it.getString("name")) }
            return MergeStatus(
                running = o.getBoolean("running"),
                virtualPath = o.optString("virtualPath"),
                left = source("left"),
                right = source("right"),
                error = o.optString("error"),
            )
        }
    }
}
