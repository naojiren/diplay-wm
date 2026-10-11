package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Exercises live OUT failure recovery without claiming real vendor USB hardware behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE, shadows = [NcmWriteRecoveryTest.Connection::class])
class NcmWriteRecoveryTest {
    @Before fun reset() { Connection.result = -1; Connection.clears = 0; Connection.endpoint = -1; Connection.throwClear = false }
    private fun bridge() = NcmUsbBridge(SharedUsbDeviceConnection.own(Shadow.newInstanceOf(UsbDeviceConnection::class.java)),
        endpoint(0x06), endpoint(0x85), null, emptyList(), true, null, {})
    private fun endpoint(address: Int) = Shadow.newInstanceOf(UsbEndpoint::class.java).also {
        ReflectionHelpers.setField(it, "mAddress", address)
        ReflectionHelpers.setField(it, "mAttributes", 2)
        ReflectionHelpers.setField(it, "mMaxPacketSize", 512)
    }
    private fun live(bridge: NcmUsbBridge) {
        NcmUsbBridge::class.java.getDeclaredMethod("enqueueFrame", ByteArray::class.java)
            .apply { isAccessible = true }.invoke(bridge, byteArrayOf(1,2,3))
    }
    @Test fun startupNakDoesNotDestroyBridgeOrClearHalt() {
        bridge().use { repeat(5) { _ -> it.send(byteArrayOf(1,2,3), 100) }; assertEquals(0, Connection.clears) }
    }
    @Test fun liveThreeFailuresClearOutHaltThenLatchFailureForBothDirections() {
        bridge().use {
            live(it); repeat(2) { _ -> it.send(byteArrayOf(1,2,3), 100) }
            unavailable { it.send(byteArrayOf(1,2,3), 100) }
            assertEquals(3, Connection.clears); assertEquals(0x06, Connection.endpoint)
            unavailable { it.recv(100) }
        }
    }
    @Test fun successfulTransferResetsConsecutiveFailureCounter() {
        bridge().use {
            live(it); repeat(2) { _ -> it.send(byteArrayOf(1,2,3), 100) }
            Connection.result = Int.MAX_VALUE; it.send(byteArrayOf(1,2,3), 100)
            Connection.result = -1; repeat(2) { _ -> it.send(byteArrayOf(1,2,3), 100) }
            assertEquals(4, Connection.clears)
            unavailable { it.send(byteArrayOf(1,2,3), 100) }
        }
    }
    @Test fun partialWriteStopsBothDirections() {
        Connection.result = 1
        bridge().use { unavailable { it.send(byteArrayOf(1,2,3), 100) }; unavailable { it.recv(100) } }
    }
    @Test fun vendorClearHaltExceptionDoesNotSkipFailurePolicy() {
        Connection.throwClear = true
        bridge().use {
            live(it); repeat(2) { _ -> it.send(byteArrayOf(1,2,3), 100) }; unavailable { it.send(byteArrayOf(1,2,3), 100) }
            assertEquals(3, Connection.clears)
        }
    }
    private fun unavailable(block: () -> Unit) { try { block(); fail("Expected unusable NCM session") } catch (_: IphoneUsbException.DeviceUnavailable) {} }

    @Implements(UsbDeviceConnection::class)
    class Connection {
        companion object { var result = -1; var clears = 0; var endpoint = -1; var throwClear = false }
        @Implementation fun bulkTransfer(ep: UsbEndpoint, bytes: ByteArray, offset: Int, length: Int, timeout: Int): Int =
            if (result == Int.MAX_VALUE) length else result
        @Implementation fun controlTransfer(type: Int, request: Int, value: Int, index: Int, buffer: ByteArray?, length: Int, timeout: Int): Int {
            assertEquals(0x02, type); assertEquals(1, request); assertEquals(0, value); assertEquals(0, length)
            clears++; endpoint = index
            if (throwClear) throw IllegalStateException("vendor clear-halt failed")
            return 0
        }
        @Implementation fun close() = Unit
    }
}
