package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Only adjacent unsent MOVE entries from the same session may replace one another. */
internal class TouchDispatchQueue<T>(
    private val executor: Executor,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onCoalesced: () -> Unit = {},
    private val send: (T, Long) -> Unit,
) {
    private data class Entry<T>(val value: T, val key: Any, val move: Boolean, val queuedNs: Long)
    private val entries = ArrayDeque<Entry<T>>()
    private val lock = Any()
    private var scheduled = false

    fun offer(value: T, session: Any, move: Boolean): Boolean = synchronized(lock) {
        if (move && entries.peekLast()?.let { it.move && it.key === session } == true) {
            entries.removeLast()
            onCoalesced()
        }
        entries.addLast(Entry(value, session, move, nanoTime()))
        if (!scheduled) {
            scheduled = true
            try { executor.execute(::drain) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                entries.clear(); scheduled = false; return false
            }
        }
        true
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                entries.pollFirst() ?: run { scheduled = false; return }
            }
            // A failed packet must not leave the queue permanently scheduled.
            runCatching { send(next.value, (nanoTime() - next.queuedNs).coerceAtLeast(0)) }
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
