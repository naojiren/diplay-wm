package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import java.io.File
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        val staging = File(privateRoot, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare local authentication" }
        staging.setReadable(false, false); staging.setReadable(true, true)
        staging.setExecutable(false, false); staging.setExecutable(true, true)
        try {
            for (name in AUTH_FILES) {
                val file = File(staging, name)
                context.assets.open("offline-mfi/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }

            // Validate the APK-bundled identity before it can replace a previous installation.
            LocalMfiAuthenticationClient.load(staging)
            val currentValid = runCatching { LocalMfiAuthenticationClient.load(target) }.isSuccess
            if (!currentValid || !sameAuthenticationFiles(target, staging)) {
                val previous = File(privateRoot, "offline-mfi-previous")
                previous.deleteRecursively()
                if (target.exists()) {
                    check(target.renameTo(previous)) { "Could not rotate previous local authentication" }
                }
                try {
                    check(staging.renameTo(target)) { "Could not install local authentication" }
                    previous.deleteRecursively()
                } catch (error: Throwable) {
                    if (!target.exists() && previous.exists()) previous.renameTo(target)
                    throw error
                }
            }
        } finally {
            staging.deleteRecursively()
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    private fun sameAuthenticationFiles(first: File, second: File): Boolean =
        AUTH_FILES.all { name ->
            val left = File(first, name)
            val right = File(second, name)
            left.isFile && right.isFile &&
                MessageDigest.isEqual(sha256(left), sha256(right))
        }

    private fun sha256(file: File): ByteArray =
        file.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest()
        }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    private val AUTH_FILES = listOf("identity.pk8", "certificate.p7b")
}

internal enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"), WIRELESS("wireless"), USB("usb");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
        USB -> false
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode = entries.firstOrNull { it.key == key } ?: LAST_USED
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun defaultConnectionMode(context: Context) =
        DefaultConnectionMode.fromKey(prefs(context).getString("default_connection_mode", null))
    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        prefs(context).edit().putString("default_connection_mode", mode.key).apply()
    }
    fun autoConnectWireless(context: Context) =
        defaultConnectionMode(context).wireless(AirPlayPersistence.loadWirelessEnabled(context))
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
}
