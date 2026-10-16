package com.shilapi.xcertplay.media

import android.os.Process

/** Audio reception and playback must not sit below the two display workers on an old head unit. */
internal object AudioThreadPriority {
    fun apply(
        setPriority: (Int) -> Unit = { Process.setThreadPriority(it) },
        readPriority: () -> Int = { Process.getThreadPriority(Process.myTid()) },
    ): Int? {
        for (priority in intArrayOf(Process.THREAD_PRIORITY_AUDIO, Process.THREAD_PRIORITY_DISPLAY)) {
            try {
                setPriority(priority)
                break
            } catch (_: RuntimeException) { /* Fall back when the ROM restricts audio priority. */ }
        }
        // Report the actual priority. A denied scheduling hint must never stop playback.
        return runCatching { readPriority() }.getOrNull()
    }
}
