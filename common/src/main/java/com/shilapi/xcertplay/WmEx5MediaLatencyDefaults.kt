package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.MediaAudioBuffer

/** Apply the requested short music buffer once, including an existing 1000 ms test setting. */
object WmEx5MediaLatencyDefaults {
    @Synchronized
    fun applyOnce(context: Context) {
        if (!WmEx5PerformanceProfile.isTestPackage(context)) return
        val profile = context.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        if (profile.getBoolean("perf20_media_latency_defaults_applied", false)) return
        val saved = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putInt("media_buffer_ms", MediaAudioBuffer.LOW_LATENCY_DEFAULT_MILLIS).commit()
        if (saved) profile.edit().putBoolean("perf20_media_latency_defaults_applied", true).commit()
    }
}
