package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = ArrayDeque<VideoJob>()
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private var frameCount = 0
    private var byteCount = 0L
    data class Snapshot(val frames: Int, val bytes: Long, val oldestMs: Long)

    fun offer(job: VideoJob) = lock.withLock {
        if (job is VideoJob.Frame) {
            if (frameCount >= maxFrames || byteCount + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.addLast(VideoJob.Resync)
                available.signal()
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
            frameCount++; byteCount += job.nalus.size
        }
        jobs.addLast(job)
        available.signal()
    }

    fun discardFrames() = lock.withLock {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoJob.Frame || item is VideoJob.Resync) iterator.remove()
        }
        frameCount = 0; byteCount = 0
    }

    fun poll(timeoutMillis: Long): VideoJob? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (jobs.isEmpty() && remaining > 0) remaining = available.awaitNanos(remaining)
        jobs.pollFirst()?.also { if (it is VideoJob.Frame) { frameCount--; byteCount -= it.nalus.size } }
    }

    fun snapshot(): Snapshot = lock.withLock {
        val oldest = jobs.firstOrNull { it is VideoJob.Frame } as? VideoJob.Frame
        Snapshot(frameCount, byteCount, oldest?.let { (System.nanoTime() - it.receivedNs).coerceAtLeast(0) / 1_000_000 } ?: 0)
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = lock.withLock {
        jobs.toList().also { jobs.clear(); frameCount = 0; byteCount = 0 }
    }
}

/** Bound waiting before decoding; hardware's own output delay is not an input backlog. */
internal object LowLatencyVideoPolicy {
    const val MAX_AGE_NS = 150_000_000L

    /** Avoid reserving eight MiB per input slot for a small EX5 stream. Grow on oversized input. */
    fun inputCapacity(width: Int, height: Int, required: Int = 0): Int {
        val estimate = width.toLong().coerceIn(1, 16384) * height.coerceIn(1, 16384) * 3 / 2 + 65536
        val target = maxOf(estimate, required.toLong()).coerceIn(512 * 1024L, 8 * 1024 * 1024L)
        var size = 512 * 1024
        while (size < target) size *= 2
        return size
    }
}

/** Keep the newest available decoded output, without rejecting the decoder's only ready frame. */
internal class LatestVideoOutput<T : Any>(private val discard: (T) -> Unit) {
    private var pending: T? = null

    fun offer(output: T) {
        val previous = pending
        pending = output
        previous?.let(discard)
    }

    fun take(): T? = pending.also { pending = null }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
