package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The OEM Android 6 driver needs its own bounded radio lease in both wireless modes. */
internal class WmWifiPerformanceGuard(
    context: Context,
    private val report: (String) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val worker = processWorker
    @Volatile private var closed = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    @Volatile private var adb: LocalAdb? = null
    private var lease: File? = null
    private var leaseLog: File? = null
    private var leaseCounter = 0L
    private var retryAtNs = 0L
    private var heartbeat: ScheduledFuture<*>? = null

    fun start() {
        worker.execute {
            if (closed) return@execute
            try {
                acquirePublicLocks()
                if (closed) return@execute
                tryLaunchDriverLease()
                if (!closed) heartbeat = worker.scheduleWithFixedDelay({
                    if (!closed) {
                        runCatching { cpuLock?.acquire(CPU_LEASE_MILLIS) }
                        val current = lease
                        if (current != null) {
                            val status = runCatching { leaseLog?.readText().orEmpty() }.getOrDefault("")
                            if (status.contains("restored=")) {
                                endLease()
                                retryAtNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                                log("WM radio guard lease ended; retry in 30 seconds")
                            } else runCatching { current.writeText((++leaseCounter).toString()) }
                                .onFailure { endLease() }
                        } else if (System.nanoTime() >= retryAtNs) tryLaunchDriverLease()
                    }
                }, 2, 2, TimeUnit.SECONDS)
            } catch (error: Exception) {
                log("WM radio guard unavailable failureClass=${error.javaClass.simpleName}; public locks remain active")
            } finally {
                if (closed) release()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquirePublicLocks() {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        runCatching {
            wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WePlay:WMStreaming")?.also {
                it.setReferenceCounted(false)
                it.acquire()
                wifiLock = it
            }
        }.onFailure { log("WM Wi-Fi performance lock unavailable failureClass=${it.javaClass.simpleName}") }
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WePlay:WMStreaming")?.also {
                it.setReferenceCounted(false)
                it.acquire(CPU_LEASE_MILLIS)
                cpuLock = it
            }
        }.onFailure { log("WM CPU wake lock unavailable failureClass=${it.javaClass.simpleName}") }
        log("WM streaming public locks wifi=${wifiLock?.isHeld == true} cpu=${cpuLock?.isHeld == true}")
    }

    private fun tryLaunchDriverLease() {
        retryAtNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        runCatching { launchDriverLease() }.onFailure {
            log("WM radio guard local ADB failed=${it.javaClass.simpleName}; retry in 30 seconds")
            endLease()
        }
    }

    private fun launchDriverLease() {
        val helper = extractHelper(app)
        if (closed) return
        val client = LocalAdb(AdbKeys.load(app)).also { adb = it }
        try {
            val access = client.connect(mayAsk = false)
            if (closed) return
            if (access != LocalAdb.Access.READY) {
                log("WM radio guard local ADB access=$access; enable the already authorized head-unit ADB")
                return
            }
            val probe = client.shell("${shellQuote(helper.absolutePath)} --probe").orEmpty()
            if (!probe.contains("WM radio guard probe chip=4339") || !probe.contains("uid=0")) {
                log("WM radio guard local ADB lacks driver access: ${probe.takeLast(600)}")
                return
            }
            if (closed) return
            val token = UUID.randomUUID().toString().replace("-", "")
            val file = File(app.filesDir, "wm-radio-lease-$token").also { lease = it }
            val status = File(file.absolutePath + ".log").also { leaseLog = it }
            status.writeText("")
            file.writeText((++leaseCounter).toString())
            val output = client.shell("${shellQuote(helper.absolutePath)} --launch ${Process.myPid()} ${shellQuote(file.absolutePath)}").orEmpty()
            val observed = status.readText()
            if (!launchConfirmed(output, observed)) {
                log("WM radio guard activation failed: ${(output + observed).takeLast(800)}")
                endLease()
                return
            }
            log("WM radio guard backend=local-adb verified=true ${observed.lineSequence().firstOrNull { it.contains("observedPm=") }}")
        } finally {
            client.close()
            if (adb === client) adb = null
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        adb?.cancelPendingOperations()
        // Serialized behind startup: cancellation cannot strand an acquired lock or root process.
        worker.execute { release() }
    }

    private fun release() {
        heartbeat?.cancel(false)
        heartbeat = null
        endLease()
        runCatching { cpuLock?.let { if (it.isHeld) it.release() } }
        cpuLock = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    private fun endLease() {
        val file = lease ?: return
        // The detached helper sees deletion within one second; lost heartbeats expire in 20s.
        runCatching { file.delete() }
        val status = leaseLog
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500)
        while (status != null && System.nanoTime() < deadline) {
            val text = runCatching { status.readText() }.getOrDefault("")
            if (text.contains("restored=")) {
                log("WM radio guard ${text.lineSequence().lastOrNull { it.contains("restored=") }}")
                if (text.contains("restored=true")) status.delete()
                break
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        lease = null
        leaseLog = null
    }

    private fun log(message: String) { runCatching { report(message) } }

    companion object {
        private const val CPU_LEASE_MILLIS = 60_000L
        // A new controller cannot start a root lease before the previous lease has restored.
        private val processWorker = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "wm-wifi-performance").apply { isDaemon = true }
        }
        fun eligible(sdk: Int = Build.VERSION.SDK_INT, model: String = Build.MODEL,
            hardware: String = Build.HARDWARE, packageName: String): Boolean =
            sdk == 23 && model.equals("CQ1301", ignoreCase = true) &&
                hardware.startsWith("j6headunit", ignoreCase = true) && packageName == "com.shihab.diplay.wmex5"

        internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        internal fun launchConfirmed(reply: String, status: String): Boolean =
            reply.lineSequence().any { it.startsWith("WM radio guard launched=1 pid=") } &&
                status.lineSequence().any { it.startsWith("WM radio guard active=1 ") } &&
                !status.contains("restored=")

        private fun extractHelper(app: Context): File {
            val bytes = app.assets.open("wm-network-guard-armv7").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).take(12)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val helper = File(app.filesDir, "wm-network-guard-$hash")
            if (!helper.isFile || !helper.readBytes().contentEquals(bytes)) {
                helper.writeBytes(bytes)
            }
            check(helper.setExecutable(true, true)) { "Network helper is not executable" }
            return helper
        }
    }
}
