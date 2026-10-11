package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun countersTrackPollDiscardOverflowAndDrainWithoutCountingControlJobs() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 10)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        queue.offer(config)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(4)))
        assertEquals(2, queue.snapshot().frames)
        assertEquals(7L, queue.snapshot().bytes)
        assertSame(config, queue.poll(0))
        queue.poll(0)
        assertEquals(1, queue.snapshot().frames)
        assertEquals(4L, queue.snapshot().bytes)
        queue.offer(VideoJob.Frame(ByteArray(8)))
        assertEquals(1, queue.snapshot().frames)
        assertEquals(8L, queue.snapshot().bytes)
        assertEquals(VideoJob.Resync, queue.poll(0))
        queue.discardFrames()
        assertEquals(0, queue.snapshot().frames)
        assertEquals(0L, queue.snapshot().bytes)
        queue.offer(config)
        queue.offer(VideoJob.Frame(ByteArray(2)))
        assertEquals(2, queue.drain().size)
        assertEquals(VideoDecodeQueue.Snapshot(0, 0, 0), queue.snapshot())
        assertNull(queue.poll(0))
    }

    @Test fun blockingConsumerWakesAndConcurrentProducersLoseNoFramesWithinBudget() {
        val queue = VideoDecodeQueue(maxFrames = 1000)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(5)
        try {
            val received = executor.submit<List<Int>> {
                List(400) { (queue.poll(3000) as VideoJob.Frame).nalus[0].toInt() }
            }
            val producers = (0..3).map { producer -> executor.submit { repeat(100) { queue.offer(VideoJob.Frame(byteArrayOf(producer.toByte()))) } } }
            producers.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(mapOf(0 to 100, 1 to 100, 2 to 100, 3 to 100), received.get(5, java.util.concurrent.TimeUnit.SECONDS).groupingBy { it }.eachCount())
            assertEquals(VideoDecodeQueue.Snapshot(0, 0, 0), queue.snapshot())
        } finally { executor.shutdownNow() }
    }

    @Test fun slowJ6DecoderDoesNotReduceEighteenReadyFramesToTwoDisplayedFrames() {
        val discarded = mutableListOf<Long>()
        val output = LatestVideoOutput<Long> { discarded.add(it) }
        val presented = mutableListOf<Long>()
        // Each ready frame has the measured 230 ms decoder delay.
        // It is still the newest available picture, even though it is older than 150 ms.
        repeat(18) { frame ->
            output.offer(frame * 55_555_556L - 230_000_000L)
            output.take()?.let { presented.add(it) }
        }
        assertEquals(18, presented.size)
        assertTrue(discarded.isEmpty())
        assertNull(output.take())
    }

    @Test fun burstReleasesOlderDecodedBuffersAndShowsOnlyTheNewest() {
        val discarded = mutableListOf<Int>()
        val output = LatestVideoOutput<Int> { discarded.add(it) }
        listOf(4, 7, 2).forEach(output::offer) // Buffer indices are not presentation order.
        assertEquals(listOf(4, 7), discarded)
        assertEquals(2, output.take())
        assertNull(output.take())
    }

    @Test fun shutdownCanReleasePendingOutputExactlyOnce() {
        val released = mutableListOf<Int>()
        val output = LatestVideoOutput<Int> { released.add(it) }
        output.offer(5)
        output.take()?.let { released.add(it) }
        output.take()?.let { released.add(it) }
        assertEquals(listOf(5), released)
    }

    @Test fun pendingLatestBufferRemainsReleasableIfDiscardingAnOlderBufferFails() {
        val output = LatestVideoOutput<Int> { throw IllegalStateException("decoder stopped") }
        output.offer(1)
        try {
            output.offer(2)
            fail("expected stopped decoder")
        } catch (_: IllegalStateException) {
            assertEquals(2, output.take())
            assertNull(output.take())
        }
    }
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
}
