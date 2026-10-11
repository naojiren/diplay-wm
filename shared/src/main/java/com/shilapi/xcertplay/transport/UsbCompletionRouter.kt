package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** requestWait is connection-wide, even when readers use different USB interfaces. */
internal class UsbCompletionRouter<T : Any>(private val poll: (Long) -> T?) {
    private val lock = ReentrantLock(true)
    private val requests = IdentityHashMap<T, Boolean>()

    fun register(request: T) = lock.withLock { requests[request] = false }
    fun forget(request: T) = lock.withLock { requests.remove(request); Unit }

    fun await(request: T, timeoutMillis: Long): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1))
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("USB request completion timed out")
            try {
                if (!lock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                    throw TimeoutException("USB completion dispatcher is busy")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("USB completion wait interrupted", interrupted)
            }
            try {
                if (!requests.containsKey(request)) throw IOException("USB request was closed")
                if (requests[request] == true) {
                    requests.remove(request)
                    return request
                }
                val pollMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 50)
                val completed = try { poll(pollMillis) } catch (_: TimeoutException) { continue }
                    ?: throw IOException("USB connection returned no completed request")
                if (requests.containsKey(completed)) requests[completed] = true
            } finally {
                lock.unlock()
            }
        }
    }
}
