package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class ExistingWifiSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        shadowOf((app.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo)
            .setSupplicantState(SupplicantState.DISCONNECTED)
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun modeAndCredentialsSurviveSwitchesWithoutOverwritingCarHotspot() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "car-password")
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        for (mode in listOf(WirelessHotspotMode.EXISTING_WIFI, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL)) {
            AirPlayPersistence.saveWirelessHotspotMode(app, mode)
            assertEquals(mode, AirPlayPersistence.loadWirelessHotspotMode(app))
            assertEquals("Pocket", AirPlayPersistence.loadExistingWifiSsid(app))
            assertEquals("pocket-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
            assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
            assertEquals("car-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
        }
        CarHotspotSettings.setEnabled(app, true)
        assertFalse(CarHotspotSettings.shouldEnable(app, true, WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun cancellingSetupPreservesModeAndSavingValidCredentialsSelectsLan() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        fields[0].setText(" Pocket ") // SSID whitespace is meaningful.
        fields[1].setText("short")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
        fields[1].setText("pocket-password")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals(" Pocket ", AirPlayPersistence.loadExistingWifiSsid(app))
    }

    @Test fun hostRuntimeReceivesLanCredentialsAndValidationIsLimitedToWirelessLan() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(app))
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        fun runtime() = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
        assertEquals("Pocket", runtime().existingWifiSsid)
        assertEquals("pocket-password", runtime().existingWifiPassphrase)
        assertEquals(CarPlayTransport.WIRELESS, runtime().transport)
        val permissions = CarPlayHostActivity::class.java.getDeclaredMethod("requiredWirelessPermissions")
            .apply { isAccessible = true }.invoke(host) as List<*>
        assertTrue(permissions.isEmpty()) // Manual SSID input must work without fine location on API 29.
        AirPlayPersistence.saveExistingWifiCredentials(app, "", "")
        AirPlayPersistence.saveWirelessEnabled(app, false)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals(CarPlayTransport.WIRED, runtime().transport)
    }

    @Test @Config(sdk = [23, 29])
    fun connectedNetworkReplacesMistypedNameWithoutReusingItsPassword() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "PCDN_5G", "old-password")
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        shadowOf(wifi.connectionInfo).setSSID("PDCN_5G")
        shadowOf(wifi.connectionInfo).setSupplicantState(SupplicantState.COMPLETED)
        assertEquals("PDCN_5G", ExistingWifiSettings.currentSsid(app))
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        val result = resolve { """
            network={
                ssid="PCDN_5G"
                psk="wrong-network-password"
            }
            network={
                ssid="PDCN_5G"
                psk="router-password"
                key_mgmt=WPA-PSK
            }
        """.trimIndent() }
        assertEquals("PDCN_5G", result!!.ssid)
        assertEquals("router-password", result.passphrase)
        assertFalse(result.toString().contains("router-password"))
        assertEquals("PCDN_5G", AirPlayPersistence.loadExistingWifiSsid(app)) // Only the host commits a live result.
    }

    @Test @Config(sdk = [23])
    fun joinedNetworkUsesMatchingSavedPasswordWithoutAnotherPromptOrAdbRead() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "AC2100", "correct-password")
        val info = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        shadowOf(info).setSSID("AC2100")
        shadowOf(info).setSupplicantState(SupplicantState.COMPLETED)
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals("correct-password", resolve { throw AssertionError("Cached current network needs no ADB read") }!!.passphrase)
    }

    @Test @Config(sdk = [23])
    fun credentialReadThatChangesNetworksIsDiscarded() {
        val info = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        shadowOf(info).setSSID("Network A")
        shadowOf(info).setSupplicantState(SupplicantState.COMPLETED)
        assertNull(resolve {
            shadowOf(info).setSSID("Network B")
            "network={\nssid=\"Network A\"\npsk=\"password-a\"\n}"
        })
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(app))
    }

    @Test fun unreadableOrHashedPasswordsAndWepAreNotTreatedAsOpenNetworks() {
        for (properties in listOf("psk=\"*\"", "psk="+"a".repeat(64), "key_mgmt=NONE\nwep_key0=\"secret\"")) {
            assertNull(ExistingWifiSettings.credentialsFromSupplicant("network={\nssid=\"Router\"\n$properties\n}", "Router"))
        }
        assertEquals("", ExistingWifiSettings.credentialsFromSupplicant(
            "network={\nssid=526f75746572\nkey_mgmt=NONE\n}", "Router")!!.passphrase)
        val escaped = ExistingWifiSettings.credentialsFromSupplicant(
            "network={\nssid=\" Router \"\npsk=\"pass\\\"word\\\\123\"\n}", " Router ")!!
        assertEquals(" Router ", escaped.ssid)
        assertEquals("pass\"word\\123", escaped.passphrase)
    }

    private fun resolve(read: () -> String?): ExistingWifiSettings.Credentials? {
        val result = java.util.concurrent.atomic.AtomicReference<ExistingWifiSettings.Credentials?>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = Thread {
            try { result.set(ExistingWifiSettings.resolveCurrent(app, read)) }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        worker.join(3000)
        assertFalse("Credential read must finish", worker.isAlive)
        failure.get()?.let { throw it }
        return result.get()
    }

    @Test @Config(sdk = [23, 29])
    fun unavailableCurrentNetworkKeepsManualCredentialsAndSsidSpaces() {
        AirPlayPersistence.saveExistingWifiCredentials(app, " Pocket ", "pocket-password")
        assertNull(ExistingWifiSettings.currentSsid(app))
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        assertEquals(" Pocket ", fields[0].text.toString())
        assertEquals("pocket-password", fields[1].text.toString())
        descendants(dialog.window!!.decorView).filterIsInstance<Button>()
            .first { it.text.toString() == activity.getString(R.string.settings_existing_wifi_use_current) }.performClick()
        assertEquals(" Pocket ", fields[0].text.toString())
        assertEquals("pocket-password", fields[1].text.toString())
    }

    private fun controls(): List<View> {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        activity.setContentView(parent)
        return descendants(parent).toList()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
