package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MediaAudioBuffer

/** Retire the perf20 audio experiment and apply the requested EX5 identity once. */
object WmEx5StableDefaults {
    @Synchronized
    fun applyOnce(context: Context) {
        if (!WmEx5PerformanceProfile.isTestPackage(context)) return
        val profile = context.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        if (profile.getBoolean("perf21_stable_defaults_applied", false)) return
        val mainSaved = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putInt("media_buffer_ms", MediaAudioBuffer.DEFAULT_MILLIS)
            .putString("model", WmEx5PerformanceProfile.MODEL)
            .putString("oem_label", WmEx5PerformanceProfile.OEM_LABEL).commit()
        val startupSaved = context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putBoolean("auto_connect", false).commit()
        // Use the packaged five-bar WM icon on this upgrade; later custom icons remain editable.
        val customIcon = AirPlayPersistence.loadCustomAirPlayIconFile(context)
        val iconCleared = customIcon == null || customIcon.delete()
        if (mainSaved && startupSaved && iconCleared) {
            profile.edit().putBoolean("perf21_stable_defaults_applied", true).commit()
        }
    }
}
