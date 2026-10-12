package com.shilapi.xcertplay

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.SurfaceView
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.LocalMfiProbe
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises installed APK assets, connection UI and real AudioTrack on an Android runtime. */
class WmAuthenticationInstrumentation : Instrumentation() {
    private var missingVpn = false
    private var perf13Video = false
    private var perf14Wifi = false
    private var perf20Audio = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        missingVpn = arguments?.getString("missing-vpn") == "true"
        perf13Video = arguments?.getString("perf13-video") == "true"
        perf14Wifi = arguments?.getString("perf14-wifi") == "true"
        perf20Audio = arguments?.getString("perf20-audio") == "true"
        androidx.multidex.MultiDex.installInstrumentation(context, targetContext)
        start()
    }

    override fun onStart() {
        var passed = 0
        try {
            if (perf20Audio) {
                check(AirPlayPersistence.loadMediaBufferMillis(targetContext) == 150)
                softwareMicrophoneEncodingUsesAndroidSixApis()
                lowLatencyAudioPlaybackUsesAndroidSixApis()
                finish(Activity.RESULT_OK, Bundle().apply {
                    putString("stream", "PERF20 short media buffer and preserved software Opus PASS API=${Build.VERSION.SDK_INT}\n")
                })
                return
            }
            if (perf13Video || perf14Wifi) {
                optimizedDefaultsAreApplied()
                softwareMicrophoneEncodingUsesAndroidSixApis()
                if (perf14Wifi) interfaceBonjourUsesAndroidSixApis()
                nativeVideoCrypto()
                audioPlaybackUsesAndroidSixApis()
                installedAuthenticationLoadsAndSigns()
                finish(Activity.RESULT_OK, Bundle().apply {
                    val label = if (perf14Wifi) "PERF16 occupied-port IPv4 Bonjour, native video, AudioTrack and authentication" else "PERF13 native video, AudioTrack and authentication"
                    putString("stream", "$label PASS API=${Build.VERSION.SDK_INT}\n")
                })
                return
            }
            defaultSettingsAndWirelessModes()
            passed++
            status("PASS EX5 defaults: 18 fps, H.264, enabled instrument and same-Wi-Fi selection")
            instrumentProjectionUsesReducedBuffersAndCenteredLayouts()
            passed++
            status("PASS API 23 instrument: 768x192 buffer, 18 fps, centered fit and centered reduced image")
            installedAuthenticationLoadsAndSigns()
            passed++
            status("PASS installed authentication: reload, six signatures and modified-challenge rejection")
            audioPlaybackUsesAndroidSixApis()
            passed++
            status("PASS Android AudioTrack initialization, PCM playback and audio focus")
            connectionButtonsWork()
            passed++
            status("PASS enabled connect-phone button and USB button opens connection activity")
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "WM runtime PASS tests=$passed failures=0 API=${Build.VERSION.SDK_INT}\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", "WM runtime FAILED passed=$passed\n${android.util.Log.getStackTraceString(error)}")
            })
        }
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun nativeVideoCrypto() {
        val providers = java.security.Security.getProviders().map { it.name }
        val key = ByteArray(32) { it.toByte() }
        val header = ByteArray(128) { (it * 5).toByte() }
        val fast = com.shilapi.xcertplay.airplay.VideoFrameDecryptor(key) {
            com.shilapi.xcertplay.airplay.VideoFrameDecryptor.bundledCipher()
        }
        check(fast.implementation.startsWith("bundled-")) { "Bundled native video provider not loaded" }
        for ((counter, size) in listOf(0, 1, 63, 64, 65, 1400, 65536, 524288).withIndex()) {
            val plain = ByteArray(size) { (it * 7).toByte() }
            val nonce = com.shilapi.xcertplay.airplay.AirPlayCrypto.nonce64(counter.toLong())
            val sealed = com.shilapi.xcertplay.airplay.AirPlayCrypto.chachaSeal(key, nonce, plain, header)
            check(fast.open(counter.toLong(), header, sealed).contentEquals(plain))
            val badTag = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            check(runCatching { fast.open(counter.toLong(), header, badTag) }.isFailure)
            check(fast.open(counter.toLong(), header, sealed).contentEquals(plain))
        }
        val plain = ByteArray(65536) { (it * 3).toByte() }
        val sealed = com.shilapi.xcertplay.airplay.AirPlayCrypto.chachaSeal(key,
            com.shilapi.xcertplay.airplay.AirPlayCrypto.nonce64(100), plain, header)
        val baseline = com.shilapi.xcertplay.airplay.VideoFrameDecryptor(key) { null }
        repeat(5) { fast.open(100, header, sealed); baseline.open(100, header, sealed) }
        fun elapsed(opener: com.shilapi.xcertplay.airplay.VideoFrameDecryptor): Long {
            val start = System.nanoTime()
            repeat(24) { check(opener.open(100, header, sealed).contentEquals(plain)) }
            return (System.nanoTime() - start) / 1000 / 24
        }
        val nativeUs = elapsed(fast)
        val baselineUs = elapsed(baseline)
        check(providers == java.security.Security.getProviders().map { it.name })
        status("PERF13 video crypto native=${fast.implementation} nativeUs=$nativeUs baseline=${baseline.implementation} baselineUs=$baselineUs bytes=65536 samples=24 tamperRejected=true providersUnchanged=true")
    }

    private fun interfaceBonjourUsesAndroidSixApis() {
        val cacheType = Class.forName("javax.jmdns.impl.DNSCache", true, targetContext.classLoader)
        val cache = cacheType.getConstructor().newInstance()
        val entries = cacheType.getMethod("allValues").invoke(cache)
        check(entries != null) { "DNS cache enumeration failed on Android 6" }
        status("PERF14 DNS cache enumeration PASS")
        val address = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .first { it is java.net.Inet4Address && !it.isLoopbackAddress }
        val config = com.shilapi.xcertplay.airplay.AirPlayConfig(
            deviceName = "DiPlay runtime test", deviceId = "02:00:00:00:00:14",
            btMac = "02:00:00:00:00:14", sourceVersion = "366.0",
            main = com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(widthPixels = 540, heightPixels = 960))
        val identity = com.shilapi.xcertplay.airplay.AirPlayIdentity(ByteArray(32), ByteArray(32), "runtime-test")
        val ipv6 = java.net.Inet6Address.getByAddress(null, java.net.InetAddress.getByName("fe80::15").address, 7)
        // A live system discovery service also uses a reusable UDP 5353 socket. Keep it open
        // throughout the test: merely restarting the app on an empty port missed this failure.
        val existing = java.net.MulticastSocket(null as java.net.SocketAddress?).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(5353))
        }
        try {
            var originalConstructorFailed = false
            try { java.net.MulticastSocket(java.net.InetSocketAddress(5353)).close() }
            catch (_: java.net.BindException) { originalConstructorFailed = true }
            check(originalConstructorFailed) { "Expected the original Android 6 bound constructor to fail on the occupied port" }
            status("PERF16 original Android 6 constructor EADDRINUSE reproduced with existing UDP 5353 socket")
            repeat(3) { iteration ->
                val bonjour = com.shilapi.xcertplay.network.CarPlayBonjour(
                    targetContext, config, identity, address.hostAddress, true,
                    additionalAddresses = listOf(ipv6), ipv4Only = true)
                try {
                    bonjour.start()
                    bonjour.start()
                    check(bonjour.diagnosticSnapshot().contains("mdnsFamilies=IPv4"))
                    val registries = bonjour.javaClass.getDeclaredField("interfaceMdns").apply { isAccessible = true }
                        .get(bonjour) as List<*>
                    check(registries.size == 1)
                    val registryType = Class.forName("javax.jmdns.JmDNS", true, targetContext.classLoader)
                    check(registryType.getMethod("getInetAddress").invoke(registries.single()) == address)
                    val socket = registries.single()!!.javaClass.getMethod("getSocket").invoke(registries.single()) as java.net.MulticastSocket
                    check(socket.reuseAddress && socket.localPort == 5353)
                    check(socket.javaClass.simpleName == "SharedMdnsSocket")
                    check(!existing.isClosed)
                    status("PERF16 occupied-port Android 6 IPv4-only JmDNS started iteration=${iteration + 1} registries=1 IPv6=disabled sharedPort=5353")
                } finally { bonjour.close(); bonjour.close() }
                check(bonjour.diagnosticSnapshot().contains("mdnsFamilies=none"))
                check(!existing.isClosed)
                status("PERF16 occupied-port Android 6 IPv4-only JmDNS cleanup PASS iteration=${iteration + 1}")
            }
        } finally { existing.close() }
    }

    private fun wmCluster(method: String, side: String? = null): Any? {
        val type = Class.forName("com.shilapi.xcertplay.WmEx5ClusterProfile")
        val instance = type.getField("INSTANCE").get(null)
        return if (side == null) type.getMethod(method, Context::class.java).invoke(instance, targetContext)
            else type.getMethod(method, Context::class.java, String::class.java).invoke(instance, targetContext, side)
    }

    private fun wmChannel(channel: Int) {
        val type = Class.forName("com.shilapi.xcertplay.WmEx5PerformanceProfile")
        type.getMethod("saveChannel", Context::class.java, Int::class.javaPrimitiveType)
            .invoke(type.getField("INSTANCE").get(null), targetContext, channel)
    }

    private fun status(text: String) = sendStatus(0, Bundle().apply { putString("stream", "$text\n") })

    private fun defaultSettingsAndWirelessModes() {
        val activity = startActivitySync(Intent(targetContext, DiPlayActivity::class.java)
            .putExtra("page", "connection").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            waitForIdleSync()
            check(AirPlayPersistence.loadFps(targetContext) == 18)
            check(AirPlayPersistence.loadDisplayScalePercent(targetContext) == 50)
            check(!AirPlayPersistence.loadHevcEnabled(targetContext))
            check(wmCluster("enabled") as Boolean && AirPlayPersistence.loadClusterMapEnabled(targetContext))
            check(wmCluster("margin", "left") == 24)
            val sameWifi = activity.getString(activity.resources.getIdentifier("existing_wifi_title", "string", targetContext.packageName))
            check(texts(activity.window.decorView).any { it.text.toString().contains(sameWifi) }) { "Same-Wi-Fi option absent" }
            AirPlayPersistence.saveWirelessHotspotMode(targetContext, WirelessHotspotMode.EXISTING_WIFI)
            check(AirPlayPersistence.loadWirelessHotspotMode(targetContext) == WirelessHotspotMode.EXISTING_WIFI)
            wmChannel(1)
            check(AirPlayPersistence.loadWifiP2pPreferredChannel(targetContext) == 1)
            AirPlayPersistence.saveWirelessHotspotMode(targetContext, WirelessHotspotMode.WIFI_P2P)
            wmChannel(36)
            for (fps in listOf(12, 18, 24, 30, 18)) {
                AirPlayPersistence.saveFps(targetContext, fps)
                check(AirPlayPersistence.loadFps(targetContext) == fps)
            }
        } finally {
            main { activity.finish() }
            waitForIdleSync()
        }
    }

    private fun instrumentProjectionUsesReducedBuffersAndCenteredLayouts() {
        val activity = startActivitySync(Intent(targetContext, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val profile = Class.forName("com.shilapi.xcertplay.WmEx5ClusterProfile")
        val instance = profile.getField("INSTANCE").get(null)
        val prefs = targetContext.getSharedPreferences("diplay_wm_ex5_cluster", 0)
        val savedLeft = prefs.getInt("left", 0)
        val nativeMethod = profile.getMethod("setNativeSize", Context::class.java, Boolean::class.javaPrimitiveType)
        val surfaceTexture = android.graphics.SurfaceTexture(0)
        val displaySurface = android.view.Surface(surfaceTexture)
        var virtual: android.hardware.display.VirtualDisplay? = null
        var presentation: android.app.Presentation? = null
        try {
            prefs.edit().putInt("resolution_percent", 40).commit()
            check(wmCluster("resolutionPercent") == 40 && wmCluster("fps") == 18)
            check(wmCluster("nativeSize") == false)
            main {
                prefs.edit().putInt("left", 0).commit()
                val manager = targetContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                virtual = manager.createVirtualDisplay("WM instrument runtime test", 1920, 480, 160, displaySurface,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                check(virtual != null)
                val config = profile.getMethod("streamConfig", Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(instance, targetContext, 1920, 480) as com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
                check(config.widthPixels == 768 && config.heightPixels == 192 && config.fps == 18)
                val type = Class.forName("com.shilapi.xcertplay.ClusterMapPresentation")
                val theme = Class.forName("com.shilapi.xcertplay.DiLink51ClusterLayout\$Theme")
                val constructor = type.getDeclaredConstructor(Context::class.java, android.view.Display::class.java,
                    theme, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
                fun show(native: Boolean) {
                    nativeMethod.invoke(instance, targetContext, native)
                    presentation = constructor.newInstance(activity, virtual!!.display, theme.enumConstants.first(),
                        { _: android.view.Surface? -> Unit }) as android.app.Presentation
                    presentation!!.show()
                    val video = type.getDeclaredField("videoView").apply { isAccessible = true }.get(presentation) as SurfaceView
                    val params = video.layoutParams as android.widget.FrameLayout.LayoutParams
                    if (native) check(params.width == 768 && params.height == 192 && params.leftMargin == 576 && params.topMargin == 144)
                    else check(params.width == 1920 && params.height == 480 && params.leftMargin == 0 && params.topMargin == 0)
                }
                show(false)
            }
            waitForIdleSync()
            android.os.SystemClock.sleep(500)
            main {
                val type = presentation!!.javaClass
                val video = type.getDeclaredField("videoView").apply { isAccessible = true }.get(presentation) as SurfaceView
                check(video.holder.surface.isValid)
                // Android 6 surfaceFrame describes the window; query the real buffer instead.
                val canvas = video.holder.lockCanvas()
                try {
                    check(canvas != null && canvas.width == 768 && canvas.height == 192) {
                        "Unexpected instrument buffer: ${canvas?.width}x${canvas?.height}"
                    }
                } finally { if (canvas != null) video.holder.unlockCanvasAndPost(canvas) }
                presentation!!.dismiss()
                nativeMethod.invoke(instance, targetContext, true)
                val theme = Class.forName("com.shilapi.xcertplay.DiLink51ClusterLayout\$Theme")
                presentation = type.getDeclaredConstructor(Context::class.java, android.view.Display::class.java,
                    theme, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
                    .newInstance(activity, virtual!!.display, theme.enumConstants.first(), { _: android.view.Surface? -> Unit }) as android.app.Presentation
                presentation!!.show()
                val nativeVideo = type.getDeclaredField("videoView").apply { isAccessible = true }.get(presentation) as SurfaceView
                val params = nativeVideo.layoutParams as android.widget.FrameLayout.LayoutParams
                check(params.width == 768 && params.height == 192 && params.leftMargin == 576 && params.topMargin == 144)
            }
        } finally {
            main {
                presentation?.dismiss()
                virtual?.release()
                nativeMethod.invoke(instance, targetContext, false)
                prefs.edit().putInt("left", savedLeft).commit()
                activity.finish()
            }
            displaySurface.release()
            surfaceTexture.release()
            waitForIdleSync()
        }
    }

    private fun installedAuthenticationLoadsAndSigns() {
        val bootstrap = Class.forName("com.shilapi.xcertplay.DiPlayBootstrap")
        bootstrap.getMethod("ensure", Context::class.java, MfiTarget::class.java)
            .invoke(bootstrap.getField("INSTANCE").get(null), targetContext, MfiTarget.LOCAL)
        val root = if (Build.VERSION.SDK_INT >= 21) targetContext.noBackupFilesDir else targetContext.filesDir
        val installed = File(root, "offline-mfi")
        for (name in listOf("identity.pk8", "certificate.p7b")) {
            val bundled = targetContext.assets.open("offline-mfi/$name").use { it.readBytes() }
            check(bundled.contentEquals(File(installed, name).readBytes())) { "Installed authentication differs from bundled input" }
        }
        LocalMfiProbe.main(arrayOf(installed.absolutePath))
    }

    private fun audioPlaybackUsesAndroidSixApis() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        val started = CountDownLatch(1)
        val sink = AndroidMediaSink(context = targetContext, audioFocusEnabled = true, mediaBufferMillis = 60,
            microphoneCaptureEnabled = false,
            onAudioDiagnostic = { line ->
                lines += line
                if (line.startsWith("Audio: ready")) ready.countDown()
                // A headless emulator has no physical audio clock; require successful play/write.
                if (line.startsWith("audio stats") && line.contains("playState=3 ") &&
                    line.contains("playing=true ")) started.countDown()
            })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96)
        try {
            val audioManager = targetContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val previousMode = audioManager.mode
            for (type in listOf("speechrecognition", "telephony")) {
                sink.onMicrophoneStarted(AudioStreamId(100, type), com.shilapi.xcertplay.airplay.MicrophoneConfig(
                    audioType = type, sampleRate = 48000, channels = 1, payloadType = 96,
                    frameMillis = 20, host = java.net.InetAddress.getByName("127.0.0.1"),
                    port = 19008, key = ByteArray(32)))
            }
            check(lines.count { it.contains("head-unit capture disabled") } == 2)
            check(audioManager.mode == previousMode) { "Capture experiment changed the Android audio mode" }
            status("PERF18 Siri and call head-unit capture disabled without changing Android audio mode")
            sink.onAudioStarted(id, format, 0)
            check(ready.await(5, TimeUnit.SECONDS)) { "Audio initialization: $lines" }
            repeat(40) { packet ->
                sink.onAudioRtp(id, format, ByteArray(12 + 1920).apply { this[0] = 0x80.toByte() }, packet * 480)
            }
            check(started.await(8, TimeUnit.SECONDS)) { "AudioTrack did not enter playback: $lines" }
            check(lines.any { it.startsWith("Audio: first PCM write") }) { "No successful PCM write: $lines" }
            check(lines.any { it.startsWith("Audio: focus requested") }) { "Audio focus was not requested: $lines" }
            check(lines.none { it.contains("renderer failed") }) { "Audio renderer failed: $lines" }
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            sink.onMediaAudioFocusChanged(android.media.AudioManager.AUDIOFOCUS_GAIN)
            status("PERF18 AudioTrack playback passed with head-unit microphone capture disabled API=${Build.VERSION.SDK_INT}")
        } finally { sink.onAudioStopped(id); sink.close() }
    }

    private fun lowLatencyAudioPlaybackUsesAndroidSixApis() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val sink = AndroidMediaSink(context = targetContext, audioFocusEnabled = false, mediaBufferMillis = 150,
            onAudioDiagnostic = { line ->
                lines += line
                if (line.startsWith("Audio: ready")) ready.countDown()
                if (line.startsWith("Audio: first play") && line.contains("mediaBufferSettingMs=150 ") &&
                    line.contains("queueLimitMs=300")) playing.countDown()
            })
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 96)
        try {
            sink.onAudioStarted(id, format, 0)
            check(ready.await(5, TimeUnit.SECONDS)) { "Short AudioTrack initialization: $lines" }
            repeat(18) { packet ->
                sink.onAudioRtp(id, format, ByteArray(12 + 1920).apply { this[0] = 0x80.toByte() }, packet * 480)
            }
            check(playing.await(5, TimeUnit.SECONDS)) { "150 ms media did not start: $lines" }
            check(lines.any { it.startsWith("Audio: first PCM write") })
            check(lines.none { it.contains("renderer failed") })
            status("PERF20 Android 6 media playback: 150 ms setting, 300 ms packet limit, PCM write/play passed")
        } finally { sink.onAudioStopped(id); sink.close() }
    }

    private fun optimizedDefaultsAreApplied() {
        check(AirPlayPersistence.loadFps(targetContext) == 18)
        check(wmCluster("fps") == 18)
        check(AirPlayPersistence.loadWirelessHotspotMode(targetContext) == WirelessHotspotMode.WIFI_P2P)
        check(AirPlayPersistence.loadWifiP2pPreferredChannel(targetContext) == 44)
        check(!targetContext.getSharedPreferences("diplay_wm_ex5_performance", 0)
            .getBoolean("phone_microphone_only", true))
        status("PERF19 installed upgrade defaults passed: dual 18 fps, Wi-Fi Direct channel 44, head-unit microphone restored")
    }

    private fun softwareMicrophoneEncodingUsesAndroidSixApis() {
        check(com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable()) {
            "Neither platform nor software microphone encoding is usable"
        }
        // Exercise the encoder in the delivered target class loader, not a duplicate test copy.
        val type = Class.forName("com.shilapi.xcertplay.media.SoftwareOpusEncoder", true, targetContext.classLoader)
        val constructor = type.declaredConstructors.first { it.parameterTypes.size == 3 }
        constructor.isAccessible = true
        val callback: (String, Throwable) -> Unit = { message, error -> throw IllegalStateException(message, error) }
        val encoder = constructor.newInstance(48_000, 5, callback)
        val decodeType = Class.forName("org.concentus.OpusDecoder", true, targetContext.classLoader)
        val decoder = decodeType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(48_000, 1)
        val encode = type.getMethod("encode", ByteArray::class.java)
        val decode = decodeType.getMethod("decode", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, ShortArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        try {
            var frames = 0
            val start = System.nanoTime()
            repeat(12) { frameIndex ->
                val pcm = ByteArray(1920)
                for (index in 0 until 960) {
                    val sample = (8_000 * kotlin.math.sin((frameIndex * 960 + index) * 2.0 * Math.PI * 440 / 48_000)).toInt()
                    pcm[index * 2] = sample.toByte()
                    pcm[index * 2 + 1] = (sample shr 8).toByte()
                }
                val packets = encode.invoke(encoder, pcm) as List<*>
                check(packets.size == 1)
                val packet = packets.single() as ByteArray
                val restored = ShortArray(960)
                check(decode.invoke(decoder, packet, 0, packet.size, restored, 0, restored.size, false) == 960)
                if (restored.any { it != 0.toShort() }) frames++
            }
            check(frames >= 10) { "Software Opus decoded only silence" }
            status("PERF19 software Opus 48kHz/mono/20ms round-trip PASS API=${Build.VERSION.SDK_INT} frames=12 elapsedMs=${(System.nanoTime()-start)/1_000_000}")
        } finally { type.getMethod("close").invoke(encoder) }
    }

    private fun connectionButtonsWork() {
        val activity = startActivitySync(Intent(targetContext, DiPlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val monitor = addMonitor(CarPlayHostActivity::class.java.name, null, false)
        var host: Activity? = null
        try {
            waitForIdleSync()
            main {
                check(DiPlayActivity::class.java.getDeclaredField("setupError").apply { isAccessible = true }.get(activity) == null)
                val connect = DiPlayActivity::class.java.getDeclaredField("connectButton").apply { isAccessible = true }.get(activity) as View
                check(connect.isEnabled) { "Connect-phone button remains disabled" }
                val label = activity.getString(activity.resources.getIdentifier("connect_with_usb", "string", targetContext.packageName))
                val button = texts(activity.window.decorView).first { it.text.toString() == label }
                check(button.performClick()) { "USB button has no click handler" }
            }
            host = waitForMonitorWithTimeout(monitor, 5000)
            check(host != null && !host.isFinishing) { "USB button did not open connection activity" }
            val videoField = CarPlayHostActivity::class.java.getDeclaredField("fallbackVideoView").apply { isAccessible = true }
            var surfaceReady = false
            repeat(50) {
                main { surfaceReady = videoField.get(host) is SurfaceView }
                if (!surfaceReady) android.os.SystemClock.sleep(20)
            }
            check(surfaceReady) { "WM low latency did not select SurfaceView on the installed runtime" }
            main {
                val type = CarPlayHostActivity::class.java
                check(type.getDeclaredField("lowLatencyVideo").apply { isAccessible = true }.getBoolean(host))
                check(!type.getDeclaredField("smoothVideo").apply { isAccessible = true }.getBoolean(host))
                check(type.getDeclaredField("fps").apply { isAccessible = true }.getInt(host) == 18)
                check(type.getDeclaredField("displayScalePercent").apply { isAccessible = true }.getInt(host) == 50)
                if (missingVpn) {
                    check(!type.getDeclaredField("vpnReady").apply { isAccessible = true }.getBoolean(host))
                    check(!type.getDeclaredField("awaitingVpnConsent").apply { isAccessible = true }.getBoolean(host))
                    val expected = host!!.getString(host!!.resources.getIdentifier("settings_usb_vpn_unavailable", "string", targetContext.packageName))
                    check(type.getDeclaredField("latestStage").apply { isAccessible = true }.get(host) == expected)
                    status("PASS missing com.android.vpndialogs: USB opens without crash; permission stays ungranted and firmware limitation is visible")
                }
                val config = type.getDeclaredMethod("createRuntimeConfig").apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
                check(config.coalesceTouchMoves && config.wifiP2pPreferredChannel == 36 && config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P)
            }
            status("PASS installed WM profile: SurfaceView, no smoothing delay, touch coalescing and required Wi-Fi channel 36")
        } finally {
            removeMonitor(monitor)
            main { host?.finish(); activity.finish() }
            waitForIdleSync()
        }
    }

    private fun texts(view: View): List<TextView> = buildList {
        if (view is TextView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
    }
}
