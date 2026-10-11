package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class LegacyAvcSpsTest {
    @Test fun constrainedHighWithoutVuiGetsBoundedWithoutChangingReferencesOrDimensions() {
        val original = sps(profile = 100, constraints = 0x10, refs = 3, vui = false)
        val result = LegacyAvcSps.tune(original)
        assertTrue(result.changed)
        val second = LegacyAvcSps.tune(result.bytes)
        assertFalse(second.changed)
        assertTrue(second.description.contains("profile=100 refs=3 reorder=0 buffer=3"))
        assertArrayEquals(result.bytes, second.bytes)
    }

    @Test fun highProfileWithExplicitZeroReorderingShrinksOnlyExcessDisplayBuffering() {
        val result = LegacyAvcSps.tune(sps(profile = 100, refs = 2, reorder = 0, buffer = 6, detailedVui = true))
        assertTrue(result.changed)
        assertTrue(LegacyAvcSps.tune(result.bytes).description.contains("profile=100 refs=2 reorder=0 buffer=2"))
        // Colour, timing, cropping and HRD are before the restriction and remain byte-for-byte identical.
        assertArrayEquals(sps(profile = 100, refs = 2, reorder = 0, buffer = 2, detailedVui = true), result.bytes)
    }

    @Test fun actualReorderingAndUnknownHighProfileOrderingArePreserved() {
        // Baseline prohibits B slices but does not itself prove output order equals decoding order.
        for (original in listOf(sps(profile = 100, reorder = 2, buffer = 6),
                sps(profile = 100, vui = false), sps(profile = 66, vui = false))) {
            val result = LegacyAvcSps.tune(original)
            assertFalse(result.changed)
            assertSame(original, result.bytes)
        }
    }

    @Test fun referenceBufferIsNeverReducedBelowDeclaredReferences() {
        val original = sps(refs = 4, reorder = 0, buffer = 4)
        assertFalse(LegacyAvcSps.tune(original).changed)
        assertTrue(LegacyAvcSps.tune(sps(refs = 4, reorder = 0, buffer = 8)).description.endsWith("buffer=4"))
    }

    @Test fun interlacedOrMalformedConfigurationIsNotRewritten() {
        val originals = listOf(sps(progressive = false), byteArrayOf(), byteArrayOf(0x67), ByteArray(5000))
        for (original in originals) assertSame(original, LegacyAvcSps.tune(original).bytes)
        val valid = sps(detailedVui = true)
        for (end in 0 until valid.size) {
            val truncated = valid.copyOf(end)
            assertSame(truncated, LegacyAvcSps.tune(truncated).bytes)
        }
    }

    @Test fun inBandSpsIsPatchedWhilePpsAndVideoBytesRemainIdentical() {
        val prefix = byteArrayOf(0, 0, 0, 1)
        val source = sps()
        val suffix = prefix + byteArrayOf(0x68, 0x33, 0x77) + prefix + byteArrayOf(0x65, 0x22, 0x11)
        val accessUnit = prefix + source + suffix
        val expected = prefix + LegacyAvcSps.tune(source).bytes + suffix
        assertArrayEquals(expected, LegacyAvcSps.tuneAccessUnit(accessUnit))
        assertArrayEquals(byteArrayOf(0, 0, 1) + LegacyAvcSps.tune(source).bytes,
            LegacyAvcSps.tuneAccessUnit(byteArrayOf(0, 0, 1) + source))
        val ordinary = prefix + byteArrayOf(0x41, 1, 2, 3)
        assertSame(ordinary, LegacyAvcSps.tuneAccessUnit(ordinary))
    }

    @Test fun zeroReferencePicturesStillAllowOneDisplayedPicture() {
        val result = LegacyAvcSps.tune(sps(refs = 0, reorder = 0, buffer = 4))
        assertTrue(result.changed)
        assertTrue(result.description.endsWith("buffer=1"))
    }

    private fun sps(profile: Int = 66, constraints: Int = 0, refs: Int = 1, vui: Boolean = true,
                    reorder: Int = 0, buffer: Int = 4, detailedVui: Boolean = false,
                    progressive: Boolean = true): ByteArray {
        val b = FixtureBits()
        b.u(profile, 8); b.u(constraints, 8); b.u(31, 8); b.ue(0)
        if (profile == 100) { b.ue(1); b.ue(0); b.ue(0); b.u(0, 1); b.u(0, 1) }
        b.ue(0); b.ue(0); b.ue(0); b.ue(refs); b.u(0, 1)
        b.ue(33); b.ue(59); b.u(if (progressive) 1 else 0, 1)
        if (!progressive) b.u(0, 1)
        b.u(1, 1); b.u(1, 1); b.ue(0); b.ue(2); b.ue(0); b.ue(0)
        b.u(if (vui) 1 else 0, 1)
        if (vui) {
            if (detailedVui) {
                b.u(1, 1); b.u(255, 8); b.u(1, 16); b.u(1, 16)
                b.u(1, 1); b.u(0, 1)
                b.u(1, 1); b.u(5, 3); b.u(1, 1); b.u(1, 1)
                b.u(1, 8); b.u(1, 8); b.u(1, 8)
                b.u(1, 1); b.ue(0); b.ue(0)
                b.u(1, 1); b.u(1, 32); b.u(8, 32); b.u(1, 1)
                b.u(1, 1); b.ue(0); b.u(0, 8); b.ue(1); b.ue(1); b.u(1, 1); b.u(0, 20)
                b.u(0, 1); b.u(0, 1); b.u(0, 1)
            } else b.u(0, 8)
            b.u(1, 1); b.u(1, 1); b.ue(2); b.ue(1); b.ue(16); b.ue(16); b.ue(reorder); b.ue(buffer)
        }
        b.u(1, 1)
        return byteArrayOf(0x67) + b.ebsp()
    }

    private class FixtureBits {
        private val bits = StringBuilder()
        fun u(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.append(if ((value ushr i) and 1 == 1) '1' else '0')
        }
        fun ue(value: Int) {
            val binary = Integer.toBinaryString(value + 1)
            bits.append("0".repeat(binary.length - 1)).append(binary)
        }
        fun ebsp(): ByteArray {
            while (bits.length % 8 != 0) bits.append('0')
            val output = ArrayList<Byte>()
            var zeros = 0
            for (part in bits.toString().chunked(8)) {
                val value = part.toInt(2)
                if (zeros >= 2 && value <= 3) { output.add(3); zeros = 0 }
                output.add(value.toByte())
                zeros = if (value == 0) zeros + 1 else 0
            }
            return output.toByteArray()
        }
    }
}
