package com.shilapi.xcertplay.media

import android.os.Process

/** Display work should run promptly while retaining lower priority than Android's audio threads. */
internal object VideoThreadPriority {
    fun apply() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        catch (_: RuntimeException) { /* Some OEM schedulers refuse priority changes. */ }
    }
}
