package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayIapTunnelStreamTest {
    @Test
    fun maximumEncryptedPackageWaitsForConsumerInsteadOfOverflowingTunnelQueue() {
        val key = ByteArray(32) { it.toByte() }
        val payload = ByteArray(4 * 1024 * 1024 - 32) { (it xor (it ushr 16)).toByte() }
        val packageReady = CountDownLatch(1)
        val producerThread = AtomicReference<Thread>()
        val session = testSession(object : AirPlaySessionListener {
            override fun onDebugLog(message: String) {
                if (message.contains("package type=comm")) {
                    producerThread.set(Thread.currentThread())
                    packageReady.countDown()
                }
            }
        })
        val bridge = AirPlayIapTunnelStream(session, IapTunnel(key), sendCommand = { _, _ -> true })
        val executor = Executors.newSingleThreadExecutor()
        try {
            Socket(InetAddress.getByName("127.0.0.1"), bridge.listen()).use { socket ->
                val sender = executor.submit {
                    val packet = ByteBuffer.allocate(payload.size + 32)
                        .putInt(payload.size + 32).apply { position(16) }
                        .putInt(0x636f6d6d).apply { position(32) }
                        .put(payload).array()
                    val output = socket.getOutputStream()
                    var offset = 0
                    var counter = 0L
                    while (offset < packet.size) {
                        val count = minOf(16_384, packet.size - offset)
                        val header = byteArrayOf(count.toByte(), (count ushr 8).toByte())
                        val encrypted = AirPlayCrypto.chachaSeal(
                            key, AirPlayCrypto.nonce64(counter++),
                            packet.copyOfRange(offset, offset + count), header,
                        )
                        output.write(header)
                        output.write(encrypted)
                        offset += count
                    }
                    output.flush()
                }
                assertTrue("package did not reach tunnel listener", packageReady.await(20, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (producerThread.get().state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertTrue("full queue must pause the socket producer", producerThread.get().state == Thread.State.TIMED_WAITING)
                val received = ByteArrayOutputStream()
                while (received.size() < payload.size) {
                    val chunk = bridge.recv(8_192, 2_000) ?: error("tunnel consumer timed out")
                    assertTrue("unexpected tunnel EOF", chunk.isNotEmpty())
                    received.write(chunk)
                }
                sender.get(2, TimeUnit.SECONDS)
                assertArrayEquals(payload, received.toByteArray())
            }
        } finally {
            bridge.close()
            session.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun inputConnectionCloseKeepsEventChannelAvailableForOutboundIap() {
        val sent = mutableListOf<ByteArray>()
        val session = testSession()
        val bridge = AirPlayIapTunnelStream(
            session = session,
            tunnel = IapTunnel(ByteArray(32)),
            sendCommand = { data, _ ->
                sent += data.copyOf()
                true
            },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("127.0.0.1"), port).use { }

            val payload = byteArrayOf(1, 2, 3)
            bridge.send(payload)

            assertTrue(sent.size == 1)
            assertArrayEquals(payload, sent.single())
        } finally {
            bridge.close()
            session.close()
        }
    }

    @Test
    fun ipv6ListenerAcceptsIpv6Loopback() {
        val bridge = AirPlayIapTunnelStream(
            session = testSession(),
            tunnel = IapTunnel(
                readKey = ByteArray(32),
                bindAddress = InetAddress.getByName("::"),
            ),
            sendCommand = { _, _ -> true },
        )

        try {
            val port = bridge.listen()
            Socket(InetAddress.getByName("::1"), port).use { }
        } finally {
            bridge.close()
        }
    }

    private fun testSession(listener: AirPlaySessionListener = object : AirPlaySessionListener {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = listener,
        media = object : AirPlayMediaHandler {},
    )

}
