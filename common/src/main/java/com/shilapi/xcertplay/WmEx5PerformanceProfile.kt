package com.shilapi.xcertplay

import android.content.Context

/** The EX5 test package keeps its radio requirement independent of video rollback. */
internal object WmEx5PerformanceProfile {
    const val PACKAGE = "com.shihab.diplay.wmex5"
    const val DEFAULT_CHANNEL = 44
    const val DEFAULT_RESOLUTION_PERCENT = 50
    const val DEFAULT_FPS = 18
    val frameRates = com.shilapi.xcertplay.airplay.AirPlayDisplaySettings.FPS_VALUES.filter { it >= 12 }
    val CHANNELS = listOf(36, 44, 157, 1, 6)
    const val MANUFACTURER = "威马"
    const val MODEL = "EX5"
    const val OEM_LABEL = "Weltmeister"
    private const val PREFS = "diplay_wm_ex5_performance"
    fun isTestPackage(context: Context): Boolean = context.packageName == PACKAGE
    fun enabled(context: Context): Boolean = isTestPackage(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("low_latency", true)
    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("low_latency", enabled).apply()
    }
    fun phoneMicrophoneOnly(context: Context): Boolean = isTestPackage(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("phone_microphone_only", false)

    fun savePhoneMicrophoneOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("phone_microphone_only", enabled).apply()
    }

    fun sanitizeFps(context: Context, value: Int): Int =
        if (isTestPackage(context) && value < 12) DEFAULT_FPS
        else com.shilapi.xcertplay.airplay.AirPlayDisplaySettings.sanitizeFps(value)
    fun channel(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("wireless_channel", DEFAULT_CHANNEL)
            .takeIf { it in CHANNELS } ?: DEFAULT_CHANNEL
    }.getOrDefault(DEFAULT_CHANNEL)

    fun saveChannel(context: Context, channel: Int) {
        require(channel in CHANNELS) { "Unsupported WM EX5 fixed Wi-Fi channel" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("wireless_channel", channel).apply()
    }
}
