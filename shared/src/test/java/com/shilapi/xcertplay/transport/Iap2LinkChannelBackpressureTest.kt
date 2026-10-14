package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Exercises real pump threads and complete iAP2 wire packets, without Android or socket mocks. */
class Iap2LinkChannelBackpressureTest {
    @Test(timeout = 15_000)
    fun aSlowConsumerRetainsMoreThan64ControlFragmentsInOrder() {
        Fixture().use { fixture ->
            val payloads = List(200) { index -> payload(index, 32) }
            fixture.peer.offerControls(payloads)
            fixture.awaitBackpressure()

            for (expected in payloads) {
                assertArrayEquals(expected, fixture.link.recvControl(3_000))
            }
            assertNull(fixture.link.recvControl(50))
            assertTrue(fixture.link.awaitReady(0))
            assertFalse(fixture.peer.isClosed())
            assertTrue(fixture.diagnostics.any { it.contains("backpressure resumed") })
        }
    }

    @Test(timeout = 15_000)
    fun oneSocketReadContainingMoreThan256TinyWirePacketsDoesNotOverflowEngineEvents() {
        Fixture().use { fixture ->
            // 900 packets x 14 bytes: the first 8192-byte stream read contains 585 packets.
            val payloads = List(900) { index -> payload(index, 4) }
            fixture.peer.offerControls(payloads)
            fixture.awaitBackpressure()

            for (expected in payloads) {
                assertArrayEquals(expected, fixture.link.recvControl(3_000))
            }
            assertTrue(fixture.peer.largestRead <= 8_192)
            assertNull(fixture.link.recvControl(50))
            assertTrue(fixture.link.awaitReady(0))
        }
    }

    @Test(timeout = 15_000)
    fun byteCapacityBackpressuresLargeFragmentsWithoutDroppingOrCorruptingThem() {
        Fixture().use { fixture ->
            // Sixteen maximum-size payloads almost fill the 1 MiB bound; the seventeenth waits.
            val payloads = List(20) { index -> payload(index, Iap2LinkEngine.MAX_PAYLOAD_BYTES) }
            fixture.peer.offerControls(payloads)
            fixture.awaitBackpressure()
            val deliveredAtPause = fixture.peer.deliveredBytes
            assertTrue("Transport must stop reading before accepting the whole burst", deliveredAtPause < 20L * 65_535)

            for (expected in payloads) {
                assertArrayEquals(expected, fixture.link.recvControl(3_000))
            }
            assertNull(fixture.link.recvControl(50))
            assertTrue(fixture.link.awaitReady(0))
            assertFalse(fixture.peer.isClosed())
        }
    }

    @Test(timeout = 10_000)
    fun anOutgoingControlStillProgressesWhileTheReceiveConsumerIsPaused() {
        Fixture().use { fixture ->
            val incoming = List(100) { index -> payload(index, 12) }
            fixture.peer.offerControls(incoming)
            fixture.awaitBackpressure()

            val outgoing = byteArrayOf(0x40, 0x40, 0, 6, 0x43, 1)
            assertTrue(fixture.link.sendControl(outgoing))
            assertTrue(
                "Backpressure must not park the sole worker before it drains outgoing commands",
                fixture.peer.awaitOutboundControl(outgoing, 2_000),
            )
            for (expected in incoming) assertArrayEquals(expected, fixture.link.recvControl(3_000))
            assertTrue(fixture.link.awaitReady(0))
        }
    }

    @Test(timeout = 10_000)
    fun closeWakesAPumpPausedForReceiveCapacity() {
        val fixture = Fixture()
        try {
            fixture.peer.offerControls(List(100) { payload(it, 12) })
            fixture.awaitBackpressure()
            fixture.link.close()
            assertTrue(fixture.peer.isClosed())
            assertNull(fixture.link.recvControl(0))
            assertFalse(fixture.link.sendControl(byteArrayOf(1)))
        } finally {
            fixture.close()
        }
    }

