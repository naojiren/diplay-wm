package com.shilapi.xcertplay.transport

import android.content.ContextWrapper
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE, shadows = [LegacyUsbBulkReadTest.Connection::class])
class LegacyUsbBulkReadTest {
    private val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
    private val endpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
        ClassParameter.from(Int::class.javaPrimitiveType, 0x85),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))

    @Test fun onlyLegacyWmPackageUsesNativeReads() {
        val base = RuntimeEnvironment.getApplication()
        val wm = object : ContextWrapper(base) { override fun getPackageName() = "com.shihab.diplay.wmex5" }
        assertTrue(LegacyUsbBulkRead.enabled(wm, 23))
        assertFalse(LegacyUsbBulkRead.enabled(wm, 28))
        assertFalse(LegacyUsbBulkRead.enabled(base, 23))
    }

    @Test fun copiesOnlyReturnedBytesAndCapsEachNativeWait() {
        val buffer = ByteBuffer.allocateDirect(16384)
        val destination = ByteArray(16384) { 0x55 }
        val reply = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 36)
        val result = LegacyUsbBulkRead.readInto(connection, endpoint, buffer, destination, 60000) { fd, ep, bytes, size, timeout ->
            assertEquals(42, fd)
            assertEquals(0x85, ep)
            assertTrue(bytes.isDirect)
            assertEquals(4096, size)
            assertEquals(1000, timeout)
            bytes.put(reply)
            reply.size
        }
        assertEquals(reply.size, result)
        assertArrayEquals(reply, destination.copyOf(reply.size))
        assertEquals(0x55.toByte(), destination[reply.size])
    }

    @Test fun timeoutPreservesTheDestinationAndLaterReadStillWorks() {
        val buffer = ByteBuffer.allocateDirect(4096)
        val destination = ByteArray(4096) { 0x55 }
        assertNull(LegacyUsbBulkRead.readInto(connection, endpoint, buffer, destination, 25) { _, _, _, _, timeout ->
            assertEquals(25, timeout)
            -110
        })
        assertEquals(0x55.toByte(), destination[0])
        assertEquals(1, LegacyUsbBulkRead.readInto(connection, endpoint, buffer, destination, 25) { _, _, bytes, _, _ ->
            bytes.put(7)
            1
        })
        assertEquals(7.toByte(), destination[0])
    }

    @Test(expected = IphoneUsbException.DeviceUnavailable::class)
    fun detachIsAnErrorRatherThanAQuietTimeout() {
        LegacyUsbBulkRead.readInto(connection, endpoint, ByteBuffer.allocateDirect(4096), ByteArray(4096), 25) { _, _, _, _, _ -> -19 }
    }

    @Test(expected = IphoneUsbException.DeviceUnavailable::class)
    fun oversizedCompletionCannotCopyBeyondTheDestination() {
        LegacyUsbBulkRead.readInto(connection, endpoint, ByteBuffer.allocateDirect(4096), ByteArray(20), 25) { _, _, _, size, _ -> size + 1 }
    }

    @Implements(UsbDeviceConnection::class)
    class Connection { @Implementation fun getFileDescriptor() = 42 }
}
