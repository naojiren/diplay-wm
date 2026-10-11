package com.shilapi.xcertplay.media

/** Bound keyframe recovery without extending its deadline for each newly discarded old frame. */
internal class VideoBacklogRecovery(private val timeoutNanos: Long = 2_000_000_000L) {
    private var startedAt: Long? = null

    fun begin(now: Long): Boolean {
        if (startedAt == null) startedAt = now
        return !expired(now)
    }

    fun expired(now: Long): Boolean = startedAt?.let { now - it >= timeoutNanos } ?: false

    /** An old output still buffered by the hardware must not count as a successful recovery. */
    fun onRendered(queuedAt: Long) {
        startedAt?.let { if (queuedAt >= it) reset() }
    }

    fun reset() { startedAt = null }
}