    @Test(timeout = 10_000)
    fun closeAlsoWakesAWaitingControlReceiver() {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val received = AtomicReference<ByteArray?>()
        val reader = thread(name = "iap2-test-waiting-consumer", isDaemon = true) {
            try {
                entered.countDown()
                received.set(fixture.link.recvControl(30_000))
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.link.close()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Closing a healthy channel must be clean", it) }
            assertNull(received.get())
            assertTrue(fixture.peer.isClosed())
        } finally {
            fixture.close()
            reader.join(1_000)
        }
    }

    @Test(timeout = 10_000)
    fun aProtocolTimerFailureTakesPriorityOverAFullReceiveQueue() {
        val fixture = Fixture(peerRetransmissionMillis = 100, peerMaxRetransmissions = 1)
        try {
            fixture.peer.offerControls(List(100) { payload(it, 12) })
            fixture.awaitBackpressure()
            assertTrue(fixture.link.sendControl(byteArrayOf(1, 2, 3)))
            assertTrue("An expired protocol timer must not hide behind a pending control fragment", fixture.peer.closed.await(2, TimeUnit.SECONDS))
            try {
                fixture.link.recvControl(0)
                fail("The caller must observe the original protocol failure")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("was not acknowledged"))
                assertFalse(expected.message.orEmpty().contains("consumer stalled"))
            }
        } finally {
            runCatching { fixture.close() }
        }
    }

