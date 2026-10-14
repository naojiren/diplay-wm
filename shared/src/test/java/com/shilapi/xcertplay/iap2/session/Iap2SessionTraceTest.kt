package com.shilapi.xcertplay.iap2.session

import com.shilapi.xcertplay.iap2.trace.Iap2TraceDirection
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2SessionTraceTest {
    @Test
    fun defaultSkipsFormattingAndLoggingEverySuccessfulFrameButKeepsDiagnostics() {
        val messages = mutableListOf<String>()
        val trace = Iap2SessionTrace(
            context = "wireless-tunnel",
            onTrace = messages::add,
            formatFrame = { _, _, _ -> error("Detailed formatter must not run in normal sessions") },
        )
        // Both directions and repeated navigation/music updates must avoid the expensive work.
        repeat(1_000) {
            trace.frame(Iap2TraceDirection.RX, Iap2Frame(0x5201, byteArrayOf()))
            trace.frame(Iap2TraceDirection.TX, Iap2Frame(0x5001, byteArrayOf()))
        }
        assertTrue(messages.isEmpty())

        trace.emit("IAP2 READY [wireless-tunnel] ready=true")
        trace.emit("IAP2 RX [wireless-tunnel] FAILED: transport closed")
        trace.emit("IAP2 CLOSE [wireless-tunnel]")
        assertEquals(3, messages.size)
        assertTrue(messages[1].contains("FAILED: transport closed"))
    }

    @Test
    fun optInFormattingReceivesTheOriginalDirectionContextAndFrame() {
        val messages = mutableListOf<String>()
        val frame = Iap2Frame(0x5201, byteArrayOf(1, 2, 3))
        var calls = 0
        val trace = Iap2SessionTrace(
            context = "tunnel",
            onTrace = messages::add,
            detailedFrameTracing = true,
            formatFrame = { direction, context, actualFrame ->
                calls++
                assertEquals(Iap2TraceDirection.RX, direction)
                assertEquals("tunnel", context)
                assertTrue(frame === actualFrame)
                "detailed frame"
            },
        )

        trace.frame(Iap2TraceDirection.RX, frame)

        assertEquals(1, calls)
        assertEquals(listOf("detailed frame"), messages)
    }

    @Test
    fun formatterFailureIsReportedWithoutInterruptingControlProcessing() {
        val messages = mutableListOf<String>()
        val trace = Iap2SessionTrace(
            context = "tunnel",
            onTrace = messages::add,
            detailedFrameTracing = true,
            formatFrame = { _, _, _ -> throw IllegalArgumentException("trace failure") },
        )

        trace.frame(Iap2TraceDirection.RX, Iap2Frame(0x5201, byteArrayOf()))

        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("FAILED: trace failure"))
    }

    @Test
    fun throwingLogSinkDoesNotEscapeThroughEitherTracePath() {
        val trace = Iap2SessionTrace(
            context = "tunnel",
            onTrace = { throw IllegalStateException("log unavailable") },
            detailedFrameTracing = true,
        )

        trace.emit("IAP2 READY [tunnel] ready=true")
        trace.frame(Iap2TraceDirection.RX, Iap2Frame(0x5201, byteArrayOf()))
    }
}
