package com.shilapi.xcertplay.media

/** Keep the current compressed frame while the codec's output is applying backpressure. */
internal class AudioDecoderInputRetry(
    private val dequeue: (Long) -> Int,
    private val drainOutput: () -> Unit,
    private val running: () -> Boolean,
    private val nowNs: () -> Long = System::nanoTime,
) {
    var retries = 0L
        private set
    var timeouts = 0L
        private set

    /** null is shutdown; -1 is a persistently stalled codec, never a single 10 ms busy result. */
    fun acquire(): Int? {
        val start = nowNs()
        while (running()) {
            val index = dequeue(INPUT_WAIT_US)
            if (index >= 0) return index
            retries++
            // Draining releases output buffers and can unblock the decoder's input pool.
            drainOutput()
            if (!running()) return null
            val afterDrain = dequeue(0)
            if (afterDrain >= 0) return afterDrain
            if (nowNs() - start >= MAX_WAIT_NS) {
                timeouts++
                return -1
            }
        }
        return null
    }

    private companion object {
        const val INPUT_WAIT_US = 10_000L
        const val MAX_WAIT_NS = 1_000_000_000L
    }
}
