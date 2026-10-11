package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33], manifest = Config.NONE)
class PreparationLayoutTest {
    @Test fun enlargedContentRemainsReadableAndShortViewportsCanScrollToTheLastControl() {
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
        method.isAccessible = true
        val root = method.invoke(activity) as android.widget.FrameLayout
        val viewport = root.getChildAt(2) as android.widget.ScrollView
        val panel = viewport.getChildAt(0) as LinearLayout
        val density = activity.resources.displayMetrics.density
        fun layout(heightDp: Int) {
            val width = (640 * density).toInt()
            val height = (heightDp * density).toInt()
            repeat(3) {
                // This detached view has no ViewRoot to schedule the next traversal.
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) invalidateMeasurement(view.getChildAt(index))
                    }
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            val last = panel.getChildAt(panel.childCount - 1)
            assertTrue("height=$heightDp bottom=${last.bottom} padding=${panel.paddingBottom} panel=${panel.height}", last.bottom + panel.paddingBottom <= panel.height)
            assertEquals(1f, panel.scaleY)
            assertTrue("The first control must remain reachable", panel.top >= 0)
            viewport.scrollTo(0, panel.height)
            assertTrue("The last control must remain reachable at height=$heightDp",
                last.bottom + panel.paddingBottom - viewport.scrollY <= viewport.height + 1)
            viewport.scrollTo(0, 0)
        }
        for (height in listOf(200, 240, 320, 400, 479, 480, 720)) layout(height)
        assertEquals(47.6f, (panel.getChildAt(1) as TextView).textSize / activity.resources.displayMetrics.scaledDensity, 0.01f)
    }

    @Test fun sameLanTimeoutShowsPhoneReconnectAdviceButOtherModesDoNot() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView").apply { isAccessible = true }.invoke(activity)
        fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("wirelessEnabled").set(activity, true)
        field("wirelessHotspotMode").set(activity, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.EXISTING_WIFI)
        CarPlayHostActivity::class.java.getDeclaredMethod("setFailedConnectionStage", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "No AirPlay TCP after CarPlay StartSession")
        val hint = field("lanReconnectHintView").get(activity) as TextView
        assertEquals(View.VISIBLE, hint.visibility)
        assertEquals(activity.getString(com.shilapi.xcertplay.host.R.string.existing_wifi_reconnect_tip), hint.text)
        field("wirelessHotspotMode").set(activity, com.shilapi.xcertplay.orchestration.WirelessHotspotMode.WIFI_P2P)
        CarPlayHostActivity::class.java.getDeclaredMethod("updateDebugOverlays").apply { isAccessible = true }.invoke(activity)
        assertEquals(View.GONE, hint.visibility)
    }
}
