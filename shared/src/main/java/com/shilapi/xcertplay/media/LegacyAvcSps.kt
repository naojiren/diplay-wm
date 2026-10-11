package com.shilapi.xcertplay.media

import java.io.ByteArrayOutputStream

/**
 * Narrow TI workaround: bound the display buffer only when the SPS proves zero frame reordering.
 * Reference pictures, profile, level, cropping, colours and timing are preserved. Unlike streaming
 * clients that control their encoder, we cannot safely force Apple's reference-picture count to one.
 */
internal object LegacyAvcSps {
    data class Result(val bytes: ByteArray, val changed: Boolean, val description: String)
    private val extendedProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
    private val zeroReorderConstraintProfiles = setOf(44, 86, 100, 110, 122, 244)

    fun tune(nal: ByteArray): Result {
        return try {
            require(nal.size in 5..4096 && nal[0].toInt() and 0x9f == 7)
            val data = unescape(nal.copyOfRange(1, nal.size))
            val reader = Bits(data)
            val profile = reader.read(8)
            val constraints = reader.read(8)
            reader.skip(8) // level_idc
            require(reader.ue() <= 31)
            if (profile in extendedProfiles) {
                val chroma = reader.ue().also { require(it <= 3) }
                if (chroma == 3) reader.skip(1)
                require(reader.ue() <= 6 && reader.ue() <= 6)
                reader.skip(1)
                if (reader.flag()) repeat(if (chroma == 3) 12 else 8) { index ->
                    if (reader.flag()) {
                        var last = 8
                        var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = (last + reader.se() + 256) and 255
                            if (next != 0) last = next
                        }
                    }
                }
            } else require(profile in setOf(66, 77, 88))
            require(reader.ue() <= 12)
            when (reader.ue()) {
                0 -> require(reader.ue() <= 12)
                1 -> {
                    reader.skip(1); reader.se(); reader.se()
                    val count = reader.ue().also { require(it <= 255) }
                    repeat(count) { reader.se() }
                }
                2 -> Unit
                else -> error("unsupported picture order")
            }
            val references = reader.ue().also { require(it <= 16) }
            reader.skip(1)
            require(reader.ue() <= 65535 && reader.ue() <= 65535)
            val progressive = reader.flag()
            if (!progressive) reader.skip(1)
            reader.skip(1)
            if (reader.flag()) repeat(4) { reader.ue() }
            val vuiPosition = reader.position
            val hasVui = reader.flag()
            var restrictionPosition = -1
            var hasRestriction = false
            var reorder: Int? = null
            var buffering: Int? = null
            var motion = true
            var limits = intArrayOf(2, 1, 16, 16)
            if (hasVui) {
                if (reader.flag() && reader.read(8) == 255) reader.skip(32)
                if (reader.flag()) reader.skip(1)
                if (reader.flag()) {
                    reader.skip(4)
                    if (reader.flag()) reader.skip(24)
                }
                if (reader.flag()) { reader.ue(); reader.ue() }
                if (reader.flag()) reader.skip(65)
                val nalHrd = reader.flag()
                if (nalHrd) reader.hrd()
                val vclHrd = reader.flag()
                if (vclHrd) reader.hrd()
                if (nalHrd || vclHrd) reader.skip(1)
                reader.skip(1)
                restrictionPosition = reader.position
                hasRestriction = reader.flag()
                if (hasRestriction) {
                    motion = reader.flag()
                    limits = IntArray(4) { reader.ue() }
                    reorder = reader.ue().also { require(it <= 16) }
                    buffering = reader.ue().also { require(it <= 16 && it >= references && it >= (reorder ?: 0)) }
                }
            }
            require(reader.flag()) // rbsp_stop_one_bit
            while (reader.position < data.size * 8) require(!reader.flag())
            val summary = "profile=$profile refs=$references reorder=${reorder ?: "unspecified"} buffer=${buffering ?: "unspecified"}"
            val provenNoReorder = reorder == 0 ||
                (!hasRestriction && profile in zeroReorderConstraintProfiles && constraints and 0x10 != 0)
            if (!progressive || !provenNoReorder) return Result(nal, false, "$summary unchanged: ordering not proven")
            val target = maxOf(1, references)
            if (hasRestriction && buffering == target) return Result(nal, false, "$summary already bounded")
            val writer = Writer()
            writer.prefix(data, if (hasVui) restrictionPosition else vuiPosition)
            if (!hasVui) {
                writer.bit(true)
                repeat(8) { writer.bit(false) } // Absent optional VUI fields, through pic_struct_present_flag.
            }
            writer.bit(true) // bitstream_restriction_flag
            writer.bit(motion)
            limits.forEach(writer::ue)
            writer.ue(0)
            writer.ue(target)
            writer.bit(true)
            while (writer.count % 8 != 0) writer.bit(false)
            return Result(byteArrayOf(nal[0]) + escape(writer.bytes()), true, "$summary -> reorder=0 buffer=$target")
        } catch (_: IllegalArgumentException) {
            Result(nal, false, "unrecognized SPS; original preserved")
        } catch (_: IllegalStateException) {
            Result(nal, false, "unsupported SPS; original preserved")
        }
    }

    /** Parameter sets precede VCL data in an access unit; ordinary frames require no payload scan. */
    fun tuneAccessUnit(annexB: ByteArray): ByteArray {
        var start = findStart(annexB, 0)
        var copied = 0
        var output: ByteArrayOutputStream? = null
        while (start >= 0) {
            val prefix = if (annexB[start + 2] == 1.toByte()) 3 else 4
            val header = start + prefix
            if (header >= annexB.size) break
            val type = annexB[header].toInt() and 31
            if (type in 1..5) break
            val next = findStart(annexB, header + 1)
            val end = if (next < 0) annexB.size else next
            if (type == 7) {
                val result = tune(annexB.copyOfRange(header, end))
                if (result.changed) {
                    val target = output ?: ByteArrayOutputStream(annexB.size + 16).also { output = it }
                    target.write(annexB, copied, header - copied)
                    target.write(result.bytes)
                    copied = end
                }
            }
            start = next
        }
        return output?.apply { write(annexB, copied, annexB.size - copied) }?.toByteArray() ?: annexB
    }

    private fun findStart(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size - 2) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                (bytes[i + 2] == 1.toByte() || (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()))) return i
        }
        return -1
    }

    private fun unescape(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        var zeros = 0
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 255
            if (zeros >= 2 && value == 3) {
                require(i + 1 < bytes.size && bytes[i + 1].toInt() and 255 <= 3)
                zeros = 0
            } else {
                output.write(value)
                zeros = if (value == 0) zeros + 1 else 0
            }
        }
        return output.toByteArray()
    }

    private fun escape(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size + 8)
        var zeros = 0
        for (byte in bytes) {
            val value = byte.toInt() and 255
            if (zeros >= 2 && value <= 3) { output.write(3); zeros = 0 }
            output.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }

    private class Bits(private val bytes: ByteArray) {
        var position = 0
            private set
        fun read(count: Int): Int {
            require(count in 0..31 && position + count <= bytes.size * 8)
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        fun flag() = read(1) != 0
        fun skip(count: Int) { require(count >= 0 && position + count <= bytes.size * 8); position += count }
        fun ue(): Int {
            var zeros = 0
            while (!flag()) { zeros++; require(zeros <= 29) }
            return (1 shl zeros) - 1 + read(zeros)
        }
        fun se(): Int { val code = ue(); return if (code and 1 != 0) (code + 1) / 2 else -(code / 2) }
        fun hrd() {
            val count = ue().also { require(it <= 31) } + 1
            skip(8)
            repeat(count) { ue(); ue(); skip(1) }
            skip(20)
        }
    }

    private class Writer {
        private val output = ByteArrayOutputStream()
        var count = 0
            private set
        private var current = 0
        fun bit(value: Boolean) {
            current = (current shl 1) or if (value) 1 else 0
            count++
            if (count % 8 == 0) { output.write(current); current = 0 }
        }
        fun ue(value: Int) {
            require(value >= 0 && value < Int.MAX_VALUE)
            val number = value + 1
            val width = 32 - Integer.numberOfLeadingZeros(number)
            repeat(width - 1) { bit(false) }
            for (index in width - 1 downTo 0) bit(number and (1 shl index) != 0)
        }
        fun prefix(bytes: ByteArray, bits: Int) {
            for (index in 0 until bits) bit((bytes[index / 8].toInt() ushr (7 - index % 8)) and 1 != 0)
        }
        fun bytes(): ByteArray { check(count % 8 == 0); return output.toByteArray() }
    }
}
