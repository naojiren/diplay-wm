package com.shilapi.xcertplay.network

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class LegacyWifiStationLeaseTest {
    private val wifi = mock(WifiManager::class.java)
    private fun info(id: Int, state: SupplicantState, ip: Int = 0): WifiInfo = mock(WifiInfo::class.java).also {
        `when`(it.networkId).thenReturn(id)
        `when`(it.supplicantState).thenReturn(state)
        `when`(it.ipAddress).thenReturn(ip)
    }
    private fun connectedThenReleased(): WifiInfo {
        val released = info(-1, SupplicantState.DISCONNECTED)
        val connected = info(13, SupplicantState.COMPLETED, 1)
        `when`(wifi.connectionInfo).thenReturn(connected, released)
        `when`(wifi.isWifiEnabled).thenReturn(true)
        `when`(wifi.disableNetwork(13)).thenReturn(true)
        `when`(wifi.disconnect()).thenReturn(true)
        `when`(wifi.enableNetwork(13, false)).thenReturn(true)
        `when`(wifi.reconnect()).thenReturn(true)
        return released
    }

    @Test fun pausesOnlyCurrentNetworkThenRestoresWithoutReplacingSavedConfigurations() {
        connectedThenReleased()
        val lease = LegacyWifiStationLease(wifi, {}, { false })
        lease.acquire(1000)
        val order = inOrder(wifi)
        order.verify(wifi).disableNetwork(13)
        order.verify(wifi).disconnect()
        verify(wifi, never()).enableNetwork(anyInt(), anyBoolean())
        lease.close()
        lease.close()
        verify(wifi, times(1)).enableNetwork(13, false)
        verify(wifi, times(1)).reconnect()
        verify(wifi, never()).removeNetwork(anyInt())
        verify(wifi, never()).saveConfiguration()
        verify(wifi, never()).setWifiEnabled(false)
    }

    @Test fun closeDoesNotReconnectOverUsersNewStationSelection() {
        connectedThenReleased()
        val lease = LegacyWifiStationLease(wifi, {}, { false })
        lease.acquire(1000)
        val selected = info(22, SupplicantState.ASSOCIATING)
        `when`(wifi.connectionInfo).thenReturn(selected)
        lease.close()
        verify(wifi).enableNetwork(13, false)
        verify(wifi, never()).reconnect()
        verify(wifi, never()).enableNetwork(22, true)
    }

    @Test fun disconnectedStationIsNotChanged() {
        val disconnected = info(-1, SupplicantState.DISCONNECTED)
        `when`(wifi.connectionInfo).thenReturn(disconnected)
        LegacyWifiStationLease(wifi, {}, { false }).use { it.acquire(1000) }
        verify(wifi, never()).disconnect()
        verify(wifi, never()).disableNetwork(anyInt())
        verify(wifi, never()).reconnect()
    }

    @Test fun successfulDisconnectStillWorksWhenDriverRejectsAutojoinPause() {
        connectedThenReleased()
        `when`(wifi.disableNetwork(13)).thenReturn(false)
        LegacyWifiStationLease(wifi, {}, { false }).use { it.acquire(1000) }
        verify(wifi).disconnect()
        verify(wifi, never()).enableNetwork(anyInt(), anyBoolean())
        verify(wifi).reconnect()
    }

    @Test fun ignoredDisconnectCannotProceedAndFailureRestoresAutojoin() {
        connectedThenReleased()
        val connected = info(13, SupplicantState.COMPLETED, 1)
        `when`(wifi.connectionInfo).thenReturn(connected)
        val lease = LegacyWifiStationLease(wifi, {}, { false })
        try {
            lease.acquire(120)
            fail("Expected station release failure")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("did not disconnect"))
        } finally { lease.close() }
        verify(wifi).enableNetwork(13, false)
        verify(wifi, never()).reconnect()
    }

    @Test fun cancellationWhileWaitingRestoresOnlyOurNetwork() {
        connectedThenReleased()
        val cancelled = AtomicBoolean(false)
        `when`(wifi.disconnect()).thenAnswer { cancelled.set(true); true }
        val lease = LegacyWifiStationLease(wifi, {}, cancelled::get)
        try {
            lease.acquire(1000)
            fail("Expected cancellation")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("cancelled"))
        } finally { lease.close() }
        verify(wifi).enableNetwork(13, false)
    }

    @Test fun interruptionPreservesFlagAndCanRestoreNetwork() {
        connectedThenReleased()
        val connected = info(13, SupplicantState.COMPLETED, 1)
        `when`(wifi.connectionInfo).thenReturn(connected)
        val issued = CountDownLatch(1)
        `when`(wifi.disconnect()).thenAnswer { issued.countDown(); true }
        val lease = LegacyWifiStationLease(wifi, {}, { false })
        val interrupted = AtomicBoolean(false)
        val worker = Thread {
            try { lease.acquire(3000) }
            catch (_: IOException) { interrupted.set(Thread.currentThread().isInterrupted) }
            finally { lease.close() }
        }
        worker.start()
        try {
            assertTrue(issued.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(interrupted.get())
            verify(wifi).enableNetwork(13, false)
        } finally { worker.interrupt(); worker.join(3000); lease.close() }
    }
}