    @Test(timeout = 25_000)
    fun aConsumerThatNeverDrainsTimesOutDespiteOngoingOutboundNotifications() {
        val fixture = Fixture()
        val stopSending = CountDownLatch(1)
        val outgoingFailure = AtomicReference<Throwable?>()
        var sender: Thread? = null
        try {
            fixture.peer.offerControls(List(100) { payload(it, 12) })
            fixture.awaitBackpressure()
            sender = thread(name = "iap2-test-outbound-during-stall", isDaemon = true) {
                try {
                    var index = 0
                    do {
                        if (!fixture.link.sendControl(payload(index++, 4))) return@thread
                    } while (!stopSending.await(100, TimeUnit.MILLISECONDS))
                } catch (error: Throwable) {
                    outgoingFailure.set(error)
                }
            }
            // Do not call recvControl here: doing so would be real consumer progress.
            assertTrue("A permanently stuck consumer must not hang forever", fixture.peer.closed.await(20, TimeUnit.SECONDS))
            outgoingFailure.get()?.let { throw AssertionError("Outgoing sender failed", it) }
            assertTrue("The worker must continue flushing outgoing control while receive is paused", fixture.peer.outboundCount() > 10)
            try {
                fixture.link.recvControl(0)
                fail("The caller must observe the consumer-stall failure")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("iAP2 control consumer stalled"))
            }
        } finally {
            stopSending.countDown()
            sender?.join(1_000)
            // close intentionally rethrows a terminal transport failure.
            runCatching { fixture.close() }
        }
    }

    private class Fixture(peerRetransmissionMillis: Int = 0, peerMaxRetransmissions: Int = 0) : AutoCloseable {
        val peer = ScriptedPeer(peerRetransmissionMillis, peerMaxRetransmissions)
        val diagnostics = CopyOnWriteArrayList<String>()
        private val pressured = CountDownLatch(1)
        val link = Iap2LinkChannel.openTunnel(peer, onDiagnostic = { message ->
            diagnostics += message
            if (message.contains("backpressure started")) pressured.countDown()
        })

        init {
            assertTrue("Synthetic peer must finish negotiation", link.awaitReady(3_000))
        }

        fun awaitBackpressure() {
            assertTrue("Expected a bounded receive pause; diagnostics=$diagnostics", pressured.await(3, TimeUnit.SECONDS))
            assertFalse("A temporary full queue must not close the stream", peer.isClosed())
        }

        override fun close() = link.close()
    }

    /** A zero-acknowledgement tunnel peer with a bounded-chunk, ordered byte stream. */
    private class ScriptedPeer(retransmissionMillis: Int, maxRetransmissions: Int) : BlockingDuplexByteStream {
        private val lock = Object()
        private val incoming = ArrayDeque<ByteArray>()
        private var inputOffset = 0
        private var nextSequence = 2
        private val outgoingControls = ArrayList<ByteArray>()
        private var ended = false
        val closed = CountDownLatch(1)
        @Volatile var deliveredBytes = 0L
            private set
        @Volatile var largestRead = 0
            private set

        init {
            val synchronization = Iap2LinkEngine.SynchronizationPayload(
                maxOutgoing = 4,
                maxLength = 65_535,
                retransmissionTimeoutMillis = retransmissionMillis,
                acknowledgementTimeoutMillis = 0,
                maxRetransmissions = maxRetransmissions,
                maxAcknowledgements = 0,
                sessions = listOf(Iap2LinkEngine.SessionDescriptor(10, 0, 2)),
            )
            incoming += packet(0xc0, 1, 99, 0, synchronization.encode())
        }

        fun offerControls(payloads: List<ByteArray>) {
            val bytes = ByteArrayOutputStream()
            synchronized(lock) {
                for (payload in payloads) {
                    bytes.write(packet(0x40, nextSequence, 99, 10, payload))
                    nextSequence = (nextSequence + 1) and 0xff
                }
                incoming += bytes.toByteArray()
                lock.notifyAll()
            }
        }

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (incoming.isEmpty() && !ended) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@synchronized null
                lock.wait((remaining / 1_000_000).coerceAtLeast(1))
            }
            if (ended) return@synchronized ByteArray(0)
            val first = incoming.first
            val size = minOf(maxBytes, first.size - inputOffset)
            val result = first.copyOfRange(inputOffset, inputOffset + size)
            inputOffset += size
            if (inputOffset == first.size) {
                incoming.removeFirst()
                inputOffset = 0
            }
            deliveredBytes += size
            largestRead = maxOf(largestRead, size)
            result
        }

        override fun send(data: ByteArray) = synchronized(lock) {
            if (ended) throw IOException("Synthetic stream closed")
            var offset = 0
            // Engine output is complete packets; skip its six-byte detection marker.
            if (data.size >= 6 && data.copyOfRange(0, 6).contentEquals(Iap2LinkEngine.IAP2_MARKER)) offset = 6
            while (offset + 9 <= data.size) {
                check((data[offset].toInt() and 0xff) == 0xff && data[offset + 1] == 0x5a.toByte())
                val length = ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
                check(length >= 9 && offset + length <= data.size)
                if ((data[offset + 7].toInt() and 0xff) == 10 && length >= 10) {
                    outgoingControls += data.copyOfRange(offset + 9, offset + length - 1)
                }
                offset += length
            }
            assertEquals(data.size, offset)
            lock.notifyAll()
        }

        fun awaitOutboundControl(payload: ByteArray, timeoutMillis: Long): Boolean = synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (outgoingControls.none { it.contentEquals(payload) } && !ended) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@synchronized false
                lock.wait((remaining / 1_000_000).coerceAtLeast(1))
            }
            outgoingControls.any { it.contentEquals(payload) }
        }

        fun isClosed(): Boolean = synchronized(lock) { ended }

        fun outboundCount(): Int = synchronized(lock) { outgoingControls.size }

        override fun close() = synchronized(lock) {
            ended = true
            incoming.clear()
            closed.countDown()
            lock.notifyAll()
        }
    }

    companion object {
        private fun payload(index: Int, size: Int): ByteArray = ByteArray(size) { offset ->
            if (offset < 4) (index ushr (offset * 8)).toByte() else (index + offset).toByte()
        }

        private fun packet(control: Int, sequence: Int, acknowledgement: Int, sessionId: Int, payload: ByteArray): ByteArray {
            val size = 10 + payload.size
            val header = byteArrayOf(
                0xff.toByte(), 0x5a, (size ushr 8).toByte(), size.toByte(), control.toByte(),
                sequence.toByte(), acknowledgement.toByte(), sessionId.toByte(), 0,
            )
            header[8] = checksum(header.copyOf(8))
            return header + payload + checksum(payload)
        }

        private fun checksum(bytes: ByteArray): Byte = (-bytes.sumOf { it.toInt() and 0xff }).toByte()
    }
}
