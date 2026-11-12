package dev.netcheck.app.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var slowThresholdMs: Long
        get() = prefs.getLong("slowThresholdMs", 200L)
        set(value) { prefs.edit().putLong("slowThresholdMs", value.coerceIn(50L, 5000L)).apply() }
    var speedConsent: Boolean
        get() = prefs.getBoolean("speedConsent", false)
        set(value) { prefs.edit().putBoolean("speedConsent", value).apply() }
    var speedLimitMiB: Int
        get() = prefs.getInt("speedLimitMiB", 20)
        set(value) { prefs.edit().putInt("speedLimitMiB", value.coerceIn(5, 100)).apply() }
}
