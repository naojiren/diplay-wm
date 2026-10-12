package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Apply the requested perf18 defaults once, including installations upgraded from perf17.
 * Later driver choices, Wi-Fi credentials, pairing, playback routing and geometry stay intact.
 */
object WmEx5OptimizedDefaults {
    @Synchronized
    fun applyOnce(context: Context) {
        if (!WmEx5PerformanceProfile.isTestPackage(context)) return
        val profile = context.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        if (profile.getBoolean("perf18_defaults_applied", false)) {
            restoreMicrophoneOnce(profile)
            return
        }
        // Complete historical migrations first so opening either Activity cannot overwrite
        // the new defaults later. These migrations are already marked on perf17 installations.
        WmEx5Startup.applyDefaults(context)
        val mainSaved = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
            .putInt("display_fps", WmEx5PerformanceProfile.DEFAULT_FPS)
            .putBoolean("wireless_enabled", true)
            .putString("wireless_hotspot_mode", WirelessHotspotMode.WIFI_P2P.name)
            .commit()
        val clusterSaved = context.getSharedPreferences("diplay_wm_ex5_cluster", Context.MODE_PRIVATE).edit()
            .putInt("fps", WmEx5ClusterProfile.DEFAULT_FPS).commit()
        if (mainSaved && clusterSaved) {
            profile.edit().putInt("wireless_channel", WmEx5PerformanceProfile.DEFAULT_CHANNEL)
                .putBoolean("phone_microphone_only", false)
                .putBoolean("perf18_defaults_applied", true).commit()
        }
        restoreMicrophoneOnce(profile)
    }

    // Recover perf18's capture experiment on upgrade, without resetting radio, audio or geometry.
    private fun restoreMicrophoneOnce(profile: android.content.SharedPreferences) {
        if (profile.getBoolean("perf19_audio_defaults_applied", false)) return
        profile.edit().putBoolean("phone_microphone_only", false)
            .putBoolean("perf19_audio_defaults_applied", true).commit()
    }
}
