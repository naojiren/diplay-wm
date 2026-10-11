package com.shilapi.xcertplay.network

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager

/** Exercises the actual API 23 startup path, rather than the separate Android 9 manager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE, shadows = [LegacyWifiP2pRequiredChannelTest.Radio::class])
class LegacyWifiP2pRequiredChannelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager) as Radio
    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        (context.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = true
    }

    @Test fun required36IsAppliedBeforeCreatingGroupAndUnknownActualFrequencyStaysUnknown() {
        val logs = mutableListOf<String>()
        LegacyWifiP2pGroupManager(context, logs::add, 36).use {
            val info = background { it.start(3000) }
            assertEquals(listOf("channel:36", "create"), radio.events)
            assertEquals(36, info.channel)
            assertEquals("5 GHz", info.bandLabel)
            assertNull(info.frequencyMHz)
            assertTrue(logs.any { line -> line.contains("requested=36 actualMHz=unavailable verified=false") })
        }
        assertEquals(listOf("channel:36", "create", "remove", "channel:0"), radio.events)
    }

    @Test fun rejectedRequiredChannelCannotCreateAnAutomaticGroup() {
        radio.reject = true
        LegacyWifiP2pGroupManager(context, preferredChannel = 36).use {
            val error = failure { it.start(3000) }
            assertTrue(error is P2pChannelUnavailableException)
            assertTrue(error.message!!.contains("channel 36"))
        }
        assertEquals(listOf("channel:36"), radio.events)
    }

    @Test fun actualDifferentRadioChannelStopsStartupAndRemovesOurGroup() {
        LegacyWifiP2pGroupManager(context, preferredChannel = 36, frameworkFrequencyReader = { 2412 }).use {
            assertTrue(failure { it.start(3000) } is P2pChannelUnavailableException)
        }
        assertEquals(listOf("channel:36", "create", "remove", "channel:0"), radio.events)
    }

    @Test fun actual5180MhzIsReportedAsVerifiedChannel36() {
        val logs = mutableListOf<String>()
        LegacyWifiP2pGroupManager(context, logs::add, 36, frameworkFrequencyReader = { 5180 }).use {
            val info = background { it.start(3000) }
            assertEquals(5180, info.frequencyMHz)
            assertEquals(36, info.channel)
            assertTrue(logs.any { line -> line.contains("actualMHz=5180 verified=true") })
        }
    }

    @Test fun requiredChannelOneReportsVerified2412MhzAndIsAppliedBeforeGroupCreation() {
        val logs = mutableListOf<String>()
        LegacyWifiP2pGroupManager(context, logs::add, 1, frameworkFrequencyReader = { 2412 }).use {
            val info = background { it.start(3000) }
            assertEquals(listOf("channel:1", "create"), radio.events)
            assertEquals(1, info.channel)
            assertEquals("2.4 GHz", info.bandLabel)
            assertEquals(2412, info.frequencyMHz)
            assertTrue(logs.any { line -> line.contains("actualMHz=2412 verified=true") })
        }
        assertEquals(listOf("channel:1", "create", "remove", "channel:0"), radio.events)
    }

    @Test fun requiredChannelOneCannotSilentlyUseFiveGhzOrAutomatic() {
        LegacyWifiP2pGroupManager(context, preferredChannel = 1, frameworkFrequencyReader = { 5180 }).use {
            assertTrue(failure { it.start(3000) } is P2pChannelUnavailableException)
        }
        assertEquals(listOf("channel:1", "create", "remove", "channel:0"), radio.events)
    }

    @Test fun rejectedChannelOneCannotCreateAGroup() {
        radio.reject = true
        LegacyWifiP2pGroupManager(context, preferredChannel = 1).use {
            assertTrue(failure { it.start(3000) } is P2pChannelUnavailableException)
        }
        assertEquals(listOf("channel:1"), radio.events)
    }

    @Test fun unansweredSelectionIsCompensatedAndLateReplyCannotCreateAGroup() {
        radio.defer = true
        LegacyWifiP2pGroupManager(context, preferredChannel = 36).use {
            assertTrue(failure { it.start(3000) }.message!!.contains("did not respond"))
            radio.deferred!!.onSuccess()
        }
        assertEquals(listOf("channel:36", "channel:0"), radio.events)
    }

    @Test fun invokedSetterFailureAfterChangingRadioIsCompensated() {
        radio.throwAfterSet = true
        LegacyWifiP2pGroupManager(context, preferredChannel = 36).use {
            assertTrue(failure { it.start(3000) }.message!!.contains("unknown outcome"))
        }
        assertEquals(listOf("channel:36", "channel:0"), radio.events)
    }

    @Test fun cancelledSelectionPreservesInterruptAndStillClearsTheChannel() {
        radio.defer = true
        val manager = LegacyWifiP2pGroupManager(context, preferredChannel = 36)
        val interrupted = AtomicBoolean(false)
        val starting = Thread {
            try { manager.start(3000); fail("Expected interrupted startup") }
            catch (_: java.io.IOException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        try {
            starting.start()
            assertTrue(radio.selectionIssued.await(2, TimeUnit.SECONDS))
            starting.interrupt()
            starting.join(5000)
            assertFalse(starting.isAlive)
            assertTrue(interrupted.get())
            assertEquals(listOf("channel:36", "channel:0"), radio.events)
        } finally { manager.close() }
    }

    @Test fun closingLeavesAnotherApplicationsGroupAndChannelUntouched() {
        val manager = LegacyWifiP2pGroupManager(context, preferredChannel = 36)
        background { manager.start(3000) }
        val other = radio.makeGroup("DIRECT-other")
        radio.group = other
        manager.close()
        assertSame(other, radio.group)
        assertEquals(listOf("channel:36", "create"), radio.events)
    }

    @Test fun automaticPolicyStillWorksForOtherApplicationsWithoutHiddenApiCall() {
        LegacyWifiP2pGroupManager(context).use {
            val info = background { it.start(3000) }
            assertEquals(0, info.channel)
            assertNull(info.frequencyMHz)
        }
        assertEquals(listOf("create", "remove"), radio.events)
    }

    @Test fun addedFixedChannelsAreAppliedAndVerifiedOnApi23() {
        for ((channel, frequency) in listOf(6 to 2437, 44 to 5220, 157 to 5785)) {
            radio.events.clear()
            LegacyWifiP2pGroupManager(context, preferredChannel = channel, frameworkFrequencyReader = { frequency }).use {
                val info = background { it.start(3000) }
                assertEquals(listOf("channel:$channel", "create"), radio.events)
                assertEquals(channel, info.channel)
                assertEquals(frequency, info.frequencyMHz)
                assertEquals(if (channel == 6) "2.4 GHz" else "5 GHz", info.bandLabel)
            }
            assertEquals(listOf("channel:$channel", "create", "remove", "channel:0"), radio.events)
        }
    }

    @Test fun addedFixedChannelsRejectVerifiedWrongFrequency() {
        for (channel in listOf(6, 44, 157)) {
            radio.events.clear()
            LegacyWifiP2pGroupManager(context, preferredChannel = channel, frameworkFrequencyReader = { 5180 }).use {
                assertTrue(failure { it.start(3000) } is P2pChannelUnavailableException)
            }
            assertEquals(listOf("channel:$channel", "create", "remove", "channel:0"), radio.events)
        }
    }

    private fun <T> background(block: () -> T): T {
        val worker = Executors.newSingleThreadExecutor()
        return try { worker.submit<T> { block() }.get(12, TimeUnit.SECONDS) }
        finally { worker.shutdownNow() }
    }
    private fun failure(block: () -> Any): Throwable {
        try { background(block); fail("Expected startup failure") }
        catch (error: ExecutionException) { return error.cause!! }
        error("unreachable")
    }

    @Implements(WifiP2pManager::class)
    class Radio : ShadowWifiP2pManager() {
        val events = mutableListOf<String>()
        var reject = false
        var defer = false
        var throwAfterSet = false
        var group: WifiP2pGroup? = null
        @Volatile var deferred: WifiP2pManager.ActionListener? = null
        val selectionIssued = CountDownLatch(1)
        @Implementation override fun setWifiP2pChannels(channel: WifiP2pManager.Channel, listenChannel: Int, operatingChannel: Int, listener: WifiP2pManager.ActionListener) {
            events += "channel:$operatingChannel"
            if (reject) { listener.onFailure(WifiP2pManager.ERROR); return }
            if (throwAfterSet && operatingChannel != 0) error("vendor failed after changing channel")
            if (defer && operatingChannel != 0) { deferred = listener; selectionIssued.countDown() }
            else listener.onSuccess()
        }
        @Implementation override fun createGroup(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
            events += "create"; group = makeGroup(); listener.onSuccess()
        }
        @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.GroupInfoListener) { listener.onGroupInfoAvailable(group) }
        @Implementation override fun requestConnectionInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ConnectionInfoListener) {
            listener.onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true; isGroupOwner = true; groupOwnerAddress = InetAddress.getByName("192.168.49.1")
            })
        }
        @Implementation override fun removeGroup(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
            events += "remove"; group = null; listener.onSuccess()
        }
        fun makeGroup(name: String = "DIRECT-ex5-test") = WifiP2pGroup().apply {
            shadowOf(this).setIsGroupOwner(true)
            shadowOf(this).setNetworkName(name)
            shadowOf(this).setPassphrase("test-passphrase")
            shadowOf(this).setInterface("p2p0")
        }
    }
}
