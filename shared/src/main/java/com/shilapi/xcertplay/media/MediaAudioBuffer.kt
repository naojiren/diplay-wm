package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video. Stable presets retain sound through radio gaps; 100/150 ms presets trade continuity
 * for a shorter response. Calls, Siri and navigation prompts keep their existing buffer.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    const val LOW_LATENCY_DEFAULT_MILLIS = 150
    val presets = listOf(100, LOW_LATENCY_DEFAULT_MILLIS, DEFAULT_MILLIS, 500, 1000)

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS

    fun isLowLatency(millis: Int): Boolean = sanitize(millis) <= LOW_LATENCY_DEFAULT_MILLIS

    /** Buffered TCP music has its own pacing/anchor; retain its original minimum there. */
    fun playbackMillis(millis: Int, bufferedStream: Boolean): Int =
        if (bufferedStream) maxOf(DEFAULT_MILLIS, sanitize(millis)) else sanitize(millis)

    /** Only short media presets may skip obsolete sound; speech and stable presets retain it. */
    fun queueLimitMillis(isMedia: Boolean, millis: Int): Int? =
        if (isMedia && isLowLatency(millis)) maxOf(200, sanitize(millis) * 2) else null

    fun tailWaitMillis(isMedia: Boolean, millis: Int): Long =
        if (isMedia && isLowLatency(millis)) 50L else 500L

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int): Plan {
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        if (!isMedia) return lowLatency
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        val start = (bytesPerSecond * sanitize(mediaMillis) / 1000).toInt()
        val shortBuffer = isLowLatency(mediaMillis)
        val headroomMillis = if (shortBuffer) 50 else HEADROOM_MILLIS
        val capacity = (bytesPerSecond * (sanitize(mediaMillis) + headroomMillis) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, if (shortBuffer) {
                maxOf(minBufferBytes * 2, MIN_TRACK_BUFFER_BYTES)
            } else lowLatency.trackBufferBytes),
            startBytes = maxOf(start, lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        return minOf(plannedStartBytes, actualCapacityBytes - writeChunkBytes).coerceAtLeast(writeChunkBytes)
    }
}
