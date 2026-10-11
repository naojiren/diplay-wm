package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Random

/** Family-aware ephemeral socket binding for legacy Android head-unit kernels. */
internal object AirPlaySocketBinder {
    fun wildcard(reference: InetAddress?): InetAddress =
        if (reference is Inet4Address) InetAddress.getByName("0.0.0.0")
        else InetAddress.getByName("::")

    fun datagram(reference: InetAddress?, stage: String): DatagramSocket {
        val address = wildcard(reference)
        return bindEphemeral(
            stage, address, { DatagramSocket(null) },
            { socket, endpoint ->
                socket.reuseAddress = false
                socket.bind(endpoint)
            },
        )
    }

    fun server(reference: InetAddress?, stage: String): ServerSocket = serverAt(wildcard(reference), stage)

    fun serverAt(address: InetAddress, stage: String): ServerSocket = bindEphemeral(
        stage, address, { ServerSocket() },
        { socket, endpoint ->
            socket.reuseAddress = false
            socket.bind(endpoint)
        },
    )

    internal fun <T : Closeable> bindEphemeral(
        stage: String,
        address: InetAddress,
        create: () -> T,
        bind: (T, InetSocketAddress) -> Unit,
        nextPort: () -> Int = { 49152 + random.nextInt(16384) },
    ): T {
        var last: IOException? = null
        repeat(EXPLICIT_BIND_ATTEMPTS + 1) { attempt ->
            var socket: T? = null
            try {
                socket = create()
                // Port zero can fail repeatedly on vendor kernels. Still bind atomically:
                // never probe/close a port and assume it remains available for this socket.
                bind(socket, InetSocketAddress(address, if (attempt == 0) 0 else nextPort()))
                return socket
            } catch (error: Exception) {
                runCatching { socket?.close() }
                if (error !is IOException) throw error
                last = error
                if (!isAddressInUse(error)) {
                    throw IOException("$stage bind failed at ${address.hostAddress}: ${error.message}", error)
                }
            }
        }
        throw IOException(
            "$stage could not bind a free port at ${address.hostAddress}: " +
                (last?.message ?: "unknown bind error"),
            last,
        )
    }

    private val random = Random()
    private const val EXPLICIT_BIND_ATTEMPTS = 16
}

internal fun isAddressInUse(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it.message?.contains("EADDRINUSE", ignoreCase = true) == true ||
            it.message?.contains("Address already in use", ignoreCase = true) == true
    }
