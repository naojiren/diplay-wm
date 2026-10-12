package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class WmEx5MediaLatencyDefaultsTest {
    private val base get() = RuntimeEnvironment.getApplication()
    private val wm get() = object : ContextWrapper(base) {
        override fun getPackageName() = WmEx5PerformanceProfile.PACKAGE
    }
    private fun prefs(name: String) = base.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun clear() {
        for (name in listOf("xcertplay_airplay", "diplay_wm_ex5_performance", "diplay_wm_ex5_cluster")) {
            prefs(name).edit().clear().commit()
        }
    }

    @Test fun upgradeFrom1000MillisChangesOnlyMediaBuffer() {
        val main = prefs("xcertplay_airplay")
        main.edit().putInt("media_buffer_ms", 1000).putInt("media_audio_channel", 3)
            .putString("pairing_id", "keep-pairing").putString("wireless_hotspot_mode", "EXISTING_WIFI").commit()
        val profile = prefs("diplay_wm_ex5_performance")
        profile.edit().putBoolean("perf19_audio_defaults_applied", true)
            .putBoolean("phone_microphone_only", false).putInt("wireless_channel", 44).commit()
        WmEx5ClusterProfile.setMargin(wm, "left", 24)
        WmEx5ClusterProfile.setMargin(wm, "bottom", 5)
        val before = main.all.filterKeys { it != "media_buffer_ms" }
        val profileBefore = profile.all.toMap()
        val clusterBefore = prefs("diplay_wm_ex5_cluster").all.toMap()
        WmEx5MediaLatencyDefaults.applyOnce(wm)
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(wm))
        assertEquals(before, main.all.filterKeys { it != "media_buffer_ms" })
        assertEquals(profileBefore, profile.all.filterKeys { it != "perf20_media_latency_defaults_applied" })
        assertEquals(clusterBefore, prefs("diplay_wm_ex5_cluster").all)
    }

    @Test fun laterManualChoicesSurviveApplicationRestarts() {
        WmEx5MediaLatencyDefaults.applyOnce(wm)
        for (millis in listOf(100, 150, 300, 500, 1000)) {
            AirPlayPersistence.saveMediaBufferMillis(wm, millis)
            WmEx5MediaLatencyDefaults.applyOnce(wm)
            assertEquals(millis, AirPlayPersistence.loadMediaBufferMillis(wm))
        }
    }

    @Test fun freshWmDefaultIsShortAndOtherPackagesKeep300Millis() {
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(wm))
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(base))
        AirPlayPersistence.saveMediaBufferMillis(base, 1000)
        val before = prefs("xcertplay_airplay").all.toMap()
        WmEx5MediaLatencyDefaults.applyOnce(base)
        assertEquals(before, prefs("xcertplay_airplay").all)
        assertFalse(prefs("diplay_wm_ex5_performance").contains("perf20_media_latency_defaults_applied"))
    }
}
