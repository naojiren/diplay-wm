package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.os.Looper
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Read the connected station name without joining a network or changing Wi-Fi. */
internal object ExistingWifiSettings {
    class Credentials(val ssid: String, val passphrase: String) {
        override fun toString() = "Existing Wi-Fi credentials (redacted)"
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun currentSsid(context: Context): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wifi?.connectionInfo
        if (info?.supplicantState != SupplicantState.COMPLETED) null
        else info.ssid?.removeSurrounding("\"")?.takeUnless {
            it.isEmpty() || it == WifiManager.UNKNOWN_SSID
        }
    } catch (_: SecurityException) { null }

    /** Uses only the joined network; never changes Wi-Fi or requests ADB authorization. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun resolveCurrent(context: Context, readSavedNetworks: (() -> String?)? = null): Credentials? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Read Wi-Fi credentials off the main thread" }
        val ssid = currentSsid(context) ?: return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val networkId = wifi?.connectionInfo?.networkId
        val configuration = runCatching {
            wifi?.configuredNetworks?.firstOrNull { it.networkId == networkId &&
                decodeSupplicantValue(it.SSID) == ssid }
        }.getOrNull()
        val platform = configuration?.let {
            when {
                it.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) ->
                    decodeSupplicantValue(it.preSharedKey)?.takeIf { key -> key != "*" && valid(ssid, key) }
                it.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                    it.wepKeys.all { key -> key == null } -> ""
                else -> null
            }
        }
        val saved = AirPlayPersistence.loadExistingWifiPassphrase(context)
        val cached = saved.takeIf { AirPlayPersistence.loadExistingWifiSsid(context) == ssid &&
            it.isNotEmpty() && valid(ssid, it) }
        val credentials = when {
            platform != null -> Credentials(ssid, platform)
            cached != null -> Credentials(ssid, cached)
            else -> credentialsFromSupplicant((readSavedNetworks ?: { readLegacySavedNetworks(context) })().orEmpty(), ssid)
        }
        // A network switch during the read must not save another network's password.
        return credentials?.takeIf { currentSsid(context) == ssid && wifi?.connectionInfo?.networkId == networkId }
    }

    private fun valid(ssid: String, key: String) = ManualHotspotValidation.error(ssid, key) == null

    private fun readLegacySavedNetworks(context: Context): String? = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use null
            // Fixed paths only; no SSID or password is interpolated into a shell command.
            adb.shell("for f in /data/misc/wifi/wpa_supplicant.conf /data/vendor/wifi/wpa/wpa_supplicant.conf; do " +
                "if [ -r \"\$f\" ]; then cat \"\$f\"; break; fi; done", 3000)
        }
    }.getOrNull()

    /** Parse data without executing it. Hashed PSKs cannot be used as a Wi-Fi passphrase. */
    internal fun credentialsFromSupplicant(text: String, ssid: String): Credentials? {
        for (block in Regex("(?m)^\\s*network=\\{\\s*$([\\s\\S]*?)^\\s*}\\s*$").findAll(text)) {
            val values = block.groupValues[1].lineSequence().mapNotNull { line ->
                Regex("^\\s*([a-z_][a-z0-9_]*)=(.*)$").find(line)?.let { it.groupValues[1] to it.groupValues[2].trim() }
            }.toMap()
            if (decodeSupplicantValue(values["ssid"]) != ssid) continue
            val key = when {
                values["key_mgmt"] == "NONE" && values.keys.none { it.startsWith("wep_key") } -> ""
                values["psk"]?.startsWith('"') == true -> decodeSupplicantValue(values["psk"])
                else -> null
            } ?: continue
            if (valid(ssid, key)) return Credentials(ssid, key)
        }
        return null
    }

    internal fun decodeSupplicantValue(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith('"')) {
            if (value.length % 2 != 0 || !value.matches(Regex("[0-9a-fA-F]+"))) return null
            return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        }
        if (!value.endsWith('"') || value.length < 2) return null
        val result = StringBuilder()
        var index = 1
        while (index < value.lastIndex) {
            val char = value[index++]
            if (char != '\\') { result.append(char); continue }
            if (index >= value.lastIndex) return null
            when (val escaped = value[index++]) {
                '\\', '"' -> result.append(escaped)
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'e' -> result.append('\u001b')
                else -> return null
            }
        }
        return result.toString()
    }
}
