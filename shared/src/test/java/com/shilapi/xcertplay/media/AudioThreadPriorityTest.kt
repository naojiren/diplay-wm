package com.shilapi.xcertplay.media

import android.os.Process
import org.junit.Assert.*
import org.junit.Test

class AudioThreadPriorityTest {
    @Test fun successfulAudioPriorityIsNotOverwrittenByFallback() {
        val requested = mutableListOf<Int>()
        assertEquals(Process.THREAD_PRIORITY_AUDIO, AudioThreadPriority.apply(
            setPriority = { requested += it }, readPriority = { requested.last() },
        ))
        assertEquals(listOf(Process.THREAD_PRIORITY_AUDIO), requested)
    }

    @Test fun restrictedRomFallsBackToDisplayPriority() {
        val requested = mutableListOf<Int>()
        val actual = AudioThreadPriority.apply(
            setPriority = { requested += it; if (it == Process.THREAD_PRIORITY_AUDIO) throw SecurityException() },
            readPriority = { Process.THREAD_PRIORITY_DISPLAY },
        )
        assertEquals(listOf(Process.THREAD_PRIORITY_AUDIO, Process.THREAD_PRIORITY_DISPLAY), requested)
        assertEquals(Process.THREAD_PRIORITY_DISPLAY, actual)
    }

    @Test fun deniedHintsAndUnavailableDiagnosticsDoNotBreakSound() {
        assertNull(AudioThreadPriority.apply(
            setPriority = { throw SecurityException() }, readPriority = { throw IllegalArgumentException() },
        ))
    }
}
