package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE)
class WmEx5ClusterProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val ids = mutableListOf<Int>()

    @Before fun reset() {
        context.getSharedPreferences("diplay_wm_ex5_cluster", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }
    @After fun cleanup() { ids.forEach(ShadowDisplayManager::removeDisplay); reset() }

    private fun display(name: String): Display {
        val id = ShadowDisplayManager.addDisplay("w1024dp-h600dp-mdpi", 5)
        ids += id
        return manager.getDisplay(id).also {
            shadowOf(it).setName(name)
            shadowOf(it).setFlags(Display.FLAG_PRESENTATION)
        }
    }

    @Test fun unrelatedScreenRemainsExcludedUntilTheDriverEnablesTheEx5Profile() {
        val screen = display("EX5 instrument map")
        assertNull(ClusterMapPresentation.findDisplay(context))
        WmEx5ClusterProfile.setEnabled(context, true)
        assertEquals(screen.displayId, ClusterMapPresentation.findDisplay(context)?.displayId)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(context))
    }

    @Test fun manualSelectionDoesNotFallBackToThePassengerScreenWhenItsDisplayVanishes() {
        val cluster = display("EX5 instrument map")
        display("Passenger screen")
        WmEx5ClusterProfile.setEnabled(context, true)
        WmEx5ClusterProfile.select(context, cluster)
        ShadowDisplayManager.removeDisplay(cluster.displayId)
        ids.remove(cluster.displayId)
        assertNull(ClusterMapPresentation.findDisplay(context))
        val replacement = display("EX5 instrument map")
        assertEquals(replacement.displayId, ClusterMapPresentation.findDisplay(context)?.displayId)
    }

    @Test fun ex5AndAdbRoutingAreMutuallyExclusive() {
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        WmEx5ClusterProfile.setEnabled(context, true)
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(context))
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        assertFalse(WmEx5ClusterProfile.enabled(context))
    }

    @Test fun excessiveMarginsLeaveAUsableAreaInsideTheDisplay() {
        WmEx5ClusterProfile.setMargin(context, "left", 100)
        WmEx5ClusterProfile.setMargin(context, "right", 100)
        WmEx5ClusterProfile.setMargin(context, "top", -50)
        assertEquals(Rect(400, 0, 600, 600), WmEx5ClusterProfile.viewport(context, 1000, 600))
    }

    @Test fun videoAndGuidanceUseTheConfiguredAreaWithoutFillingTheOutside() {
        WmEx5ClusterProfile.setResolutionPercent(context, 40)
        WmEx5ClusterProfile.setEnabled(context, true)
        WmEx5ClusterProfile.setMargin(context, "left", 10)
        WmEx5ClusterProfile.setMargin(context, "top", 20)
        val presentation = ClusterMapPresentation(context, display("EX5 instrument map")) {}
        try {
            presentation.create()
            val video: View = ReflectionHelpers.getField(presentation, "videoView")
            val params = video.layoutParams as FrameLayout.LayoutParams
            assertEquals(103, params.leftMargin)
            assertEquals(120, params.topMargin)
            assertEquals(920, params.width)
            assertEquals(480, params.height)
            val card: View = ReflectionHelpers.getField(presentation, "turnCardView")
            assertEquals(params.width, card.layoutParams.width)
            assertEquals(params.height, card.layoutParams.height)
        } finally { presentation.dismiss() }
    }

    @Test fun lowResolutionStreamIsCenteredAndHasAnIndependentFrameRate() {
        WmEx5ClusterProfile.setResolutionPercent(context, 40)
        AirPlayPersistence.saveFps(context, 30)
        val config = WmEx5ClusterProfile.streamConfig(context, 1920, 480)
        assertEquals(768, config.widthPixels)
        assertEquals(192, config.heightPixels)
        assertEquals(18, config.fps)
        assertEquals(Rect(0, 0, 1920, 480), WmEx5ClusterProfile.videoViewport(context, 1920, 480))
        assertEquals(50.0 to 50.0, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.markerPercent(
            0, 0, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT))
        WmEx5ClusterProfile.setFps(context, 24)
        assertEquals(24, WmEx5ClusterProfile.streamConfig(context, 1920, 480).fps)
        AirPlayPersistence.saveFps(context, 12)
        assertEquals(12, WmEx5ClusterProfile.streamConfig(context, 1920, 480).fps)
    }

    @Test fun nativeSizedImageLeavesEqualSpaceOnOppositeSides() {
        WmEx5ClusterProfile.setResolutionPercent(context, 40)
        WmEx5ClusterProfile.setNativeSize(context, true)
        assertEquals(Rect(576, 144, 1344, 336), WmEx5ClusterProfile.videoViewport(context, 1920, 480))
        WmEx5ClusterProfile.setMargin(context, "left", 10)
        WmEx5ClusterProfile.setMargin(context, "right", 10)
        assertEquals(Rect(652, 144, 1268, 336), WmEx5ClusterProfile.videoViewport(context, 1920, 480))
    }

    @Test fun codecAlignmentIsFittedWithoutDistortionOrCrossingUserMargins() {
        WmEx5ClusterProfile.setResolutionPercent(context, 40)
        WmEx5ClusterProfile.setMargin(context, "left", 10)
        val config = WmEx5ClusterProfile.streamConfig(context, 1920, 480)
        assertEquals(688, config.widthPixels)
        assertEquals(192, config.heightPixels)
        assertEquals(Rect(196, 0, 1916, 480), WmEx5ClusterProfile.videoViewport(context, 1920, 480))
        // A small projection area still yields a contained, positive rectangle.
        for (side in listOf("left", "right", "top", "bottom")) WmEx5ClusterProfile.setMargin(context, side, 40)
        val area = WmEx5ClusterProfile.viewport(context, 8, 8)
        val output = WmEx5ClusterProfile.videoViewport(context, 8, 8)
        assertTrue(area.contains(output))
        assertTrue(output.width() > 0 && output.height() > 0)
    }

    @Test fun fullResolutionInstrumentDefaultsToEighteenFpsWithoutChangingTheMainScreen() {
        AirPlayPersistence.saveFps(context, 30)
        val config = WmEx5ClusterProfile.streamConfig(context, 1920, 480)
        assertEquals(1920, config.widthPixels)
        assertEquals(480, config.heightPixels)
        assertEquals(18, config.fps)
        assertEquals(Rect(0, 0, 1920, 480), WmEx5ClusterProfile.videoViewport(context, 1920, 480))
        assertEquals(30, AirPlayPersistence.loadFps(context))
        WmEx5ClusterProfile.setResolutionPercent(context, 40)
        assertEquals(768, WmEx5ClusterProfile.streamConfig(context, 1920, 480).widthPixels)
    }

    @Test fun requestedEx5MarginsKeepTheMapCenteredWithinTheRemainingArea() {
        val wm = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = WmEx5PerformanceProfile.PACKAGE
        }
        val area = WmEx5ClusterProfile.viewport(wm, 1920, 480)
        assertEquals(Rect(460, 0, 1920, 456), area)
        val video = WmEx5ClusterProfile.videoViewport(wm, 1920, 480)
        val stream = WmEx5ClusterProfile.streamConfig(wm, 1920, 480)
        assertTrue(area.contains(video))
        // Codec alignment can leave an odd number of physical pixels to divide.
        assertEquals(area.exactCenterX(), video.exactCenterX(), 0.5f)
        assertEquals(area.exactCenterY(), video.exactCenterY(), 0.5f)
        assertEquals(stream.widthPixels.toDouble() / stream.heightPixels, video.width().toDouble() / video.height(), 0.01)
        assertEquals(100, WmEx5ClusterProfile.resolutionPercent(wm))
        assertEquals(18, stream.fps)
        // Explicit zero margins must still be editable and take precedence over defaults.
        WmEx5ClusterProfile.setMargin(wm, "left", 0)
        WmEx5ClusterProfile.setMargin(wm, "bottom", 0)
        assertEquals(Rect(0, 0, 1920, 480), WmEx5ClusterProfile.viewport(wm, 1920, 480))
    }

    @Test @Config(sdk = [23])
    fun androidSixUsesTheSameWindowTypeAsTheWorkingNavigationMod() {
        WmEx5ClusterProfile.setEnabled(context, true)
        val presentation = ClusterMapPresentation(context, display("EX5 instrument map")) {}
        try {
            assertEquals(WindowManager.LayoutParams.TYPE_TOAST, presentation.window!!.attributes.type)
            val flags = presentation.window!!.attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        } finally { presentation.dismiss() }
    }
}
