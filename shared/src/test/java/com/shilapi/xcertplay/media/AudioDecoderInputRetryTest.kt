package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioDecoderInputRetryTest {
    @Test fun temporaryBackpressureDrainsOutputBeforeRetryingTheSameInput() {
        var outputHeld = true
        val actions = mutableListOf<String>()
        val retry = AudioDecoderInputRetry(
            dequeue = { wait -> actions += "input:$wait"; if (outputHeld) -1 else 3 },
            drainOutput = { actions += "drain"; outputHeld = false },
            running = { true },
            nowNs = { 0 },
        )
        assertEquals(3, retry.acquire())
        assertEquals(listOf("input:10000", "drain", "input:0"), actions)
        assertEquals(1, retry.retries)
        assertEquals(0, retry.timeouts)
    }

    @Test fun burstsCanRecoverAfterManyTenMillisecondBusyResults() {
        var clock = 0L
        var drains = 0
        val retry = AudioDecoderInputRetry(
            dequeue = { wait -> clock += wait * 1000; if (clock >= 250_000_000) 7 else -1 },
            drainOutput = { drains++ }, running = { true }, nowNs = { clock },
        )
        assertEquals(7, retry.acquire())
        assertEquals(24, drains)
        assertEquals(0, retry.timeouts)
    }

    @Test fun persistentCodecStallIsBoundedAndFollowingFrameCanStillProceed() {
        var clock = 0L
        var available = false
        val retry = AudioDecoderInputRetry(
            dequeue = { wait -> clock += wait * 1000; if (available) 2 else -1 },
            drainOutput = {}, running = { true }, nowNs = { clock },
        )
        assertEquals(-1, retry.acquire())
        assertEquals(1_000_000_000, clock)
        assertEquals(1, retry.timeouts)
        available = true
        assertEquals(2, retry.acquire())
        assertEquals(1, retry.timeouts)
    }

    @Test fun shutdownDuringDrainDoesNotCountAsDroppedAudioOrPollAgain() {
        var running = true
        var polls = 0
        val retry = AudioDecoderInputRetry(
            dequeue = { polls++; -1 }, drainOutput = { running = false },
            running = { running }, nowNs = { 0 },
        )
        assertNull(retry.acquire())
        assertEquals(1, polls)
        assertEquals(0, retry.timeouts)
    }

    @Test fun releasedOutputCanRecoverEvenIfWritingItTookTheWholeDeadline() {
        var clock = 0L
        var ready = false
        val retry = AudioDecoderInputRetry(
            dequeue = { if (ready) 1 else -1 },
            drainOutput = { ready = true; clock += 1_100_000_000 },
            running = { true }, nowNs = { clock },
        )
        assertEquals(1, retry.acquire())
        assertEquals(0, retry.timeouts)
    }

    @Test fun availableBuffersAreNotDelayedOrDrained() {
        val retry = AudioDecoderInputRetry(
            dequeue = { 0 }, drainOutput = { error("Unexpected drain") }, running = { true },
        )
        assertEquals(0, retry.acquire())
        assertEquals(0, retry.retries)
    }
}
