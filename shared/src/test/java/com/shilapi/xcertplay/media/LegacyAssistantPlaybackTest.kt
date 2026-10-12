package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.os.Build
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 25, 26], manifest = Config.NONE)
class LegacyAssistantPlaybackTest {
    @Test fun siriUsesASupportedUsageWhileMusicAndCallsKeepTheirRouting() {
        for ((type, usage) in listOf(
            "speechrecognition" to if (Build.VERSION.SDK_INT < 26) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANT,
            "media" to AudioAttributes.USAGE_MEDIA,
            "telephony" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
        )) {
            val lines = CopyOnWriteArrayList<String>()
            val ready = CountDownLatch(1)
            val sink = AndroidMediaSink(context = RuntimeEnvironment.getApplication(),
                onAudioDiagnostic = { line ->
                    lines += line
                    if (line.startsWith("Audio: ready")) ready.countDown()
                })
            val id = AudioStreamId(100, type)
            try {
                sink.onAudioStarted(id, AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, type), 0)
                assertTrue(lines.toString(), ready.await(5, TimeUnit.SECONDS))
                assertTrue(lines.toString(), lines.any { it.startsWith("Audio: ready") && it.contains("usage=$usage ") })
            } finally { sink.onAudioStopped(id); sink.close() }
        }
    }
}
