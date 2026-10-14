package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Bounded byte FIFO between the dedicated AirPlay tunnel reader and the iAP2 link worker.
 * A full buffer pauses the reader, propagating pressure to TCP without dropping control bytes.
 * Offers are serialized so a package larger than the buffer can be delivered in ordered portions.
 */
internal class IapTunnelReceiveBuffer(
    capacityBytes: Int = 1_048_576,
    private val maximumStallMillis: Long = 30_000,
) {
    init {
        require(capacityBytes > 0)
        require(maximumStallMillis in 1..300_000)
    }

    private val lock = Object()
    private val offerLock = Object()
    private val bytes = ByteArray(capacityBytes)
    private var head = 0
    private var size = 0
    private var closed = false
    private var closeLocation: String? = null
    private var terminalFailure: Throwable? = null

    fun checkOpen() = synchronized(lock) { checkOpenLocked() }

    fun offer(source: ByteArray) {
        if (source.isEmpty()) return
        synchronized(offerLock) {
            var offset = 0
            synchronized(lock) {
                while (offset < source.size) {
                    checkOpenLocked()
                    val stalledAt = System.nanoTime()
                    while (size == bytes.size) {
                        checkOpenLocked()
                        val remaining = maximumStallMillis * NANOS_PER_MILLISECOND -
                            (System.nanoTime() - stalledAt)
                        if (remaining <= 0) {
                            val failure = IOException(
                                "AirPlay iAP tunnel receive consumer stalled for ${maximumStallMillis}ms " +
                                    "pendingBytes=$size limitBytes=${bytes.size}",
                            )
                            failLocked(failure)
                            throw failure
                        }
                        try {
                            waitLocked(remaining)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            val failure = InterruptedIOException(
                                "AirPlay iAP tunnel receive interrupted while waiting for capacity",
                            ).apply { initCause(interrupted) }
                            failLocked(failure)
                            throw failure
                        }
                    }
                    checkOpenLocked()
                    val count = minOf(source.size - offset, bytes.size - size)
                    val tail = (head + size) % bytes.size
                    val first = minOf(count, bytes.size - tail)
                    source.copyInto(bytes, tail, offset, offset + first)
                    source.copyInto(bytes, 0, offset + first, offset + count)
                    size += count
                    offset += count
                    lock.notifyAll()
                }
            }
        }
    }

    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val budget = timeoutMillis.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLISECOND) *
            NANOS_PER_MILLISECOND
        val started = System.nanoTime()
        synchronized(lock) {
            while (true) {
                throwFailureLocked()
                if (closed) return ByteArray(0)
                if (size > 0) {
                    val count = minOf(maxBytes, size)
                    val result = ByteArray(count)
                    val first = minOf(count, bytes.size - head)
                    bytes.copyInto(result, 0, head, head + first)
                    bytes.copyInto(result, first, 0, count - first)
                    head = (head + count) % bytes.size
                    size -= count
                    lock.notifyAll()
                    return result
                }
                val remaining = budget - (System.nanoTime() - started)
                if (remaining <= 0) return null
                try {
                    waitLocked(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** Cancels pending bytes and wakes both readers and a writer blocked on capacity. */
    fun close(requestedBy: String? = null): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        closed = true
        closeLocation = requestedBy
        size = 0
        lock.notifyAll()
        true
    }

    fun fail(cause: Throwable?): Boolean = synchronized(lock) {
        failLocked(cause ?: IOException("AirPlay iAP tunnel terminated"))
    }

    private fun failLocked(cause: Throwable): Boolean {
        if (closed || terminalFailure != null) return false
        terminalFailure = cause
        size = 0
        lock.notifyAll()
        return true
    }

    private fun checkOpenLocked() {
        throwFailureLocked()
        if (closed) {
            throw IOException(
                "AirPlay iAP tunnel is closed" +
                    (closeLocation?.let { "; requested by $it" } ?: ""),
            )
        }
    }

    private fun throwFailureLocked() {
        val failure = terminalFailure ?: return
        throw IOException(
            "AirPlay iAP tunnel failed: ${failure.javaClass.simpleName}: " +
                (failure.message ?: "unknown error"),
            failure,
        )
    }

    private fun waitLocked(nanos: Long) =
        lock.wait(nanos / NANOS_PER_MILLISECOND, (nanos % NANOS_PER_MILLISECOND).toInt())

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
