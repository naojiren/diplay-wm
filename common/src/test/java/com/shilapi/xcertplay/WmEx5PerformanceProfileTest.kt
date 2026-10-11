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
class WmEx5PerformanceProfileTest {
    private val base get() = RuntimeEnvironment.getApplication()
    private val wm get() = object : ContextWrapper(base) { override fun getPackageName() = WmEx5PerformanceProfile.PACKAGE }
    @Before fun clear() {
        for (name in listOf("xcertplay_airplay", "diplay_wm_ex5_performance")) base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun wmDefaultHasSurfaceViewAndNoSmoothingDelayWithPersistentRollback() {
        AirPlayPersistence.saveSmoothVideo(wm, true)
        assertTrue(WmEx5PerformanceProfile.enabled(wm))
        assertFalse(AirPlayPersistence.loadSmoothVideo(wm))
        assertEquals(CarPlayVideoSurfaceMode.SURFACE, carPlayVideoSurfaceMode(true, false, WmEx5PerformanceProfile.enabled(wm)))
        WmEx5PerformanceProfile.saveEnabled(wm, false)
        assertFalse(WmEx5PerformanceProfile.enabled(wm))
        assertTrue(AirPlayPersistence.loadSmoothVideo(wm))
        WmEx5PerformanceProfile.saveEnabled(wm, true)
        assertTrue(WmEx5PerformanceProfile.enabled(wm))
    }

    @Test fun requiredChannelIsIndependentOfSelectedWirelessModeAndVideoRollback() {
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveWifiP2pPreferredChannel(wm, 149)
        WmEx5PerformanceProfile.saveEnabled(wm, false)
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(wm))
        assertEquals(44, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
        // Original preferences stay available to ordinary builds.
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(base))
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(base))
        assertFalse(WmEx5PerformanceProfile.enabled(base))
    }

    @Test fun channelOnePersistsIndependentlyOfVideoRollbackAndOldGenericPreference() {
        AirPlayPersistence.saveWifiP2pPreferredChannel(wm, 149)
        WmEx5PerformanceProfile.saveChannel(wm, 1)
        assertEquals(1, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
        assertTrue(WmEx5PerformanceProfile.enabled(wm))
        WmEx5PerformanceProfile.saveEnabled(wm, false)
        assertEquals(1, WmEx5PerformanceProfile.channel(wm))
        assertEquals(149, AirPlayPersistence.loadWifiP2pPreferredChannel(base))
        WmEx5PerformanceProfile.saveChannel(wm, 36)
        assertEquals(36, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
    }

    @Test fun invalidOrWrongTypeChannelFallsBackTo44() {
        val prefs = wm.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        prefs.edit().putInt("wireless_channel", 149).commit()
        assertEquals(44, WmEx5PerformanceProfile.channel(wm))
        prefs.edit().putString("wireless_channel", "1").commit()
        assertEquals(44, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
    }

    @Test fun sameWifiPersistsAndDoesNotFallBackToCreatingAGroup() {
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(wm))
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(wm, "test-router", "test-password")
        WmEx5PerformanceProfile.saveChannel(wm, 1)
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(wm))
        assertEquals("test-router", AirPlayPersistence.loadExistingWifiSsid(wm))
        assertEquals(1, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedChannelCannotBeSavedForWm() { WmEx5PerformanceProfile.saveChannel(wm, 149) }

    @Test fun addedChannelsPersistWithoutChangingSameLanCredentials() {
        AirPlayPersistence.saveWirelessHotspotMode(wm, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(wm, "PDCN_5G", "router-password")
        for (channel in listOf(6, 44, 157, 1, 36)) {
            WmEx5PerformanceProfile.saveChannel(wm, channel)
            assertEquals(channel, AirPlayPersistence.loadWifiP2pPreferredChannel(wm))
            assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(wm))
            assertEquals("PDCN_5G", AirPlayPersistence.loadExistingWifiSsid(wm))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun automaticChannelCannotBeSavedForWm() { WmEx5PerformanceProfile.saveChannel(wm, 0) }
}
