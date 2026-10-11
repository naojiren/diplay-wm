package com.shilapi.xcertplay.transport

/** Decision logic only; JVM tests cannot establish vendor-kernel USB ownership. */
internal object LegacyNcmClaim {
    data class Driver(val errno: Int, val name: String)

    interface Native {
        fun claim(): Int
        fun driver(): Driver
        fun disconnectClaim(driver: String): Int
        /** Restore only unbound members of this NCM pair after a failed detach-and-claim. */
        fun restore()
    }

    fun eligible(api: Int, board: String, hardware: String, abi: String, vendor: Int,
                 controlClass: Int, controlSubclass: Int, dataClass: Int): Boolean =
        api == 23 && abi == "armeabi-v7a" && vendor == 0x05ac &&
            controlClass == 0x02 && controlSubclass == 0x0d && dataClass == 0x0a &&
            listOf(board, hardware).any {
                Regex("(?i)(?:ac)?8227l|mediatek|(?:^|[^a-z0-9])mt[0-9]{4}(?:[^0-9]|$)").containsMatchIn(it)
            }

    fun claim(
        eligible: Boolean,
        framework: (force: Boolean) -> Boolean,
        native: () -> Native?,
        owned: () -> Unit,
        log: (String) -> Unit,
    ): Boolean {
        val standard = framework(true)
        // Register ownership before logging/verification so the caller can always unwind it.
        if (standard) owned()
        log("framework force=true ok=$standard")
        if (standard) return true
        if (!eligible) {
            log("legacy fallback skipped: device outside API23/ARMv7/MTK/iPhone NCM gate")
            return false
        }
        val ops = native() ?: return false
        val claimErrno = ops.claim()
        if (claimErrno == 0) owned()
        log("native CLAIMINTERFACE errno=$claimErrno")
        if (claimErrno == 0) return verify(framework, log)
        // Permission, disconnect and descriptor errors must not trigger driver detachment.
        if (claimErrno != 16) return false // EBUSY
        val driver = ops.driver()
        log("native GETDRIVER errno=${driver.errno} driver=${driver.name}")
        // Never detach usbfs (another app), or an unknown vendor driver.
        if (driver.errno != 0 || driver.name !in setOf("cdc_ncm", "cdc_ether")) return false
        val atomicErrno = ops.disconnectClaim(driver.name)
        if (atomicErrno == 0) owned()
        if (atomicErrno != 0) {
            // In mainline, detachment can succeed even when the subsequent claim fails.
            try {
                log("native DISCONNECT_CLAIM driver=${driver.name} errno=$atomicErrno")
            } finally {
                ops.restore()
            }
            return false
        }
        log("native DISCONNECT_CLAIM driver=${driver.name} errno=$atomicErrno")
        return verify(framework, log)
    }

    private fun verify(framework: (Boolean) -> Boolean, log: (String) -> Unit): Boolean {
        // CLAIMINTERFACE is idempotent for the same usbfs open-file state. Never force again.
        val verified = framework(false)
        log("framework verify force=false ok=$verified")
        return verified
    }
}
