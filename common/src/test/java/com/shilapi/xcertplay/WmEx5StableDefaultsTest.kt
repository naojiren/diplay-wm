package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.MediaAudioBuffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class WmEx5StableDefaultsTest {
    private val base get() = RuntimeEnvironment.getApplication()
    private val wm get() = object : ContextWrapper(base) {
        override fun getPackageName() = WmEx5PerformanceProfile.PACKAGE
    }
    private fun prefs(name: String) = base.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun clear() {
        for (name in listOf("xcertplay_airplay", "diplay", "diplay_wm_ex5_performance", "diplay_wm_ex5_cluster")) {
            prefs(name).edit().clear().commit()
        }
        AirPlayPersistence.clearCustomAirPlayIcon(base)
    }

    @Test fun perf20UpgradeRestoresStableAudioAndIdentityWithoutChangingOtherSettings() {
        val main = prefs("xcertplay_airplay")
        main.edit().putInt("media_buffer_ms", 100).putString("model", "DiPlay")
            .putString("oem_label", "BYD").putInt("media_audio_channel", 3)
            .putInt("navigation_audio_channel", 5).putString("pairing_id", "keep-pairing")
            .putString("wireless_hotspot_mode", "EXISTING_WIFI").putInt("display_fps", 24).commit()
        val startup = prefs("diplay")
        startup.edit().putBoolean("auto_connect", true).putString("phone_address", "keep-phone").commit()
        val profile = prefs("diplay_wm_ex5_performance")
        profile.edit().putBoolean("perf20_media_latency_defaults_applied", true)
            .putBoolean("phone_microphone_only", false).putInt("wireless_channel", 44).commit()
        WmEx5ClusterProfile.setMargin(wm, "left", 24)
        WmEx5ClusterProfile.setMargin(wm, "bottom", 5)
        val keys = setOf("media_buffer_ms", "model", "oem_label")
        val before = main.all.filterKeys { it !in keys }
        val profileBefore = profile.all.toMap()
        val clusterBefore = prefs("diplay_wm_ex5_cluster").all.toMap()
        WmEx5StableDefaults.applyOnce(wm)
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(wm))
        assertEquals("EX5", AirPlayPersistence.loadModel(wm))
        assertEquals("Weltmeister", AirPlayPersistence.loadOemLabel(wm))
        assertFalse(DiPlayPreferences.autoConnect(wm))
        assertEquals("keep-phone", DiPlayPreferences.phoneAddress(wm))
        assertEquals(before, main.all.filterKeys { it !in keys })
        assertEquals(profileBefore, profile.all.filterKeys { it != "perf21_stable_defaults_applied" })
        assertEquals(clusterBefore, prefs("diplay_wm_ex5_cluster").all)
    }

    @Test fun saved150MillisAndPreviousCustomIconAreReplacedOnUpgrade() {
        AirPlayPersistence.saveMediaBufferMillis(wm, 150)
        // Represent perf20's raw persisted setting, before its short presets were withdrawn.
        prefs("xcertplay_airplay").edit().putInt("media_buffer_ms", 150).commit()
        AirPlayPersistence.saveCustomAirPlayIcon(wm, byteArrayOf(1, 2, 3))
        WmEx5StableDefaults.applyOnce(wm)
        assertEquals(300, prefs("xcertplay_airplay").getInt("media_buffer_ms", 0))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(wm))
        assertEquals(R.raw.wm_car_home, AirPlayPersistence.defaultAirPlayIconResource(wm))
    }

    @Test fun laterManualChoicesAndCustomIconsSurviveRestarts() {
        WmEx5StableDefaults.applyOnce(wm)
        AirPlayPersistence.saveMediaBufferMillis(wm, 500)
        AirPlayPersistence.saveModel(wm, "Driver model")
        AirPlayPersistence.saveOemLabel(wm, "Driver label")
        DiPlayPreferences.saveAutoConnect(wm, true)
        val icon = byteArrayOf(4, 5, 6)
        AirPlayPersistence.saveCustomAirPlayIcon(wm, icon)
        WmEx5StableDefaults.applyOnce(wm)
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(wm))
        assertEquals("Driver model", AirPlayPersistence.loadModel(wm))
        assertEquals("Driver label", AirPlayPersistence.loadOemLabel(wm))
        assertTrue(DiPlayPreferences.autoConnect(wm))
        assertArrayEquals(icon, AirPlayPersistence.loadCustomAirPlayIconFile(wm)!!.readBytes())
    }

    @Test fun freshWmDefaultsMatchRequestedValuesAndOnlyStableBufferPresetsRemain() {
        assertEquals(300, AirPlayPersistence.loadMediaBufferMillis(wm))
        assertEquals("EX5", AirPlayPersistence.loadModel(wm))
        assertEquals("Weltmeister", AirPlayPersistence.loadOemLabel(wm))
        assertFalse(DiPlayPreferences.autoConnect(wm))
        assertEquals(listOf(300, 500, 1000), MediaAudioBuffer.presets)
        assertEquals(300, MediaAudioBuffer.sanitize(100))
        assertEquals(300, MediaAudioBuffer.sanitize(150))
    }

    @Test fun otherPackagesKeepTheirOwnIdentityStartupAndIcon() {
        prefs("xcertplay_airplay").edit().putInt("media_buffer_ms", 1000)
            .putString("model", "Other car").putString("oem_label", "Other OEM").commit()
        DiPlayPreferences.saveAutoConnect(base, true)
        AirPlayPersistence.saveCustomAirPlayIcon(base, byteArrayOf(8, 9))
        val before = prefs("xcertplay_airplay").all.toMap()
        WmEx5StableDefaults.applyOnce(base)
        assertEquals(before, prefs("xcertplay_airplay").all)
        assertTrue(DiPlayPreferences.autoConnect(base))
        assertNotNull(AirPlayPersistence.loadCustomAirPlayIconFile(base))
        assertEquals(R.raw.ic_car_home, AirPlayPersistence.defaultAirPlayIconResource(base))
        assertFalse(prefs("diplay_wm_ex5_performance").contains("perf21_stable_defaults_applied"))
    }
}
