package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2CsmFramerIncrementalTest {
    @Test
    fun incrementalInputBoundsBufferEvenForBatchLargerThanOneMebibyte() {
        val frame = Iap2Frame(0x5201, ByteArray(1_018) { it.toByte() })
        val encoded = frame.encodedFrame()
        val count = 1_100
        val input = ByteArray(encoded.size * count) { encoded[it % encoded.size] }
        val framer = Iap2CsmFramer()
        var offset = 0
        var received = 0
        while (offset < input.size) {
            val consumed = framer.append(input, offset)
            assertTrue(consumed in 1..Iap2CsmFramer.MAX_FRAME_BYTES)
            offset += consumed
            while (true) {
                val next = framer.poll() ?: break
                assertEquals(frame, next)
                received++
            }
        }
        assertEquals(count, received)
        assertNull(framer.poll())
    }

    @Test
    fun splitStartMarkerAndInvalidLengthsResynchronizeWithoutLosingFrames() {
        val first = Iap2Frame(0x5001, byteArrayOf(1, 2, 3))
        val second = Iap2Frame(0x5201, byteArrayOf(4, 5))
        val garbage = byteArrayOf(0, 1, 0x40, 0x40, 0, 2, 3)
        val bytes = garbage + first.encodedFrame() + second.encodedFrame()
        val actual = mutableListOf<Iap2Frame>()
        // Exercise every possible split, including the marker and the two-byte frame length.
        for (split in 1 until bytes.size) {
            val parser = Iap2CsmFramer()
            actual.clear()
            listOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size)).forEach { chunk ->
                var offset = 0
                while (offset < chunk.size) {
                    offset += parser.append(chunk, offset)
                    while (true) actual += parser.poll() ?: break
                }
            }
            assertEquals("split=$split", listOf(first, second), actual)
        }
    }
}
