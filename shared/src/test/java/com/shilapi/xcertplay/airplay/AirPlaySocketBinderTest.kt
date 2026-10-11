package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class AirPlaySocketBinderTest {
    private val address = InetAddress.getByName("127.0.0.1")

    @Test fun portZeroFailureAndOccupiedFallbackRecoverWithoutLeakingSockets() {
        val created = mutableListOf<FakeSocket>()
        val ports = mutableListOf<Int>()
        var candidate = 51000
        val result = AirPlaySocketBinder.bindEphemeral(
            "AirPlay timing", address,
            { FakeSocket().also(created::add) },
            { _, endpoint ->
                ports += endpoint.port
                if (ports.size <= 2) throw BindException("bind failed: EADDRINUSE (Address already in use)")
            },
            { candidate++ },
        )
        assertEquals(listOf(0, 51000, 51001), ports)
        assertTrue(created.dropLast(1).all { it.closed })
        assertFalse(result.closed)
        result.close()
    }

    @Test fun exhaustedFallbackIsBoundedAndIncludesTheFailingStage() {
        val created = mutableListOf<FakeSocket>()
        val error = assertThrows(IOException::class.java) {
            AirPlaySocketBinder.bindEphemeral(
                "AirPlay control", address,
                { FakeSocket().also(created::add) },
                { _, _ -> throw BindException("EADDRINUSE") },
            )
        }
        assertEquals(17, created.size)
        assertTrue(created.all { it.closed })
        assertTrue(error.message!!.contains("AirPlay control"))
    }

    @Test fun permissionOrAddressErrorsAreNotMisreportedAsPortConflicts() {
        val created = mutableListOf<FakeSocket>()
        val error = assertThrows(IOException::class.java) {
            AirPlaySocketBinder.bindEphemeral(
                "AirPlay audio", address,
                { FakeSocket().also(created::add) },
                { _, _ -> throw BindException("EADDRNOTAVAIL") },
            )
        }
        assertEquals(1, created.size)
        assertTrue(created.single().closed)
        assertTrue(error.message!!.contains("EADDRNOTAVAIL"))
    }

    @Test fun concurrentTcpAndUdpListenersAreUsableAndHaveDistinctPorts() {
        AirPlaySocketBinder.server(address, "AirPlay screen").use { screen ->
            AirPlaySocketBinder.server(address, "AirPlay event").use { event ->
                assertNotEquals(screen.localPort, event.localPort)
                Socket(address, screen.localPort).use { client ->
                    screen.accept().use { accepted ->
                        accepted.soTimeout = 2000
                        client.getOutputStream().write(42)
                        assertEquals(42, accepted.getInputStream().read())
                    }
                }
            }
        }
        AirPlaySocketBinder.datagram(address, "AirPlay timing").use { timing ->
            AirPlaySocketBinder.datagram(address, "AirPlay audio").use { audio ->
                assertNotEquals(timing.localPort, audio.localPort)
                DatagramSocket().use { sender ->
                    sender.send(DatagramPacket(byteArrayOf(42), 1, address, timing.localPort))
                    timing.soTimeout = 2000
                    val packet = DatagramPacket(ByteArray(16), 16)
                    timing.receive(packet)
                    assertEquals(42, packet.data[0].toInt())
                }
            }
        }
    }

    private class FakeSocket : Closeable {
        var closed = false
        override fun close() { closed = true }
    }
}
