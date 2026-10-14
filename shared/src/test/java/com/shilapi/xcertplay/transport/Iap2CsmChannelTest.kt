package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2CsmChannelTest {
    @Test
    fun oneLinkPayloadCanDeliverHundredsOfFramesWithoutClosing() {
        val stream = ScriptedStream()
        Iap2CsmChannel.openTunnel(stream).use { channel ->
            assertTrue(channel.awaitReady(1_000))
            val frames = List(512) { Iap2Frame(0x5000 + it, byteArrayOf(it.toByte())) }
            stream.offerControl(encode(frames))

            assertEquals(frames.first(), channel.recv(1_000))
            // All remaining frames are already buffered; a zero timeout must still return them.
            frames.drop(1).forEach { assertEquals(it, channel.recv(0)) }
            assertNull(channel.recv(0))
            assertFalse(channel.isClosed)
        }
    }

    @Test
    fun maximumFrameSplitAcrossLinkPayloadsPreservesFollowingBurst() {
        val stream = ScriptedStream()
        Iap2CsmChannel.openTunnel(stream).use { channel ->
            assertTrue(channel.awaitReady(1_000))
            val large = Iap2Frame(0x5201, ByteArray(Iap2CsmFramer.MAX_BODY_BYTES) { it.toByte() })
            val encoded = large.encodedFrame()
            val following = List(300) { Iap2Frame(0x5001, byteArrayOf(it.toByte(), (it ushr 8).toByte())) }
            stream.offerControl(encoded.copyOfRange(0, Iap2CsmFramer.MAX_LINK_CHUNK_BYTES))
            stream.offerControl(
                encoded.copyOfRange(Iap2CsmFramer.MAX_LINK_CHUNK_BYTES, encoded.size) + encode(following),
            )

            assertEquals(large, channel.recv(1_000))
            following.forEach { assertEquals(it, channel.recv(0)) }
            assertFalse(channel.isClosed)
        }
    }

    @Test
    fun timeoutRetainsIncompleteFrameForTheNextReceive() {
        val stream = ScriptedStream()
        Iap2CsmChannel.openTunnel(stream).use { channel ->
            assertTrue(channel.awaitReady(1_000))
            val frame = Iap2Frame(0x5201, ByteArray(300) { it.toByte() })
            val encoded = frame.encodedFrame()
            stream.offerControl(encoded.copyOfRange(0, 5))
            assertNull(channel.recv(30))
            stream.offerControl(encoded.copyOfRange(5, encoded.size))
            assertEquals(frame, channel.recv(1_000))
        }
    }

    @Test
    fun cleanCloseDiscardsBufferedFramesAndUnblocksWaitingReceive() {
        val stream = ScriptedStream()
        val channel = Iap2CsmChannel.openTunnel(stream)
        assertTrue(channel.awaitReady(1_000))
        stream.offerControl(encode(List(100) { Iap2Frame(0x5001, byteArrayOf(it.toByte())) }))
        assertEquals(Iap2Frame(0x5001, byteArrayOf(0)), channel.recv(1_000))
        channel.close()
        assertNull(channel.recv(0))
        assertTrue(channel.isClosed)

        val waitingStream = ScriptedStream()
        val waitingChannel = Iap2CsmChannel.openTunnel(waitingStream)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(waitingChannel.awaitReady(1_000))
            val started = CountDownLatch(1)
            val receiver = executor.submit<Iap2Frame?> {
                started.countDown()
                waitingChannel.recv(10_000)
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            waitingChannel.close()
            assertNull(receiver.get(1, TimeUnit.SECONDS))
        } finally {
            waitingChannel.close()
            executor.shutdownNow()
        }
    }

    private fun encode(frames: List<Iap2Frame>): ByteArray {
        val encoded = frames.map { it.encodedFrame() }
        val combined = ByteArray(encoded.sumOf { it.size })
        var offset = 0
        encoded.forEach {
            it.copyInto(combined, offset)
            offset += it.size
        }
        return combined
    }

    /** A negotiated zero-ack peer; stream reads split wire packets at the requested byte limit. */
    private class ScriptedStream : BlockingDuplexByteStream {
        private val lock = Object()
        private val incoming = ArrayDeque<ByteArray>()
        private var offset = 0
        private var sequence = 2
        private var closed = false

        init {
            val synchronization = Iap2LinkEngine.SynchronizationPayload(
                maxOutgoing = 4,
                maxLength = 65_535,
                retransmissionTimeoutMillis = 2_000,
                acknowledgementTimeoutMillis = 500,
                maxRetransmissions = 4,
                maxAcknowledgements = 0,
                sessions = listOf(Iap2LinkEngine.SessionDescriptor(Iap2LinkEngine.CONTROL_SESSION_ID, 0, 2)),
            )
            incoming += packet(0xc0, 1, synchronization.encode(), sessionId = 0)
        }

        fun offerControl(bytes: ByteArray) = synchronized(lock) {
            require(bytes.size <= Iap2CsmFramer.MAX_LINK_CHUNK_BYTES)
            incoming += packet(0x40, sequence++ and 0xff, bytes, Iap2LinkEngine.CONTROL_SESSION_ID)
            lock.notifyAll()
        }

        override fun send(data: ByteArray) = Unit

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(lock) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (incoming.isEmpty() && !closed) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return@synchronized null
                lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
            }
            if (closed) return@synchronized byteArrayOf()
            val packet = incoming.first()
            val end = minOf(packet.size, offset + maxBytes)
            val result = packet.copyOfRange(offset, end)
            offset = end
            if (offset == packet.size) {
                incoming.removeFirst()
                offset = 0
            }
            result
        }

        override fun close() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

        private fun packet(control: Int, sequence: Int, payload: ByteArray, sessionId: Int): ByteArray {
            val length = 10 + payload.size
            val header = byteArrayOf(
                0xff.toByte(), 0x5a, (length ushr 8).toByte(), length.toByte(),
                control.toByte(), sequence.toByte(), 99, sessionId.toByte(), 0,
            )
            header[8] = checksum(header.copyOf(8))
            return header + payload + checksum(payload)
        }

        private fun checksum(bytes: ByteArray): Byte = (-bytes.sumOf { it.toInt() and 0xff }).toByte()
    }
}
