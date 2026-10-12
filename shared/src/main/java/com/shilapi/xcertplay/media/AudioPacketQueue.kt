package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** A short media queue bounds both RTP duration and time spent waiting for the renderer.
 * A null limit preserves the original FIFO/full-queue behavior for speech and stable playback.
 */
internal class AudioPacketQueue<T>(
    private val capacity: Int,
    private val sampleRate: Int,
    private val limitMillis: Int? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private data class Entry<T>(val value: T, val sample: Int, val arrivalNs: Long)
    data class Snapshot(val size: Int, val oldestAgeMs: Long, val spanMs: Long, val evicted: Int)
    private val lock = Object()
    private val entries = ArrayDeque<Entry<T>>()
    private var evicted = 0

    init {
        require(capacity > 0 && sampleRate > 0)
        require(limitMillis == null || limitMillis > 0)
    }

    val size: Int get() = synchronized(lock) { entries.size }
    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    fun offer(value: T, sample: Int): Boolean = synchronized(lock) {
        val now = nanoTime()
        if (limitMillis == null) {
            if (entries.size >= capacity) return@synchronized false
        } else {
            discardExpired(now)
            val maxSamples = sampleRate.toLong() * limitMillis / 1000
            while (entries.isNotEmpty()) {
                val distance = forwardSamples(entries.peekFirst().sample, sample)
                // A duplicate or reordered RTP packet must not evict the entire queue.
                if (distance == null || distance <= maxSamples) break
                entries.removeFirst()
                evicted++
            }
            while (entries.size >= capacity) {
                entries.removeFirst()
                evicted++
            }
        }
        entries.addLast(Entry(value, sample, now))
        lock.notifyAll()
        true
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long): T? = synchronized(lock) {
        require(timeoutMillis >= 0)
        // The injectable clock describes packet age. Use the real monotonic clock for waiting.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (true) {
            discardExpired(nanoTime())
            if (entries.isNotEmpty()) return@synchronized entries.removeFirst().value
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized null
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    fun snapshotAndResetEvictions(): Snapshot = synchronized(lock) {
        val first = entries.peekFirst()
        val last = entries.peekLast()
        val result = Snapshot(entries.size,
            first?.let { ((nanoTime() - it.arrivalNs) / 1_000_000L).coerceAtLeast(0) } ?: 0,
            if (first != null && last != null) {
                (forwardSamples(first.sample, last.sample) ?: 0) * 1000 / sampleRate
            } else 0, evicted)
        evicted = 0
        result
    }

    private fun discardExpired(now: Long) {
        val limit = limitMillis?.toLong()?.times(1_000_000L) ?: return
        while (entries.isNotEmpty() && now - entries.peekFirst().arrivalNs > limit) {
            entries.removeFirst()
            evicted++
        }
    }

    private fun forwardSamples(first: Int, last: Int): Long? {
        val distance = (last.toLong() - first.toLong()) and 0xffff_ffffL
        return distance.takeIf { it <= 0x7fff_ffffL }
    }
}
