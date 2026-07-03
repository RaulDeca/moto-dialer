package com.motocallrecorder

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("motocallrecorder_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ENABLED = "recording_enabled"
        private const val KEY_RECORD_VOIP = "record_voip"
        private const val KEY_THEME = "theme_mode"

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var recordVoip: Boolean
        get() = prefs.getBoolean(KEY_RECORD_VOIP, true)
        set(value) = prefs.edit().putBoolean(KEY_RECORD_VOIP, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()
}
