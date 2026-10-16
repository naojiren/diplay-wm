// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** The verified WM Android 6 input contract is not a generic Android keyboard service. */
internal object WmEx5MediaKeys {
    fun supported(context: Context): Boolean = Build.VERSION.SDK_INT == 23 &&
        WmEx5PerformanceProfile.isTestPackage(context)

    fun handles(event: KeyEvent): Boolean = event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
}

/** OEM events can reuse downTime (or have no timestamps); never remember them indefinitely. */
internal class WmEx5MediaKeyPresses(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private data class Key(val code: Int, val device: Int, val scan: Int)
    private data class Event(val key: Key, val action: Int, val down: Long, val time: Long)
    private val recent = LinkedHashMap<Event, Long>()
    private val held = LinkedHashMap<Key, Event>()

    @Synchronized
    fun accept(event: KeyEvent, releaseOnly: Boolean): Int? {
        if (!WmEx5MediaKeys.handles(event)) return null
        val key = Key(event.keyCode, event.deviceId, event.scanCode)
        if (event.isCanceled) {
            held.remove(key)
            return null
        }
        if (!event.hasNoModifiers()) return null
        if (event.action != KeyEvent.ACTION_UP &&
            (releaseOnly || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)) return null
        val now = clock()
        val iterator = recent.entries.iterator()
        while (iterator.hasNext()) {
            val age = now - iterator.next().value
            if (age < 0 || age >= DUPLICATE_WINDOW_MS) iterator.remove()
        }
        val observation = Event(key, event.action, event.downTime, event.eventTime)
        if (recent.containsKey(observation)) return null
        recent[observation] = now
        while (recent.size > 32) recent.remove(recent.keys.first())
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Binder and window delivery can be reordered: an already delivered UP also owns
            // a late copy of its DOWN, but not a new DOWN with a newer event timestamp.
            if (recent.keys.any { it.key == key && it.action == KeyEvent.ACTION_UP &&
                    it.down == event.downTime && it.time >= event.eventTime }) return null
            // A duplicated initial DOWN during a long hold must not fire again after cache expiry.
            if (held[key] == observation) return null
            held[key] = observation
            while (held.size > 32) held.remove(held.keys.first())
        } else {
            // Pair UP with a DOWN already delivered by the window or MediaSession. Release-only
            // OEM presses have no held entry, so their changing eventTime identifies each press.
            val down = held.remove(key)
            if (down != null && down.down == event.downTime) return null
        }
        return CarPlayMediaButton.forKeyCode(event.keyCode)
    }

    @Synchronized fun clear() { recent.clear(); held.clear() }

    companion object {
        // Only identical events are suppressed. Distinct timestamps can arrive as fast as needed.
        // With completely identical OEM events, allow another press after this bounded window.
        private const val DUPLICATE_WINDOW_MS = 200L
    }
}

/**
 * WM's InputService rewrites physical left/right to 88/87 and only delivers ACTION_UP.
 * Listen while the CarPlay activity is visible; never inject global keys or take over the service.
 * Same independently implemented Binder contract as the already tested WM launcher.
 */
internal class WmEx5MediaKeysMonitor(
    private val supported: () -> Boolean,
    private val findService: () -> IBinder? = {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as? IBinder
    },
    private val onKey: (KeyEvent) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    fun start() {
        if (!supported() || session != null) return
        val next = Session()
        session = next
        next.worker.post { next.connect() }
    }

    fun stop() {
        val old = session ?: return
        session = null
        old.active = false
        old.worker.removeCallbacksAndMessages(null)
        old.worker.post { old.detach(); old.thread.quitSafely() }
    }

    private inner class Session {
        val thread = HandlerThread("WePlay-WM-MediaKeys").apply { start() }
        val worker = Handler(thread.looper)
        @Volatile var active = true
        private var remote: IBinder? = null
        private val subscriptions = mutableSetOf<Int>()
        private val reconnect = Runnable { connect() }
        private val death = IBinder.DeathRecipient {
            worker.post {
                detach()
                if (active) worker.postDelayed(reconnect, 1000)
            }
        }
        private val listener = object : Binder() {
            init { attachInterface(null, CALLBACK) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(CALLBACK)
                    return true
                }
                if (code != 1) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK)
                val event = runCatching {
                    if (data.readInt() != 0) KeyEvent.CREATOR.createFromParcel(data) else null
                }.getOrNull()
                if (event != null && WmEx5MediaKeys.handles(event) &&
                    event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.hasNoModifiers()) main.post {
                    if (active && session === this@Session) onKey(event)
                }
                reply?.writeNoException()
                return true
            }
        }

        fun connect() {
            if (!active) return
            detach()
            try {
                val binder = findService() ?: error("Input service unavailable")
                check(binder.interfaceDescriptor == DESCRIPTOR) { "Unexpected input descriptor" }
                remote = binder
                binder.linkToDeath(death, 0)
                for (key in KEYS) {
                    check(subscription(1, key)) { "Input subscription rejected" }
                    subscriptions.add(key)
                }
                // Older OEM services may reject these optional codes. Keep the working arrows.
                for (key in PLAYBACK_KEYS) {
                    if (runCatching { subscription(1, key) }.getOrDefault(false)) subscriptions.add(key)
                }
                Log.i(TAG, "Factory media listener connected keys=$subscriptions")
            } catch (error: Exception) {
                detach()
                Log.w(TAG, "Factory input unavailable: ${error.javaClass.simpleName}")
                if (active) worker.postDelayed(reconnect, 10000)
            }
        }

        private fun subscription(transaction: Int, key: Int): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(listener)
                data.writeInt(key)
                check(remote?.transact(transaction, data, reply, 0) == true)
                reply.readException()
                reply.readInt() != 0
            } finally { data.recycle(); reply.recycle() }
        }

        fun detach() {
            for (key in subscriptions) runCatching { subscription(2, key) }
            subscriptions.clear()
            runCatching { remote?.unlinkToDeath(death, 0) }
            remote = null
        }
    }

    companion object {
        const val SERVICE = "Service.Input"
        const val DESCRIPTOR = "com.yftech.inputmanager.IInputManager"
        const val CALLBACK = "com.yftech.inputmanager.IInputListener"
        private const val TAG = "WePlay-WM-MediaKeys"
        private val KEYS = listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_NEXT)
        private val PLAYBACK_KEYS = listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
}
