package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Android 4.4 - 8 system-generated Wi-Fi Direct group owner for wireless CarPlay. */
@android.annotation.SuppressLint("MissingPermission")
internal class LegacyWifiP2pGroupManager(
    context: Context,
    private val diagnostic: (String) -> Unit = {},
    private val preferredChannel: Int = WifiP2pChannels.AUTO,
    private val frameworkFrequencyReader: (WifiP2pGroup) -> Int? = ::readLegacyGroupFrequency,
) : WirelessHotspotManager {
    init { require(WifiP2pChannels.isValid(preferredChannel)) }
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IllegalStateException("WifiP2pManager is unavailable")

    private var thread: HandlerThread? = null
    private var channel: WifiP2pManager.Channel? = null
    private var created = false
    private var createdSsid: String? = null
    private var restrictionOwned = false
    private val stationLease = (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.let {
        LegacyWifiStationLease(it, diagnostic) { closed }
    }
    private val channelLock = Any()
    @Volatile private var closed = false

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "LegacyWifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0)
        check(!closed) { "Wi-Fi Direct startup cancelled" }
        checkPrerequisites()

        val worker = HandlerThread("xcertplay-wifi-p2p-legacy").apply { start() }
        val p2pChannel = manager.initialize(
            appContext,
            worker.looper,
            WifiP2pManager.ChannelListener {
                diagnostic("Legacy Wi-Fi Direct channel disconnected")
            },
        )
        synchronized(channelLock) {
            if (closed) {
                runCatching { p2pChannel.javaClass.getMethod("close").invoke(p2pChannel) }
                worker.quitSafely()
                throw IOException("Wi-Fi Direct startup cancelled")
            }
            thread = worker
            channel = p2pChannel
        }

        try {
            requestGroupInfo(p2pChannel, 2_000)?.let { group ->
                diagnostic(
                    "Legacy Wi-Fi Direct removing retained group owner=${group.isGroupOwner} " +
                        "name=${group.networkName ?: "unknown"}",
                )
                removeGroupBlocking(p2pChannel, 4_000)
            }

            stationLease?.acquire(minOf(timeoutMillis, 4_000))

            synchronized(channelLock) {
                check(!closed) { "Wi-Fi Direct startup cancelled" }
                if (preferredChannel != WifiP2pChannels.AUTO) {
                    val accepted = try {
                        WifiP2pLegacyChannels.apply(manager, p2pChannel, preferredChannel, diagnostic)
                    } catch (unknown: LegacyChannelOutcomeUnknown) {
                        restrictionOwned = true
                        throw P2pChannelUnavailableException(preferredChannel, unknown.message.orEmpty(), unknown)
                    }
                    if (!accepted) throw P2pChannelUnavailableException(preferredChannel, "The driver rejected the required channel; no automatic fallback.")
                    restrictionOwned = true
                    diagnostic("Legacy Wi-Fi Direct required channel applied channel=$preferredChannel requestedMHz=${WifiP2pChannels.frequencyMhz(preferredChannel)}")
                }
            }

            val createLatch = CountDownLatch(1)
            val createFailure = AtomicReference<IOException?>()
            synchronized(channelLock) {
                check(!closed) { "Wi-Fi Direct startup cancelled" }
                manager.createGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        created = true
                        createLatch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        createFailure.set(
                            IOException("Legacy Wi-Fi Direct createGroup failed code=$reason"),
                        )
                        createLatch.countDown()
                    }
                })
            }
            if (!await(createLatch, minOf(timeoutMillis, 10_000))) {
                throw IOException("Legacy Wi-Fi Direct group creation timed out")
            }
            createFailure.get()?.let { throw it }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            var lastReason = "group information unavailable"
            while (!closed && System.nanoTime() < deadline) {
                val group = requestGroupInfo(p2pChannel, 1_500)
                if (group == null) {
                    lastReason = "group information unavailable"
                    continue
                }
                if (!group.isGroupOwner) {
                    throw IOException("Legacy Wi-Fi Direct became a group client instead of owner")
                }
                createdSsid = group.networkName

                val ssid = group.networkName?.takeIf { it.isNotBlank() }
                val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
                val connectionAddress = requestConnectionInfo(p2pChannel, 1_500)
                val networkInterface = interfaceName?.let {
                    runCatching { NetworkInterface.getByName(it) }.getOrNull()
                }
                val hostAddress = networkInterface?.let { iface ->
                    wirelessHostAddress(
                        addresses = Collections.list(iface.inetAddresses),
                        interfaceIndex = iface.index,
                        preferIpv4 = true,
                    )
                } ?: connectionAddress

                val frameworkFrequency = frameworkFrequencyReader(group)
                val driverFrequency = interfaceName?.let { name ->
                    LegacyHotspotRadio.read(name, if (preferredChannel != 0) WifiP2pLegacyChannels.bandLabelFor(preferredChannel) else null).also { reading ->
                        diagnostic(
                            "Legacy Wi-Fi Direct radio iface=$name " +
                                "frequency=${reading.frequencyMHz ?: "unknown"} " +
                                "error=${reading.error ?: "none"}",
                        )
                    }.frequencyMHz
                }
                val frequency = frameworkFrequency ?: driverFrequency
                if (preferredChannel != 0 && frequency != null && frequency != WifiP2pChannels.frequencyMhz(preferredChannel)) {
                    throw P2pChannelUnavailableException(preferredChannel, "The driver selected ${frequency}MHz instead; no automatic fallback.")
                }
                val channelNumber = frequency?.let(::wifiFrequencyMhzToChannel) ?: preferredChannel

                if (ssid == null || passphrase == null || hostAddress == null) {
                    lastReason =
                        "incomplete group ssid=${ssid != null} passphrase=${passphrase != null} " +
                            "host=${hostAddress != null} iface=${interfaceName ?: "unknown"}"
                    Thread.sleep(150)
                    continue
                }

                val bandLabel = when (frequency) {
                    in 2412..2484 -> "2.4 GHz"
                    in 5160..5895 -> "5 GHz"
                    in 5955..7115 -> "6 GHz"
                    else -> if (preferredChannel != 0) WifiP2pLegacyChannels.bandLabelFor(preferredChannel) else "system selected"
                }
                diagnostic("Legacy Wi-Fi Direct channel requested=$preferredChannel actualMHz=${frequency ?: "unavailable"} verified=${frequency != null}")
                diagnostic(
                    "Legacy Wi-Fi Direct ready iface=${interfaceName ?: "unknown"} " +
                        "band=$bandLabel channel=$channelNumber frequency=${frequency ?: "unknown"}",
                )

                return WirelessHotspotInfo(
                    ssid = ssid,
                    passphrase = passphrase,
                    security = Iap2WirelessSecurity.WPA_WPA2,
                    channel = channelNumber,
                    frequencyMHz = frequency,
                    bssid = networkInterface?.hardwareAddress
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
                        ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                    interfaceName = interfaceName,
                    hostAddress = hostAddress,
                    bandLabel = bandLabel,
                    backend = WirelessHotspotBackend.WIFI_P2P,
                )
            }
            throw IOException("Legacy Wi-Fi Direct timed out: $lastReason")
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    override fun close() {
        closed = true
        synchronized(channelLock) {
            val activeChannel = channel
            // A cancelled startup must still compensate its submitted radio command.
            val interrupted = Thread.interrupted()
            try {
                if (activeChannel != null) {
                    val observed = runCatching { requestGroupInfo(activeChannel, 1_000, requireReply = true) }
                    val current = observed.getOrNull()
                    val ours = observed.isSuccess && (current == null ||
                        (created && createdSsid != null && current.isGroupOwner && current.networkName == createdSsid))
                    if (ours && created && current != null) runCatching { removeGroupBlocking(activeChannel, 2_000) }
                    if (restrictionOwned && ours) {
                        try {
                            val cleared = WifiP2pLegacyChannels.apply(manager, activeChannel, 0, diagnostic)
                            diagnostic("Legacy Wi-Fi Direct channel restriction cleared=$cleared")
                            if (cleared) restrictionOwned = false
                        } catch (failure: Exception) {
                            diagnostic("Legacy Wi-Fi Direct channel cleanup failed type=${failure.javaClass.simpleName}")
                        }
                    } else if (restrictionOwned) {
                        diagnostic("Legacy Wi-Fi Direct channel cleanup skipped=${if (observed.isSuccess) "another_group" else "group_unknown"}")
                    }
                }
                channel = null
                created = false
                activeChannel?.let { ch -> runCatching { ch.javaClass.getMethod("close").invoke(ch) } }
                thread?.quitSafely()
                thread = null
                stationLease?.close()
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private fun checkPrerequisites() {
        if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("Allow precise Location for DiPlay before using Wi-Fi Direct")
        }
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi?.isWifiEnabled == false) {
            throw IOException("Turn on Wi-Fi in the head unit settings before using Wi-Fi Direct")
        }
    }
    private fun requestGroupInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
        requireReply: Boolean = false,
    ): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pGroup?>()
        manager.requestGroupInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) {
            if (requireReply) throw IOException("Wi-Fi Direct group ownership query timed out")
            return null
        }
        return result.get()
    }

    private fun requestConnectionInfo(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ): InetAddress? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<WifiP2pInfo?>()
        manager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutMillis)) return null
        val info = result.get() ?: return null
        if (!info.groupFormed || !info.isGroupOwner) return null
        return info.groupOwnerAddress?.takeUnless(InetAddress::isAnyLocalAddress)
    }
    private fun removeGroupBlocking(
        channel: WifiP2pManager.Channel,
        timeoutMillis: Long,
    ) {
        val latch = CountDownLatch(1)
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = latch.countDown()
            override fun onFailure(reason: Int) {
                diagnostic("Legacy Wi-Fi Direct removeGroup failed code=$reason")
                latch.countDown()
            }
        })
        await(latch, timeoutMillis)
    }

    private fun await(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for legacy Wi-Fi Direct", interrupted)
    }
}

private fun readLegacyGroupFrequency(group: WifiP2pGroup): Int? = runCatching {
    (group.javaClass.getMethod("getFrequency").invoke(group) as? Number)
        ?.toInt()?.takeIf { it > 0 }
}.getOrNull()
