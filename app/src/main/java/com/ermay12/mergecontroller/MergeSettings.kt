package com.ermay12.mergecontroller

import android.content.SharedPreferences

/** Settings shared by the main screen and the button mapping screen. */
object MergeSettings {
    const val PREFS_NAME = "settings"
    const val KEY_NINTENDO_LAYOUT = "nintendo_layout"
    const val KEY_RUMBLE = "rumble"
    const val KEY_XBOX_IDENTITY = "xbox_identity"
    private const val KEY_REMAP = "remap"

    fun flags(prefs: SharedPreferences): Int {
        var flags = 0
        if (prefs.getBoolean(KEY_NINTENDO_LAYOUT, false)) flags = flags or MergeStatus.FLAG_NINTENDO_LAYOUT
        if (prefs.getBoolean(KEY_RUMBLE, true)) flags = flags or MergeStatus.FLAG_RUMBLE
        if (prefs.getBoolean(KEY_XBOX_IDENTITY, true)) flags = flags or MergeStatus.FLAG_XBOX_IDENTITY
        return flags
    }

    /** User button overrides as flat triples [side, src, dst]; empty when none. */
    fun loadRemap(prefs: SharedPreferences): IntArray =
        prefs.getString(KEY_REMAP, "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toIntArray()

    fun saveRemap(prefs: SharedPreferences, remap: IntArray) {
        prefs.edit().putString(KEY_REMAP, remap.joinToString(",")).apply()
    }
}