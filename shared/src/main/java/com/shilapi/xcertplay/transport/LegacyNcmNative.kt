package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection

/** Borrow the framework fd synchronously before any NCM workers start; never open/close/dup it. */
internal object LegacyNcmNative {
    init { System.loadLibrary("legacy_ncm_claim") }
    private external fun claim(fd: Int, interfaceId: Int): Int
    private external fun driver(fd: Int, interfaceId: Int): String
    private external fun disconnectClaim(fd: Int, interfaceId: Int, driver: String): Int
    private external fun reconnectIfUnbound(fd: Int, interfaceId: Int): Int

    fun operations(connection: UsbDeviceConnection, interfaceId: Int, pair: List<Int>, configurationId: Int,
                   log: (String) -> Unit): LegacyNcmClaim.Native? {
        val fd = connection.fileDescriptor
        if (fd < 0) {
            log("native unavailable: framework fd is closed")
            return null
        }
        // Descriptors can survive a configuration transition; never detach against stale objects.
        val active = ByteArray(1)
        val length = connection.controlTransfer(0x80, 8, 0, 0, active, 1, 1_000) // GET_CONFIGURATION
        val actual = active[0].toInt() and 0xff
        log("legacy GET_CONFIGURATION length=$length active=$actual expected=$configurationId")
        if (length != 1 || actual != configurationId) return null
        return object : LegacyNcmClaim.Native {
            override fun claim() = claim(fd, interfaceId)
            override fun driver(): LegacyNcmClaim.Driver {
                // JNI returns errno:name; driver names have no colon in the allowlist.
                val value = driver(fd, interfaceId)
                return LegacyNcmClaim.Driver(value.substringBefore(':').toInt(), value.substringAfter(':'))
            }
            override fun disconnectClaim(driver: String) = disconnectClaim(fd, interfaceId, driver)
            override fun restore() {
                for (id in pair.distinct()) {
                    log("native restore CONNECT iface=$id errno=${reconnectIfUnbound(fd, id)}")
                }
            }
        }
    }
}
