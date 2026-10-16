package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L
    private var emptySinceNs: Long? = null

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    /** API 19–23 have no underrun counter. Observe actual PCM depletion for a bounded interval. */
    fun drainedForRebuffer(enabled: Boolean, playing: Boolean, compressedQueueEmpty: Boolean,
        rawHead: Int, nowNs: Long): Boolean {
        val queued = queuedBytes(rawHead)
        if (!enabled || !playing || writtenBytes == 0L || !compressedQueueEmpty || queued > 0L) {
            emptySinceNs = null
            return false
        }
        val since = emptySinceNs ?: nowNs.also { emptySinceNs = it }
        return nowNs - since >= EMPTY_GRACE_NS
    }

    fun playbackStarted() { emptySinceNs = null }

    private companion object { const val EMPTY_GRACE_NS = 60_000_000L }
}
