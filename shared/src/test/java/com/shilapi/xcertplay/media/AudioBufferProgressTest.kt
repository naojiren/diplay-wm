package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBufferProgressTest {
    @Test fun musicRebuffersAfterConfirmedDepletionWithoutPlatformUnderrunCounter() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.drainedForRebuffer(true, true, true, 999, 0))
        assertFalse(buffer.drainedForRebuffer(true, true, false, 1000, 1))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 10_000_000))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 69_000_000))
        assertTrue(buffer.drainedForRebuffer(true, true, true, 1000, 70_000_000))
        assertFalse(buffer.drainedForRebuffer(true, false, true, 1000, 80_000_000))
        buffer.written(400)
        assertEquals(400, buffer.queuedBytes(1000))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 90_000_000))
    }

    @Test fun doesNotAddRebufferDelayToCallsOrSpeech() {
        val buffer = AudioBufferProgress(2)
        buffer.written(100)
        assertFalse(buffer.drainedForRebuffer(false, true, true, 50, 0))
        assertFalse(buffer.drainedForRebuffer(false, true, true, 50, 1_000_000_000))
    }

    @Test fun anEmptyTrackBeforeFirstPcmDoesNotTriggerRecovery() {
        val buffer = AudioBufferProgress(4)
        assertFalse(buffer.drainedForRebuffer(true, true, true, 0, 0))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 0, 1_000_000_000))
    }

    @Test fun newlyArrivingPacketsCancelTheDepletionGracePeriod() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 0))
        assertFalse(buffer.drainedForRebuffer(true, true, false, 1000, 50_000_000))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 70_000_000))
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 120_000_000))
        assertTrue(buffer.drainedForRebuffer(true, true, true, 1000, 130_000_000))
    }

    @Test fun restartingPlaybackDoesNotImmediatelyReuseTheOldEmptyTimer() {
        val buffer = AudioBufferProgress(4)
        buffer.written(4000)
        buffer.drainedForRebuffer(true, true, true, 1000, 0)
        assertTrue(buffer.drainedForRebuffer(true, true, true, 1000, 60_000_000))
        buffer.playbackStarted()
        assertFalse(buffer.drainedForRebuffer(true, true, true, 1000, 70_000_000))
    }

    @Test fun unsignedPlaybackHeadWrapKeepsQueuedAudio() {
        val buffer = AudioBufferProgress(2)
        repeat(8) { buffer.written(1_073_741_824) }
        buffer.written(40)
        assertEquals(42, buffer.queuedBytes(-1))
        assertEquals(40, buffer.queuedBytes(0))
        assertEquals(0, buffer.queuedBytes(20))
    }
}
