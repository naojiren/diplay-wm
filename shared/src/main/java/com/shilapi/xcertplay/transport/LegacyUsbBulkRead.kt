package com.shilapi.xcertplay.transport

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
import java.nio.ByteBuffer

/** The EX5 API 23 host rejects async URBs; usbfs bulk reads worked in the on-car probe. */
internal object LegacyUsbBulkRead {
    const val READ_BYTES = 4096
    private const val MAX_WAIT_MILLIS = 1000

    fun enabled(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        context.packageName == "com.shihab.diplay.wmex5" && sdk in 19..25

    /** Direct memory avoids holding a Java byte[] in a JNI critical section during the wait. */
    fun readInto(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        buffer: ByteBuffer,
        destination: ByteArray,
        timeoutMillis: Long,
        transfer: (Int, Int, ByteBuffer, Int, Int) -> Int = { fd, ep, bytes, size, timeout ->
            NativeUsbBulkRead.read(fd, ep, bytes, size, timeout)
        },
    ): Int? {
        require(timeoutMillis > 0)
        require(buffer.isDirect && !buffer.isReadOnly)
        val count = minOf(READ_BYTES, buffer.capacity(), destination.size)
        require(count > 0)
        buffer.clear()
        buffer.limit(count)
        val received = transfer(connection.fileDescriptor, endpoint.address, buffer, count,
            timeoutMillis.coerceIn(1, MAX_WAIT_MILLIS.toLong()).toInt())
        if (received == 0 || received == -110 || received == -11) return null
        if (received < 0) throw IphoneUsbException.DeviceUnavailable(
            "Native USB bulk read failed endpoint=0x${endpoint.address.toString(16)} errno=${-received}",
        )
        if (received > count) throw IphoneUsbException.DeviceUnavailable("Native USB read exceeded its buffer")
        buffer.position(0)
        buffer.limit(received)
        buffer.get(destination, 0, received)
        return received
    }
}

private object NativeUsbBulkRead {
    init { System.loadLibrary("usb_host_compat") }
    external fun read(fd: Int, endpoint: Int, buffer: ByteBuffer, length: Int, timeoutMillis: Int): Int
}
