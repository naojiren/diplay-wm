package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException

/**
 * Full-duplex adapter for the CarPlay iAP2 DataStream.
 *
 * Inbound iAP2 bytes come from [IapTunnel]; outbound bytes are sent over the encrypted AirPlay
 * event channel using the `iAPSendMessage` command.
 */
internal class AirPlayIapTunnelStream(
    private val session: AirPlaySession,
    private val tunnel: IapTunnel,
    private val sendCommand: (ByteArray, Long) -> Boolean = { data, timeoutMillis ->
        session.sendIapMessage(data, timeoutMillis)
    },
) : BlockingDuplexByteStream {
    private val inbound = IapTunnelReceiveBuffer()

    fun listen(): Int = tunnel.listen(
        object : IapTunnel.Listener {
            override fun onOpen(remoteAddress: String?) {
                session.logTrace(
                    "AirPlay iAP tunnel connected remote=${remoteAddress ?: "unknown"}",
                )
            }

            override fun onIap(bytes: ByteArray) = offer(bytes)

            override fun onDebug(message: String) {
                session.logTrace(message)
            }

            override fun onClosed(cause: Throwable?) {
                session.logTrace(
                    "AirPlay iAP tunnel closed cause=" +
                        "${cause?.javaClass?.simpleName ?: "peer EOF"}: " +
                        "${cause?.message ?: "no detail"}",
                )
                val shouldCloseSession = inbound.fail(cause)
                if (shouldCloseSession) session.close()
            }
        },
    )

    override fun send(data: ByteArray) {
        inbound.checkOpen()
        if (!tunnel.awaitPeerConnection(PEER_CONNECT_TIMEOUT_MILLIS)) {
            throw IOException("CarPlay iAP tunnel peer did not connect")
        }
        inbound.checkOpen()
        if (!sendCommand(data, EVENT_READY_TIMEOUT_MILLIS)) {
            throw IOException("AirPlay event channel rejected an iAP message")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = inbound.recv(maxBytes, timeoutMillis)

    override fun close() {
        if (!inbound.close(closeCaller())) return
        tunnel.close()
    }

    private fun offer(bytes: ByteArray) {
        try {
            inbound.offer(bytes)
        } catch (failure: IOException) {
            // A timed-out or interrupted writer has a terminal failure visible to the link reader.
            // Stop the socket producer as well, rather than accepting more packages after it.
            tunnel.close()
            throw failure
        }
    }

    private fun closeCaller(): String =
        Thread.currentThread().stackTrace
            .drop(2)
            .take(8)
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }

    private companion object {
        const val PEER_CONNECT_TIMEOUT_MILLIS = 15_000L
        const val EVENT_READY_TIMEOUT_MILLIS = 10_000L
    }
}
