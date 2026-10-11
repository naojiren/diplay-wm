package com.shilapi.xcertplay.media

/** Diagnostic link between the touch uplink and the next video frame, read by [VideoStats]. */
internal object TouchLatencyProbe {
    @Volatile private var pendingTouchNs = 0L
    @Volatile var maxSendNs = 0L
    private var queueSamples = 0
    private var queueSumNs = 0L
    private var queueMaxNs = 0L
    private var coalescedMoves = 0

    @Synchronized fun onQueueDelay(nanos: Long) {
        queueSamples++; queueSumNs += nanos; queueMaxNs = maxOf(queueMaxNs, nanos)
    }
    @Synchronized fun onMoveCoalesced() { coalescedMoves++ }
    @Synchronized fun queueSummary(): String {
        val average = if (queueSamples == 0) -1 else queueSumNs / queueSamples / 1_000_000
        val result = " touchQueueAvg=${average}ms touchQueueMax=${queueMaxNs / 1_000_000}ms coalescedMoves=$coalescedMoves"
        queueSamples = 0; queueSumNs = 0; queueMaxNs = 0; coalescedMoves = 0
        return result
    }

    fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns touch-to-frame latency for the first frame after a touch, or -1. */
    fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }
}
