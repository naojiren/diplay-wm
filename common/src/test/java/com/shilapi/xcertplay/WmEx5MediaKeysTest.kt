package com.shilapi.xcertplay

import android.content.Intent
import android.content.ContextWrapper
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WmEx5MediaKeysTest {
    private fun key(code: Int = 87, action: Int = KeyEvent.ACTION_UP, time: Long = 100,
        repeat: Int = 0, flags: Int = 0, meta: Int = 0, device: Int = 7) =
        KeyEvent(time, time + if (action == KeyEvent.ACTION_UP) 30 else 0,
            action, code, repeat, meta, device, 5, flags)

    private fun context(packageName: String) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getPackageName() = packageName
    }

    @Test fun verifiedAndroid6PackageEnablesTheFactoryIntegrationOnlyForWePlay() {
        assertTrue(WmEx5MediaKeys.supported(context(WmEx5PerformanceProfile.PACKAGE)))
        assertFalse(WmEx5MediaKeys.supported(context("com.shihab.diplay")))
    }

    @Test @Config(sdk = [28]) fun anotherAndroidVersionDoesNotUseTheAndroid6BinderContract() {
        assertFalse(WmEx5MediaKeys.supported(context(WmEx5PerformanceProfile.PACKAGE)))
    }

    @Test fun releaseOnlyFactoryKeysMapToIphonePreviousAndNext() {
        val presses = WmEx5MediaKeyPresses()
        assertEquals(CarPlayMediaButton.PREVIOUS, presses.accept(key(88), true))
        assertEquals(CarPlayMediaButton.NEXT, presses.accept(key(87), true))
    }

    @Test fun factoryDownAndHeldRepeatsWaitForOneRelease() {
        val presses = WmEx5MediaKeyPresses()
        assertNull(presses.accept(key(action = KeyEvent.ACTION_DOWN), true))
        assertNull(presses.accept(key(action = KeyEvent.ACTION_DOWN, repeat = 3), true))
        assertEquals(CarPlayMediaButton.NEXT, presses.accept(key(repeat = 3), true))
        assertNull(presses.accept(key(repeat = 3), true))
    }

    @Test fun androidDownAndFactoryReleaseDoNotSkipTwoTracks() {
        val presses = WmEx5MediaKeyPresses()
        assertEquals(CarPlayMediaButton.NEXT, presses.accept(key(action = KeyEvent.ACTION_DOWN), false))
        assertNull(presses.accept(key(action = KeyEvent.ACTION_DOWN, repeat = 1), false))
        assertNull(presses.accept(key(), true))
        assertNull(presses.accept(key(), false))
    }

    @Test fun rapidDifferentPressesAreNeverTimeDebounced() {
        val presses = WmEx5MediaKeyPresses()
        assertEquals(List(8) { CarPlayMediaButton.NEXT }, (100L..107L).map {
            presses.accept(key(time = it), true)
        })
    }

    @Test fun reusedPositiveDownTimeDoesNotSuppressLaterFactoryPresses() {
        val presses = WmEx5MediaKeyPresses { 1000L }
        for (code in listOf(87, 88, 85, 126, 127)) {
            repeat(8) { index ->
                val event = KeyEvent(42L, 100L + index, KeyEvent.ACTION_UP, code, 0)
                assertEquals(CarPlayMediaButton.forKeyCode(code), presses.accept(event, true))
                assertNull(presses.accept(event, false))
            }
        }
    }

    @Test fun allZeroFactoryTimestampsExpireUsingLocalClockAndDoNotStayDisabled() {
        var now = 1000L
        val presses = WmEx5MediaKeyPresses { now }
        for (code in listOf(87, 88, 85)) {
            repeat(10) {
                val event = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0)
                assertEquals(CarPlayMediaButton.forKeyCode(code), presses.accept(event, true))
                now += 20L
                assertNull(presses.accept(event, false))
                now += 200L
            }
        }
    }

    @Test fun reusedDownTimeWithRealDownUpPairsWorksAndLongHoldDoesNotRepeat() {
        var now = 1000L
        val presses = WmEx5MediaKeyPresses { now }
        repeat(10) { index ->
            val time = 100L + index * 100L
            val down = KeyEvent(42L, time, KeyEvent.ACTION_DOWN, 85, 0)
            assertEquals(CarPlayMediaButton.PLAY_PAUSE, presses.accept(down, false))
            now += 1000L
            assertNull(presses.accept(down, false))
            val up = KeyEvent(42L, time + 50L, KeyEvent.ACTION_UP, 85, 0)
            assertNull(presses.accept(up, true))
            assertNull(presses.accept(up, false))
        }
    }

    @Test fun factoryUpBeforeDelayedWindowDownStillSendsOneCommand() {
        val presses = WmEx5MediaKeyPresses { 1000L }
        assertEquals(CarPlayMediaButton.NEXT, presses.accept(key(), true))
        assertNull(presses.accept(key(action = KeyEvent.ACTION_DOWN), false))
        assertNull(presses.accept(key(), false))
        assertEquals(CarPlayMediaButton.NEXT, presses.accept(key(time = 200), true))
    }

    @Test fun onlyMediaKeysAreHandledAndCanceledModifiedEventsAreIgnored() {
        val presses = WmEx5MediaKeyPresses()
        for (code in listOf(21, 22, 24, 25, 304, 4)) {
            assertFalse(WmEx5MediaKeys.handles(key(code)))
            assertNull(presses.accept(key(code), true))
        }
        assertNull(presses.accept(key(flags = KeyEvent.FLAG_CANCELED), true))
        assertNull(presses.accept(key(meta = KeyEvent.META_CTRL_ON), true))
    }

    @Test fun playbackReleaseWorksAndDuplicateWindowSessionFactoryDeliveryTogglesOnlyOnce() {
        for (code in listOf(85, 126, 127)) {
            val presses = WmEx5MediaKeyPresses()
            assertEquals(CarPlayMediaButton.PLAY_PAUSE, presses.accept(key(code), true))
            assertNull(presses.accept(key(code), false))
            assertEquals(CarPlayMediaButton.PLAY_PAUSE,
                presses.accept(key(code, KeyEvent.ACTION_DOWN, time = 200), false))
            assertNull(presses.accept(key(code, KeyEvent.ACTION_DOWN, time = 200), false))
            assertNull(presses.accept(key(code, time = 200), true))
        }
    }

    @Test fun controllerReplacementClearsPressHistoryAndHistoryIsBounded() {
        val presses = WmEx5MediaKeyPresses()
        assertNotNull(presses.accept(key(), true))
        presses.clear()
        assertNotNull(presses.accept(key(), true))
        for (time in 101L..140L) assertNotNull(presses.accept(key(time = time), true))
        assertNotNull(presses.accept(key(), true))
    }

    @Test fun realMediaCallbackSharesOemDedupButSoftwareTransportCommandsStillWork() {
        val presses = WmEx5MediaKeyPresses()
        val sent = mutableListOf<Int>()
        val callback = CarPlayMediaCallback(hardwareEvent = { event ->
            if (!WmEx5MediaKeys.handles(event)) false else {
                presses.accept(event, false)?.let { sent += it }
                true
            }
        }) { index, _ -> sent += index }
        fun button(event: KeyEvent) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)
        assertTrue(callback.onMediaButtonEvent(button(key(action = KeyEvent.ACTION_DOWN))))
        presses.accept(key(), true)?.let { sent += it }
        assertTrue(callback.onMediaButtonEvent(button(key())))
        assertEquals(listOf(CarPlayMediaButton.NEXT), sent)
        callback.onSkipToNext()
        callback.onSkipToPrevious()
        assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS), sent)
    }

    @Test fun oemBinderSubscriptionsReleaseEventsAndUnregistrationUseVerifiedContract() {
        val input = Input()
        val received = mutableListOf<KeyEvent>()
        val monitor = WmEx5MediaKeysMonitor({ true }, { input }, received::add)
        try {
            monitor.start(); monitor.start()
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(88, 87, 85, 126, 127), input.requested.toList())
            input.event(key(88))
            input.event(key(87, KeyEvent.ACTION_DOWN))
            input.event(key(24))
            input.event(key(flags = KeyEvent.FLAG_CANCELED))
            input.event(key(meta = KeyEvent.META_CTRL_ON))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(88), received.map { it.keyCode })
            monitor.stop()
            assertTrue(input.removed.await(3, TimeUnit.SECONDS))
            assertEquals(setOf(88, 87, 85, 126, 127), input.removedKeys.toSet())
        } finally { monitor.stop() }
    }

    @Test fun leavingCarplayDropsAlreadyQueuedFactoryEvents() {
        val input = Input()
        val received = mutableListOf<KeyEvent>()
        val monitor = WmEx5MediaKeysMonitor({ true }, { input }, received::add)
        try {
            monitor.start()
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            input.event(key())
            monitor.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(received.isEmpty())
            assertTrue(input.removed.await(3, TimeUnit.SECONDS))
        } finally { monitor.stop() }
    }

    @Test fun partialSubscriptionFailureRemovesTheAlreadyRegisteredKey() {
        val input = Input(rejectNext = true)
        val monitor = WmEx5MediaKeysMonitor({ true }, { input }) {}
        try {
            monitor.start()
            assertTrue(input.registered.await(3, TimeUnit.SECONDS))
            assertTrue(input.removed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(88), input.removedKeys.toList())
        } finally { monitor.stop() }
    }

    @Test fun unsupportedPackageOrFirmwareNeverLooksUpTheFactoryService() {
        var lookups = 0
        val monitor = WmEx5MediaKeysMonitor({ false }, { lookups++; Input() }) {}
        monitor.start(); monitor.stop()
        assertEquals(0, lookups)
    }

    private class Input(private val rejectNext: Boolean = false) : Binder() {
        val registered = CountDownLatch(if (rejectNext) 2 else 5)
        val removed = CountDownLatch(if (rejectNext) 1 else 5)
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val removedKeys = Collections.synchronizedList(mutableListOf<Int>())
        private val listeners = ConcurrentHashMap<Int, IBinder>()
        init { attachInterface(null, WmEx5MediaKeysMonitor.DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WmEx5MediaKeysMonitor.DESCRIPTOR)
            val listener = requireNotNull(data.readStrongBinder())
            val key = data.readInt()
            assertTrue(key in listOf(87, 88, 85, 126, 127))
            when (code) {
                1 -> {
                    requested += key
                    if (!rejectNext || key != 87) listeners[key] = listener
                    reply!!.writeNoException()
                    reply.writeInt(if (rejectNext && key == 87) 0 else 1)
                    registered.countDown()
                }
                2 -> {
                    assertSame(listeners.remove(key), listener)
                    removedKeys += key
                    reply!!.writeNoException(); reply.writeInt(1)
                    removed.countDown()
                }
                else -> fail("Unexpected input operation $code")
            }
            return true
        }

        fun event(event: KeyEvent) {
            val listener = listeners[if (event.keyCode in listOf(87, 88, 85, 126, 127)) event.keyCode else 87] ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(WmEx5MediaKeysMonitor.CALLBACK)
                data.writeInt(1)
                event.writeToParcel(data, 0)
                assertTrue(listener.transact(1, data, null, IBinder.FLAG_ONEWAY))
            } finally { data.recycle() }
        }
    }
}
