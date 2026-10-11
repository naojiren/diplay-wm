package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyNcmClaimTest {
    private fun eligible(api: Int = 23, board: String = "8227L_demo", hardware: String = "ac8227l",
                         abi: String = "armeabi-v7a", vendor: Int = 0x05ac,
                         controlClass: Int = 2, subclass: Int = 13, dataClass: Int = 10) =
        LegacyNcmClaim.eligible(api, board, hardware, abi, vendor, controlClass, subclass, dataClass)

    @Test fun gateRequiresTheKnownLegacyDeviceAndNcmFunction() {
        assertTrue(eligible())
        assertTrue(eligible(board = "unknown", hardware = "mt8127"))
        assertFalse(eligible(api = 22))
        assertFalse(eligible(api = 24))
        assertFalse(eligible(api = 37))
        assertFalse(eligible(board = "unknown", hardware = "qcom"))
        assertFalse(eligible(abi = "arm64-v8a"))
        assertFalse(eligible(vendor = 0x1234))
        assertFalse(eligible(controlClass = 255))
        assertFalse(eligible(subclass = 6))
        assertFalse(eligible(dataClass = 255))
    }

    private class Fake : LegacyNcmClaim.Native {
        val calls = mutableListOf<String>()
        var claimErrno = 16
        var driver = LegacyNcmClaim.Driver(0, "cdc_ncm")
        var atomicErrno = 0
        var standard = false
        var verify = true
        var owned = false
        val logs = mutableListOf<String>()
        override fun claim(): Int { calls += "claim"; return claimErrno }
        override fun driver(): LegacyNcmClaim.Driver { calls += "driver"; return driver }
        override fun disconnectClaim(driver: String): Int { calls += "atomic:$driver"; return atomicErrno }
        override fun restore() { calls += "restore" }
        fun run(eligible: Boolean = true, available: Boolean = true): Boolean = LegacyNcmClaim.claim(
            eligible,
            framework = { force ->
                calls += "framework:$force"
                if (!force) assertTrue("Native ownership must be tracked before verification", owned)
                if (force) standard else verify
            },
            native = { calls += "load"; if (available) this else null },
            owned = { assertFalse(owned); owned = true; calls += "owned" },
            log = { logs += it },
        )
    }

    @Test fun successfulFrameworkPathNeverLoadsNativeEvenOnMtk() {
        val fake = Fake().apply { standard = true }
        assertTrue(fake.run())
        assertEquals(listOf("framework:true", "owned"), fake.calls)
    }

    @Test fun failedModernFrameworkPathNeverLoadsNative() {
        val fake = Fake()
        assertFalse(fake.run(eligible = false))
        assertEquals(listOf("framework:true"), fake.calls)
        assertFalse(fake.owned)
    }

    @Test fun unavailableNativeLibraryFailsClosed() {
        val fake = Fake()
        assertFalse(fake.run(available = false))
        assertEquals(listOf("framework:true", "load"), fake.calls)
    }

    @Test fun oneNonForcingNativeRetryCanResolveTransientBusy() {
        val fake = Fake().apply { claimErrno = 0 }
        assertTrue(fake.run())
        assertEquals(listOf("framework:true", "load", "claim", "owned", "framework:false"), fake.calls)
    }

    @Test fun onlyBusyCanLeadToDetach() {
        for (errno in listOf(1, 5, 9, 13, 19, 22, 25, 110)) {
            val fake = Fake().apply { claimErrno = errno }
            assertFalse(fake.run())
            assertEquals(listOf("framework:true", "load", "claim"), fake.calls)
            assertFalse(fake.owned)
        }
    }

    @Test fun refusesUsbfsUnknownDriversAndFailedDriverQueries() {
        for (driver in listOf(LegacyNcmClaim.Driver(0, "usbfs"), LegacyNcmClaim.Driver(0, "vendor_net"),
                              LegacyNcmClaim.Driver(61, ""), LegacyNcmClaim.Driver(13, "cdc_ncm"))) {
            val fake = Fake().apply { this.driver = driver }
            assertFalse(fake.run())
            assertEquals(listOf("framework:true", "load", "claim", "driver"), fake.calls)
            assertFalse(fake.owned)
        }
    }

    @Test fun knownNetworkDriverUsesOneNameGuardedAtomicAttemptThenVerifies() {
        for (name in listOf("cdc_ncm", "cdc_ether")) {
            val fake = Fake().apply { driver = LegacyNcmClaim.Driver(0, name) }
            assertTrue(fake.run())
            assertEquals(listOf("framework:true", "load", "claim", "driver", "atomic:$name", "owned", "framework:false"), fake.calls)
            assertTrue(fake.logs.any { "GETDRIVER errno=0 driver=$name" in it })
            assertTrue(fake.logs.any { "DISCONNECT_CLAIM driver=$name errno=0" in it })
        }
    }

    @Test fun unsupportedDeniedOrRacedAtomicIoctlRestoresAndStopsWithoutRetryLoop() {
        for (errno in listOf(1, 13, 16, 19, 22, 25)) {
            val fake = Fake().apply { atomicErrno = errno }
            assertFalse(fake.run())
            assertEquals(listOf("framework:true", "load", "claim", "driver", "atomic:cdc_ncm", "restore"), fake.calls)
            assertFalse(fake.owned)
        }
    }

    @Test fun failedVerificationStillTracksClaimForCallerCleanup() {
        val fake = Fake().apply { verify = false }
        assertFalse(fake.run())
        assertTrue(fake.owned)
        assertEquals("framework:false", fake.calls.last())
    }

    @Test fun throwingVerificationStillTracksClaimForCallerCleanup() {
        val fake = Fake()
        var owned = false
        try {
            LegacyNcmClaim.claim(true, { force -> if (force) false else error("detach") },
                { fake }, { owned = true }, {})
            fail("Expected verification failure")
        } catch (_: IllegalStateException) {
            assertTrue(owned)
        }
    }

    @Test fun failedAtomicOperationRestoresEvenWhenLoggingThrows() {
        val fake = Fake().apply { atomicErrno = 16 }
        try {
            LegacyNcmClaim.claim(true, { false }, { fake }, { fail("No ownership") },
                { if ("DISCONNECT_CLAIM" in it) error("log sink failed") })
            fail("Expected logging failure")
        } catch (_: IllegalStateException) {
            assertEquals("restore", fake.calls.last())
        }
    }
}
