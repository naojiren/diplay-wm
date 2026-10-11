package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Legacy requestWait may finish with null when close releases a timed-out request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 25], manifest = Config.NONE,
    shadows = [LegacyUsbTimeoutRecoveryTest.Connection::class, LegacyUsbTimeoutRecoveryTest.Request::class])
class LegacyUsbTimeoutRecoveryTest {
    @Before fun reset() { Replay.queue.clear(); Replay.closed = CountDownLatch(1); Replay.waits.set(0); Replay.cancel = false; Replay.unexpectedNull = false }
    @Test fun failedCancelAndLateNullCompletionDoNotPoisonNextRead() { recover() }
    @Test fun successfulCancelWithLateNullCompletionAlsoAllowsNextRead() { Replay.cancel = true; recover() }
    @Test fun ordinaryNullCompletionIsNotSilentlyDiscarded() {
        Replay.unexpectedNull = true
        pipe().use {
            try { it.read(2000); fail("Unexpected null completion must fail the read") }
            catch (error: java.io.IOException) { assertTrue(error.message!!.contains("no completed request")) }
        }
    }
    private fun pipe(): Iap2UsbSession {
        val ep = Shadow.newInstanceOf(UsbEndpoint::class.java).also { ReflectionHelpers.setField(it, "mAddress", 0x85) }
        return Iap2UsbSession(SharedUsbDeviceConnection.own(Shadow.newInstanceOf(UsbDeviceConnection::class.java)),
            Shadow.newInstanceOf(UsbInterface::class.java), ep, ep)
    }
    private fun recover() {
        pipe().use {
            assertNull(it.read(20))
            assertArrayEquals(byteArrayOf(11,22,33), it.read(2000))
        }
        assertEquals(2, Replay.waits.get())
    }
    object Replay {
        val queue = LinkedBlockingQueue<Pair<UsbRequest, ByteBuffer>>()
        var closed = CountDownLatch(1); val waits = AtomicInteger(); var cancel = false; var unexpectedNull = false
    }
    @Implements(UsbRequest::class)
    class Request {
        @RealObject lateinit var real: UsbRequest
        @Implementation fun initialize(connection: UsbDeviceConnection, ep: UsbEndpoint) = true
        @Implementation fun queue(buffer: ByteBuffer, length: Int): Boolean { Replay.queue.put(real to buffer); return true }
        @Implementation fun cancel() = Replay.cancel
        @Implementation fun close() { Replay.closed.countDown() }
    }
    @Implements(UsbDeviceConnection::class)
    class Connection {
        @Implementation fun requestWait(): UsbRequest? {
            val (request, buffer) = Replay.queue.take()
            if (Replay.waits.getAndIncrement() == 0) {
                if (Replay.unexpectedNull) return null
                check(Replay.closed.await(2, TimeUnit.SECONDS))
                return null
            }
            buffer.put(byteArrayOf(11,22,33)); return request
        }
        @Implementation fun releaseInterface(intf: UsbInterface) = true
        @Implementation fun close() = Unit
    }
}
