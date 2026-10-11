package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.security.Security
import javax.crypto.Cipher

class VideoFrameDecryptorTest {
    private val key = ByteArray(32) { it.toByte() }
    private val header = ByteArray(128) { (it * 3).toByte() }
    private fun accelerated() = VideoFrameDecryptor(key) { Cipher.getInstance("ChaCha20-Poly1305") }

    @Test fun reusedCipherDecryptsChangingNoncesAndFrameSizes() {
        val opener = accelerated()
        val providers = Security.getProviders().map { it.name }
        for ((counter, size) in listOf(0, 1, 63, 64, 65, 1400, 65536, 524288).withIndex()) {
            val plain = ByteArray(size) { (it * 11).toByte() }
            val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, AirPlayCrypto.nonce64(counter.toLong()), plain, header)
            assertArrayEquals(plain, opener.open(counter.toLong(), header, sealed))
        }
        assertTrue(opener.implementation.startsWith("bundled-"))
        assertEquals(providers, Security.getProviders().map { it.name })
    }

    @Test fun alteredTagHeaderAndCounterAreRejectedAndNextGoodFrameStillWorks() {
        for (native in listOf(true, false)) {
            val opener = if (native) accelerated() else VideoFrameDecryptor(key) { null }
            val plain = ByteArray(200) { it.toByte() }
            val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, AirPlayCrypto.nonce64(2), plain, header)
            val badTag = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val badHeader = header.copyOf().also { it[17] = (it[17].toInt() xor 1).toByte() }
            assertTrue(runCatching { opener.open(2, header, badTag) }.isFailure)
            assertTrue(runCatching { opener.open(2, badHeader, sealed) }.isFailure)
            assertTrue(runCatching { opener.open(3, header, sealed) }.isFailure)
            assertArrayEquals(plain, opener.open(2, header, sealed))
        }
    }

    @Test fun unavailableNativeLibraryUsesTheAuthenticatedExistingPath() {
        val opener = VideoFrameDecryptor(key) { throw UnsatisfiedLinkError("test ABI unavailable") }
        val plain = ByteArray(1400) { (it * 7).toByte() }
        val sealed = AirPlayCrypto.chachaSealBouncyCastle(key, AirPlayCrypto.nonce64(7), plain, header)
        assertArrayEquals(plain, opener.open(7, header, sealed))
    }
}
