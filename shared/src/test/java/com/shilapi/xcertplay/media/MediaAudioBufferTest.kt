package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaAudioBufferTest {
    @Test
    fun `buffered TCP music keeps its original anchor and FIFO policy`() {
        for (millis in listOf(100, 150, 300)) {
            val actual = MediaAudioBuffer.playbackMillis(millis, bufferedStream = true)
            assertEquals(300, actual)
            assertNull(MediaAudioBuffer.queueLimitMillis(true, actual))
            assertEquals(500L, MediaAudioBuffer.tailWaitMillis(true, actual))
        }
        for (millis in listOf(100, 150, 300, 500, 1000)) {
            assertEquals(millis, MediaAudioBuffer.playbackMillis(millis, bufferedStream = false))
        }
        assertEquals(1000, MediaAudioBuffer.playbackMillis(1000, bufferedStream = true))
    }

    @Test
    fun `short media presets reduce both start delay and track capacity`() {
        val hundred = MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 100)
        assertEquals(19_200, hundred.startBytes)
        assertEquals(28_800, hundred.trackBufferBytes)
        val hundredFifty = MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 150)
        assertEquals(28_800, hundredFifty.startBytes)
        assertEquals(38_400, hundredFifty.trackBufferBytes)
        assertEquals(200, MediaAudioBuffer.queueLimitMillis(true, 100))
        assertEquals(300, MediaAudioBuffer.queueLimitMillis(true, 150))
        assertEquals(50L, MediaAudioBuffer.tailWaitMillis(true, 150))
    }

    @Test
    fun `hardware minimum still bounds short media presets`() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 32_768, 100)
        assertEquals(32_768, plan.startBytes)
        assertEquals(65_536, plan.trackBufferBytes)
    }

    @Test
    fun `short media presets never trim speech and stable media retains FIFO`() {
        assertNull(MediaAudioBuffer.queueLimitMillis(false, 100))
        assertNull(MediaAudioBuffer.queueLimitMillis(false, 150))
        for (millis in listOf(300, 500, 1000)) {
            assertNull(MediaAudioBuffer.queueLimitMillis(true, millis))
            assertEquals(500L, MediaAudioBuffer.tailWaitMillis(true, millis))
        }
        assertEquals(500L, MediaAudioBuffer.tailWaitMillis(false, 150))
        assertEquals(MediaAudioBuffer.plan(false, 48_000, 1, 7_680, 1000),
            MediaAudioBuffer.plan(false, 48_000, 1, 7_680, 100))
    }

    @Test
    fun `music buffers the chosen delay plus headroom`() {
        // 48 kHz stereo 16-bit = 192 000 bytes/s.
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
        assertEquals(134_400, plan.trackBufferBytes)
    }

    @Test
    fun `all streams mapped to media receive the music buffer`() {
        val selection = AudioChannelMapper.map(
            "compatibility", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS,
        )
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            48_000, 2, minBufferBytes = 7_680, mediaMillis = 500)
        assertEquals(96_000, plan.startBytes)
    }

    @Test
    fun `calls and prompts keep the low-latency buffer`() {
        val plan = MediaAudioBuffer.plan(false, 16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000)
        assertEquals(4 * 1024, plan.startBytes)
        assertEquals(16 * 1024, plan.trackBufferBytes)
        val guidance = AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS)
        assertEquals(plan, MediaAudioBuffer.plan(guidance.channel == AudioChannel.MEDIA,
            16_000, 1, minBufferBytes = 1_280, mediaMillis = 1000))
    }

    @Test
    fun `unknown delay falls back to the default`() {
        assertEquals(MediaAudioBuffer.DEFAULT_MILLIS, MediaAudioBuffer.sanitize(250))
        assertEquals(57_600, MediaAudioBuffer.plan(true, 48_000, 2, 7_680, mediaMillis = 42).startBytes)
    }

    @Test
    fun `start level stays below a smaller granted buffer`() {
        assertEquals(98_000, MediaAudioBuffer.startBytesFor(192_000, 100_000, 2_048 - 48))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 134_400, 2_048))
        assertEquals(57_600, MediaAudioBuffer.startBytesFor(57_600, 0, 2_048))
    }
}
