package com.shilapi.xcertplay.network

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Releases the legacy station radio for P2P, then restores only the network we suspended. */
@Suppress("DEPRECATION")
internal class LegacyWifiStationLease(
    private val wifi: WifiManager,
    private val diagnostic: (String) -> Unit,
    private val cancelled: () -> Boolean,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var previousNetwork = -1
    private var disabledNetwork = -1
    private var disconnected = false

    fun acquire(timeoutMillis: Long) {
        synchronized(lock) {
            check(!closed && !cancelled()) { "Wi-Fi Direct startup cancelled" }
            val before = wifi.connectionInfo ?: return
            if (!stationBusy(before)) return
            previousNetwork = before.networkId
            // Disconnect alone can immediately auto-join again on old head-unit firmware.
            // Do not remove/save a configuration or disable other saved networks.
            if (previousNetwork >= 0 && runCatching { wifi.disableNetwork(previousNetwork) }.getOrDefault(false)) {
                disabledNetwork = previousNetwork
            }
            disconnected = wifi.disconnect()
            diagnostic("Legacy Wi-Fi Direct station release requested disconnected=$disconnected autoJoinPaused=${disabledNetwork >= 0}")
            if (!disconnected && disabledNetwork < 0) throw IOException("Could not disconnect the existing Wi-Fi station for Wi-Fi Direct")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1))
        while (System.nanoTime() < deadline) {
            if (cancelled() || synchronized(lock) { closed }) throw IOException("Wi-Fi Direct startup cancelled")
            if (!stationBusy(wifi.connectionInfo)) {
                // The framework notification can precede release of the legacy driver resource.
                pause(200)
                if (cancelled() || synchronized(lock) { closed }) throw IOException("Wi-Fi Direct startup cancelled")
                if (!stationBusy(wifi.connectionInfo)) {
                    diagnostic("Legacy Wi-Fi Direct station released; creating group")
                    return
                }
            }
            pause(100)
        }
        throw IOException("Existing Wi-Fi station did not disconnect before Wi-Fi Direct startup")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val released = disconnected || disabledNetwork >= 0
            if (disabledNetwork >= 0) {
                val restored = runCatching { wifi.enableNetwork(disabledNetwork, false) }.getOrDefault(false)
                diagnostic("Legacy Wi-Fi Direct station autoJoin restored=$restored")
                disabledNetwork = -1
            }
            if (previousNetwork >= 0 && released && runCatching { wifi.isWifiEnabled }.getOrDefault(false)) {
                val current = runCatching { wifi.connectionInfo }.getOrNull()
                // A new manual station selection belongs to the user; leave it alone.
                if (!stationBusy(current) && (current == null || current.networkId < 0 || current.networkId == previousNetwork)) {
                    val restored = runCatching { wifi.reconnect() }.getOrDefault(false)
                    diagnostic("Legacy Wi-Fi Direct station reconnect requested=$restored")
                }
            }
        }
    }

    private fun pause(millis: Long) {
        try { Thread.sleep(millis) }
        catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Wi-Fi Direct station release interrupted", interrupted)
        }
    }

    private fun stationBusy(info: WifiInfo?): Boolean = info != null && (
        info.ipAddress != 0 || info.supplicantState in BUSY_STATES)

    private companion object {
        val BUSY_STATES = setOf(
            SupplicantState.COMPLETED, SupplicantState.ASSOCIATED,
            SupplicantState.ASSOCIATING, SupplicantState.AUTHENTICATING,
            SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE,
        )
    }
}
