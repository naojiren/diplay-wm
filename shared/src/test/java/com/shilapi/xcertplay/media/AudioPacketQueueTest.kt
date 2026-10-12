package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioPacketQueueTest {
    @Test fun twoSecondAacBurstKeepsRecentSoundInsteadOfTwoSecondsOfOldSound() {
        val queue = AudioPacketQueue<Int>(192, 48_000, 300) { 0L }
        repeat(96) { assertTrue(queue.offer(it, it * 1024)) }
        val snapshot = queue.snapshotAndResetEvictions()
        assertEquals(15, snapshot.size)
        assertEquals(81, snapshot.evicted)
        assertTrue(snapshot.spanMs <= 300)
        assertEquals(81, queue.poll(0))
        for (expected in 82..95) assertEquals(expected, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun rendererStallCannotReplayExpiredMedia() {
        var now = 0L
        val queue = AudioPacketQueue<Int>(192, 48_000, 300) { now }
        repeat(3) { queue.offer(it, it * 1024) }
        now = 301_000_000L
        assertNull(queue.poll(0))
        assertEquals(3, queue.snapshotAndResetEvictions().evicted)
        queue.offer(99, 4096)
        assertEquals(99, queue.poll(0))
    }

    @Test fun normalContinuousPlaybackPreservesEveryPacket() {
        var now = 0L
        val queue = AudioPacketQueue<Int>(192, 48_000, 200) { now }
        repeat(1000) { index ->
            now += 21_333_333L
            assertTrue(queue.offer(index, index * 1024))
            now += 5_000_000L
            assertEquals(index, queue.poll(0))
        }
        assertEquals(0, queue.snapshotAndResetEvictions().evicted)
    }

    @Test fun unsignedRtpWrapStillBoundsTheQueue() {
        val queue = AudioPacketQueue<Int>(192, 48_000, 200) { 0L }
        repeat(21) { queue.offer(it, (0xffff_ff00L + it * 1024L).toInt()) }
        assertEquals(11, queue.poll(0))
        assertTrue(queue.snapshotAndResetEvictions().spanMs <= 200)
    }

    @Test fun reorderedOrDuplicatePacketDoesNotEvictFreshSound() {
        val queue = AudioPacketQueue<Int>(192, 48_000, 200) { 0L }
        queue.offer(1, 2048)
        queue.offer(2, 1024)
        queue.offer(3, 2048)
        assertEquals(0, queue.snapshotAndResetEvictions().evicted)
        assertEquals(1, queue.poll(0))
        assertEquals(2, queue.poll(0))
        assertEquals(3, queue.poll(0))
    }

    @Test fun speechAndStableMediaKeepTheOriginalFifoEvenAfterAStall() {
        var now = 0L
        val queue = AudioPacketQueue<Int>(192, 48_000) { now }
        repeat(192) { assertTrue(queue.offer(it, it * 1024)) }
        assertFalse(queue.offer(192, 192 * 1024))
        now = 10_000_000_000L
        repeat(192) { assertEquals(it, queue.poll(0)) }
        assertEquals(0, queue.snapshotAndResetEvictions().evicted)
    }

    @Test fun waitingConsumerWakesWhenAudioArrives() {
        val queue = AudioPacketQueue<Int>(192, 48_000, 300)
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val received = AtomicInteger(-1)
        val worker = Thread {
            ready.countDown()
            queue.poll(1000)?.let(received::set)
            finished.countDown()
        }
        worker.start()
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            assertTrue(queue.offer(42, 0))
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(42, received.get())
        } finally {
            worker.interrupt()
            worker.join(1000)
        }
    }
}
