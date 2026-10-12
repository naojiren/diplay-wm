package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class WmEx5OptimizedDefaultsTest {
    private val base get() = RuntimeEnvironment.getApplication()
    private val wm get() = object : ContextWrapper(base) {
        override fun getPackageName() = WmEx5PerformanceProfile.PACKAGE
    }
    private fun prefs(name: String) = base.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun clear() {
        for (name in listOf("xcertplay_airplay", "diplay_wm_ex5_performance", "diplay_wm_ex5_cluster")) {
            prefs(name).edit().clear().commit()
        }
        // Match a perf17 upgrade; its historical migrations have already completed.
        val editor = prefs("diplay_wm_ex5_performance").edit()
        for (key in listOf("perf3_defaults_applied", "perf4_defaults_applied", "perf5_cluster_defaults_applied",
            "perf6_manufacturer_applied", "perf11_layout_defaults_applied")) editor.putBoolean(key, true)
        editor.commit()
    }

    @Test fun upgradeAppliesNewDefaultsAndPreservesPlaybackPairingCredentialsAndGeometry() {
        prefs("xcertplay_airplay").edit().putInt("display_fps", 4).commit()
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(wm, "AC2100", "test-password")
        AirPlayPersistence.saveMediaAudioChannel(wm, 3)
        AirPlayPersistence.saveNavigationAudioChannel(wm, 5)
        AirPlayPersistence.saveMediaBufferMillis(wm, 120)
        AirPlayPersistence.saveDisplayScalePercent(wm, 50)
        prefs("xcertplay_airplay").edit().putString("pairing_id", "keep-pairing").commit()
        WmEx5PerformanceProfile.saveChannel(wm, 6)
        WmEx5PerformanceProfile.saveEnabled(wm, false)
        prefs("diplay_wm_ex5_cluster").edit().putInt("fps", 4).commit()
        WmEx5ClusterProfile.setMargin(wm, "left", 24)
        WmEx5ClusterProfile.setMargin(wm, "bottom", 5)
        val mainBefore = prefs("xcertplay_airplay").all.toMap()
        val clusterBefore = prefs("diplay_wm_ex5_cluster").all.toMap()
        WmEx5OptimizedDefaults.applyOnce(wm)
        assertEquals(18, AirPlayPersistence.loadFps(wm))
        assertEquals(18, WmEx5ClusterProfile.streamConfig(wm, 1920, 480).fps)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(wm))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(wm))
        assertEquals(44, WmEx5PerformanceProfile.channel(wm))
        assertFalse(WmEx5PerformanceProfile.phoneMicrophoneOnly(wm))
        assertFalse(WmEx5PerformanceProfile.enabled(wm))
        assertEquals(mainBefore.filterKeys { it !in setOf("display_fps", "wireless_enabled", "wireless_hotspot_mode") },
            prefs("xcertplay_airplay").all.filterKeys { it !in setOf("display_fps", "wireless_enabled", "wireless_hotspot_mode") })
        assertEquals(clusterBefore.filterKeys { it != "fps" }, prefs("diplay_wm_ex5_cluster").all.filterKeys { it != "fps" })
    }

    @Test fun defaultsAreAppliedOnceAndLaterManualChoicesSurvive() {
        WmEx5OptimizedDefaults.applyOnce(wm)
        AirPlayPersistence.saveFps(wm, 24)
        WmEx5ClusterProfile.setFps(wm, 12)
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.EXISTING_WIFI)
        WmEx5PerformanceProfile.saveChannel(wm, 1)
        WmEx5PerformanceProfile.savePhoneMicrophoneOnly(wm, false)
        WmEx5OptimizedDefaults.applyOnce(wm)
        assertEquals(24, AirPlayPersistence.loadFps(wm))
        assertEquals(12, WmEx5ClusterProfile.fps(wm))
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(wm))
        assertEquals(1, WmEx5PerformanceProfile.channel(wm))
        assertFalse(WmEx5PerformanceProfile.phoneMicrophoneOnly(wm))
    }

    @Test fun ordinaryPackagesKeepTheirSavedSettingsAndMicrophone() {
        AirPlayPersistence.saveFps(base, 8)
        AirPlayPersistence.saveWirelessHotspotMode(base, WirelessHotspotMode.EXISTING_WIFI)
        val before = prefs("xcertplay_airplay").all.toMap()
        WmEx5OptimizedDefaults.applyOnce(base)
        assertEquals(before, prefs("xcertplay_airplay").all)
        assertFalse(WmEx5PerformanceProfile.phoneMicrophoneOnly(base))
    }

    @Test fun perf18UpgradeRestoresCaptureWithoutResettingAnyOtherSetting() {
        val profile = prefs("diplay_wm_ex5_performance")
        profile.edit().putBoolean("perf18_defaults_applied", true)
            .putBoolean("phone_microphone_only", true).putInt("wireless_channel", 6).commit()
        AirPlayPersistence.saveFps(wm, 24)
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveMediaAudioChannel(wm, 3)
        WmEx5ClusterProfile.setFps(wm, 24)
        WmEx5ClusterProfile.setMargin(wm, "left", 24)
        WmEx5ClusterProfile.setMargin(wm, "bottom", 5)
        val mainBefore = prefs("xcertplay_airplay").all.toMap()
        val clusterBefore = prefs("diplay_wm_ex5_cluster").all.toMap()
        val profileBefore = profile.all.filterKeys { it != "phone_microphone_only" }
        WmEx5OptimizedDefaults.applyOnce(wm)
        assertFalse(WmEx5PerformanceProfile.phoneMicrophoneOnly(wm))
        assertEquals(mainBefore, prefs("xcertplay_airplay").all)
        assertEquals(clusterBefore, prefs("diplay_wm_ex5_cluster").all)
        assertEquals(profileBefore, profile.all.filterKeys {
            it !in setOf("phone_microphone_only", "perf19_audio_defaults_applied")
        })
        WmEx5PerformanceProfile.savePhoneMicrophoneOnly(wm, true)
        WmEx5OptimizedDefaults.applyOnce(wm)
        assertTrue("later explicit driver choice survives", WmEx5PerformanceProfile.phoneMicrophoneOnly(wm))
    }

    @Test fun freshInstallCompletesHistoricalMigrationsBeforeApplyingNewDefaults() {
        prefs("diplay_wm_ex5_performance").edit().clear().commit()
        WmEx5OptimizedDefaults.applyOnce(wm)
        assertEquals(18, AirPlayPersistence.loadFps(wm))
        assertEquals(18, WmEx5ClusterProfile.fps(wm))
        assertEquals(44, WmEx5PerformanceProfile.channel(wm))
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(wm))
        assertEquals(50, AirPlayPersistence.loadDisplayScalePercent(wm))
        assertEquals(100, WmEx5ClusterProfile.resolutionPercent(wm))
        assertTrue(WmEx5ClusterProfile.enabled(wm))
        AirPlayPersistence.saveFps(wm, 24)
        WmEx5ClusterProfile.setFps(wm, 24)
        WmEx5PerformanceProfile.saveChannel(wm, 1)
        WmEx5Startup.applyDefaults(wm)
        assertEquals(24, AirPlayPersistence.loadFps(wm))
        assertEquals(24, WmEx5ClusterProfile.fps(wm))
        assertEquals(1, WmEx5PerformanceProfile.channel(wm))
    }

    @Test fun bothWmRatePickersExcludeLowRatesAndAcceptTwentyFour() {
        assertTrue(24 in WmEx5PerformanceProfile.frameRates)
        assertTrue(24 in WmEx5ClusterProfile.fpsChoices)
        assertTrue(WmEx5PerformanceProfile.frameRates.all { it >= 12 })
        assertTrue(WmEx5ClusterProfile.fpsChoices.all { it >= 12 })
        for (value in listOf(4, 8)) {
            prefs("xcertplay_airplay").edit().putInt("display_fps", value).commit()
            prefs("diplay_wm_ex5_cluster").edit().putInt("fps", value).commit()
            assertEquals(18, AirPlayPersistence.loadFps(wm))
            assertEquals(18, WmEx5ClusterProfile.fps(wm))
        }
        for (value in listOf(12, 18, 24, 30)) {
            AirPlayPersistence.saveFps(wm, value)
            WmEx5ClusterProfile.setFps(wm, value)
            assertEquals(value, AirPlayPersistence.loadFps(wm))
            assertEquals(value, WmEx5ClusterProfile.streamConfig(wm, 1920, 480).fps)
        }
    }
}
