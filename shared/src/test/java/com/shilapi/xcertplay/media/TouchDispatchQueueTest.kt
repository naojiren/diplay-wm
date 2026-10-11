package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class TouchDispatchQueueTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun backlogKeepsLatestMoveAndEveryPressReleaseAndPointerBoundary() {
        val worker = Worker()
        val sent = mutableListOf<String>()
        var coalesced = 0
        val queue = TouchDispatchQueue<String>(worker, onCoalesced = { coalesced++ }) { value, _ -> sent += value }
        val session = Any()
        listOf("down", "move1", "move2", "pointerDown", "move3", "move4", "pointerUp", "up", "cancel").forEach {
            assertTrue(queue.offer(it, session, it.startsWith("move")))
        }
        assertEquals(1, worker.tasks.size)
        worker.run()
        assertEquals(listOf("down", "move2", "pointerDown", "move4", "pointerUp", "up", "cancel"), sent)
        assertEquals(2, coalesced)
    }

    @Test fun equalButDistinctSessionsCannotCoalesceOneAnothersPackets() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { value, _ -> sent += value }
        queue.offer(1, String(charArrayOf('s')), true)
        queue.offer(2, String(charArrayOf('s')), true)
        worker.run()
        assertEquals(listOf(1, 2), sent)
    }

    @Test fun waitIsMeasuredFromLatestMoveAndNewWorkDuringSendIsDrained() {
        val worker = Worker()
        var time = 100L
        val sent = mutableListOf<Pair<Int, Long>>()
        val session = Any()
        lateinit var queue: TouchDispatchQueue<Int>
        queue = TouchDispatchQueue(worker, nanoTime = { time }) { value, wait ->
            sent += value to wait
            if (value == 2) queue.offer(3, session, false)
        }
        queue.offer(1, session, true)
        time = 150
        queue.offer(2, session, true)
        time = 200
        worker.run()
        assertEquals(listOf(2 to 50L, 3 to 0L), sent)
        queue.offer(4, session, false)
        worker.run()
        assertEquals(4 to 0L, sent.last())
    }

    @Test fun sendFailureDoesNotStrandFollowingTouchesAndClearCancelsPendingWork() {
        val worker = Worker()
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(worker) { value, _ -> if (value == 1) error("transport closed") else sent += value }
        val session = Any()
        queue.offer(1, session, false); queue.offer(2, session, false)
        worker.run()
        queue.offer(3, session, false); queue.clear(); worker.run()
        queue.offer(4, session, false); worker.run()
        assertEquals(listOf(2, 4), sent)
    }

    @Test fun rejectedWorkerReturnsFailureAndDoesNotRetainOldTouches() {
        var reject = true
        val sent = mutableListOf<Int>()
        val queue = TouchDispatchQueue<Int>(Executor { if (reject) throw RejectedExecutionException() else it.run() }) { value, _ -> sent += value }
        assertFalse(queue.offer(1, Any(), false))
        reject = false
        assertTrue(queue.offer(2, Any(), false))
        assertEquals(listOf(2), sent)
    }
}
