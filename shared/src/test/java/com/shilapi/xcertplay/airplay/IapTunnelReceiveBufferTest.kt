package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IapTunnelReceiveBufferTest {
    @Test
    fun fullBufferWaitsThenPreservesBytesWhenReaderResumes() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 32)
        val original = ByteArray(32) { it.toByte() }
        val next = ByteArray(24) { (it + 32).toByte() }
        buffer.offer(original)
        val writer = start { buffer.offer(next) }
        try {
            awaitWaiting(writer.first)
            assertFalse(writer.second.isDone)
            val output = ByteArrayOutputStream()
            while (output.size() < original.size + next.size) {
                output.write(buffer.recv(7, 1_000) ?: error("reader timed out"))
            }
            writer.second.get(1, TimeUnit.SECONDS)
            assertArrayEquals(original + next, output.toByteArray())
            assertNull(buffer.recv(1, 0))
        } finally {
            buffer.close()
            writer.first.join(1_000)
        }
    }

    @Test
    fun fourMebibyteOfferCanStreamThroughMuchSmallerBuffer() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 16_384)
        val input = ByteArray(4 * 1024 * 1024) { (it xor (it ushr 12)).toByte() }
        val writer = start { buffer.offer(input) }
        try {
            awaitWaiting(writer.first)
            val output = ByteArrayOutputStream()
            while (output.size() < input.size) {
                output.write(buffer.recv(3_997, 1_000) ?: error("reader timed out"))
            }
            writer.second.get(1, TimeUnit.SECONDS)
            assertArrayEquals(input, output.toByteArray())
        } finally {
            buffer.close()
            writer.first.join(1_000)
        }
    }

    @Test
    fun concurrentOffersDoNotInterleaveTheirPartialPackages() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 32)
        val first = ByteArray(1_000) { it.toByte() }
        val second = ByteArray(1_000) { (it xor 0x55).toByte() }
        val writer = start { buffer.offer(first) }
        awaitWaiting(writer.first)
        val other = start { buffer.offer(second) }
        try {
            val output = ByteArrayOutputStream()
            while (output.size() < first.size + second.size) {
                output.write(buffer.recv(11, 1_000) ?: error("reader timed out"))
            }
            writer.second.get(1, TimeUnit.SECONDS)
            other.second.get(1, TimeUnit.SECONDS)
            assertArrayEquals(first + second, output.toByteArray())
        } finally {
            buffer.close()
            writer.first.join(1_000)
            other.first.join(1_000)
        }
    }

    @Test
    fun closeWakesBlockedWriterAndReaderWithoutServingStaleBytes() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 4)
        buffer.offer(byteArrayOf(1, 2, 3, 4))
        val writer = start { buffer.offer(byteArrayOf(5)) }
        awaitWaiting(writer.first)
        buffer.close("test close")
        assertTrue(taskFailure(writer.second).message!!.contains("closed"))
        assertArrayEquals(byteArrayOf(), buffer.recv(10, 0))

        val empty = IapTunnelReceiveBuffer(capacityBytes = 4)
        val receivedEof = AtomicBoolean(false)
        val reader = start { receivedEof.set(empty.recv(1, 10_000)?.isEmpty() == true) }
        awaitWaiting(reader.first)
        empty.close()
        reader.second.get(1, TimeUnit.SECONDS)
        assertTrue(receivedEof.get())
    }

    @Test
    fun failureWakesWaitingProducerAndRemainsVisibleToConsumer() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 4)
        buffer.offer(byteArrayOf(1, 2, 3, 4))
        val writer = start { buffer.offer(byteArrayOf(5)) }
        awaitWaiting(writer.first)
        buffer.fail(IOException("test peer reset"))
        assertTrue(taskFailure(writer.second).message!!.contains("test peer reset"))
        assertTrue(receiveFailure(buffer).message!!.contains("test peer reset"))

        val empty = IapTunnelReceiveBuffer(capacityBytes = 4)
        val reader = start { empty.recv(1, 10_000); Unit }
        awaitWaiting(reader.first)
        empty.fail(IOException("test disconnected"))
        assertTrue(taskFailure(reader.second).message!!.contains("test disconnected"))
    }

    @Test
    fun interruptedOfferFailsRatherThanSilentlyDroppingRemainingData() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 4)
        buffer.offer(byteArrayOf(1, 2, 3, 4))
        val remainedInterrupted = AtomicBoolean(false)
        val writer = start {
            try {
                buffer.offer(byteArrayOf(5, 6))
            } finally {
                remainedInterrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        awaitWaiting(writer.first)
        writer.first.interrupt()
        assertTrue(taskFailure(writer.second) is InterruptedIOException)
        assertTrue(remainedInterrupted.get())
        assertTrue(receiveFailure(buffer).message!!.contains("interrupted"))
    }

    @Test
    fun prolongedConsumerStallIsBoundedAndReported() {
        val buffer = IapTunnelReceiveBuffer(capacityBytes = 4, maximumStallMillis = 40)
        buffer.offer(byteArrayOf(1, 2, 3, 4))
        val writer = start { buffer.offer(byteArrayOf(5)) }
        assertTrue(taskFailure(writer.second).message!!.contains("consumer stalled"))
        assertTrue(receiveFailure(buffer).message!!.contains("consumer stalled"))
    }

    private fun start(action: () -> Unit): Pair<Thread, FutureTask<Unit>> {
        val task = FutureTask<Unit> { action() }
        val thread = Thread(task, "iap-buffer-test").apply { isDaemon = true; start() }
        return thread to task
    }

    private fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("expected blocked producer/consumer, got ${thread.state}", thread.state == Thread.State.TIMED_WAITING)
    }

    private fun taskFailure(task: FutureTask<Unit>): Throwable {
        try {
            task.get(2, TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            return failure.cause!!
        }
        fail("expected task to fail")
        error("unreachable")
    }

    private fun receiveFailure(buffer: IapTunnelReceiveBuffer): IOException {
        try {
            buffer.recv(10, 0)
        } catch (failure: IOException) {
            return failure
        }
        fail("expected a terminal receive failure")
        error("unreachable")
    }
}
