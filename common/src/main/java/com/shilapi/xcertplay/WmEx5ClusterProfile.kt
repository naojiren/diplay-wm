package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import kotlin.math.roundToInt

/** EX5 profile based on the supplied 7.1.0.600067_chenmo APK's Presentation path. */
internal object WmEx5ClusterProfile {
    private const val PREFS = "diplay_wm_ex5_cluster"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", WmEx5PerformanceProfile.isTestPackage(context))
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) {
            AirPlayPersistence.saveAdbClusterEnabled(context, false)
            AirPlayPersistence.saveClusterMapEnabled(context, true)
        }
    }

    fun displays(context: Context): List<Display> =
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.filter { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
            ?.filter { ClusterMapPresentation.sizeOf(it).let { size -> size.x >= 8 && size.y >= 8 } }
            .orEmpty()

    fun select(context: Context, display: Display?) {
        prefs(context).edit().putInt("display_id", display?.displayId ?: -1)
            .putString("display_name", display?.name).apply()
    }

    fun findDisplay(context: Context): Display? {
        if (!enabled(context)) return null
        val candidates = displays(context)
        val saved = prefs(context)
        val id = saved.getInt("display_id", -1)
        if (id == -1) return candidates.firstOrNull() // Same filtered-list ordering as chenmo.g.c.
        val name = saved.getString("display_name", null)
        return candidates.firstOrNull { it.displayId == id && it.name == name }
            ?: candidates.firstOrNull { name != null && it.name == name }
        // A missing manual selection must not silently redirect video to another screen.
    }

    fun automatic(context: Context): Boolean = prefs(context).getInt("display_id", -1) == -1
    const val DEFAULT_LEFT_MARGIN = 24
    const val DEFAULT_BOTTOM_MARGIN = 5
    fun margin(context: Context, side: String): Int {
        val default = if (WmEx5PerformanceProfile.isTestPackage(context)) when (side) {
            "left" -> DEFAULT_LEFT_MARGIN
            "bottom" -> DEFAULT_BOTTOM_MARGIN
            else -> 0
        } else 0
        return prefs(context).getInt(side, default).coerceIn(0, 40)
    }
    fun setMargin(context: Context, side: String, percent: Int) {
        require(side in listOf("left", "top", "right", "bottom"))
        prefs(context).edit().putInt(side, percent.coerceIn(0, 40)).apply()
    }

    fun viewport(context: Context, width: Int, height: Int): Rect = Rect(
        width * margin(context, "left") / 100,
        height * margin(context, "top") / 100,
        width * (100 - margin(context, "right")) / 100,
        height * (100 - margin(context, "bottom")) / 100,
    )

    const val DEFAULT_RESOLUTION_PERCENT = 100
    const val DEFAULT_FPS = 18
    val resolutionChoices = listOf(25, 30, 40, 50, 67, 83, 100)
    val fpsChoices = listOf(12, 18, 24, 30)
    fun resolutionPercent(context: Context): Int = prefs(context).getInt("resolution_percent", DEFAULT_RESOLUTION_PERCENT)
        .takeIf { it in resolutionChoices } ?: DEFAULT_RESOLUTION_PERCENT
    fun setResolutionPercent(context: Context, percent: Int) {
        require(percent in resolutionChoices)
        prefs(context).edit().putInt("resolution_percent", percent).apply()
    }
    fun fps(context: Context): Int = prefs(context).getInt("fps", DEFAULT_FPS)
        .takeIf { it in fpsChoices } ?: DEFAULT_FPS
    fun setFps(context: Context, value: Int) {
        require(value in fpsChoices)
        prefs(context).edit().putInt("fps", value).apply()
    }
    fun nativeSize(context: Context): Boolean = prefs(context).getBoolean("native_size", false)
    fun setNativeSize(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("native_size", value).apply()
    }

    /** Stream resolution is independent of the main screen and the physical projection area. */
    fun streamConfig(context: Context, width: Int, height: Int): AirPlayDisplayConfig {
        val area = viewport(context, width, height)
        return CarPlayClusterDisplay.config(
            area.width(), area.height(), resolutionPercent(context),
            AirPlayPersistence.loadClusterMarkerHorizontalStep(context),
            AirPlayPersistence.loadClusterMarkerVerticalStep(context),
            AirPlayPersistence.loadClusterContent(context),
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        ).copy(fps = minOf(fps(context), AirPlayPersistence.loadFps(context)))
    }

    /** Fit the aligned stream without stretching it; keep existing instrument margins. */
    fun videoViewport(context: Context, width: Int, height: Int): Rect {
        val area = viewport(context, width, height)
        val stream = streamConfig(context, width, height)
        val fit = minOf(area.width().toDouble() / stream.widthPixels, area.height().toDouble() / stream.heightPixels)
        val scale = if (nativeSize(context)) minOf(1.0, fit) else fit
        val w = (stream.widthPixels * scale).roundToInt().coerceIn(1, area.width())
        val h = (stream.heightPixels * scale).roundToInt().coerceIn(1, area.height())
        val left = area.left + (area.width() - w) / 2
        val top = area.top + (area.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** The mod's SDK expression `(SDK_INT | 4) == 23` selects precisely API 19 and 23. */
    @Suppress("DEPRECATION")
    fun configureWindow(window: Window?) {
        if (Build.VERSION.SDK_INT == 19 || Build.VERSION.SDK_INT == 23) {
            window?.setType(WindowManager.LayoutParams.TYPE_TOAST)
        }
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    fun diagnosticReport(context: Context): String = buildString {
        appendLine("wmEx5Enabled=${enabled(context)}")
        appendLine("wmEx5Selection=${if (automatic(context)) "auto-first-presentation" else prefs(context).getString("display_name", "missing")}")
        appendLine("wmEx5Margins=${listOf("left", "top", "right", "bottom").joinToString { "$it=${margin(context, it)}%" }}")
        appendLine("wmEx5Video=resolution=${resolutionPercent(context)}% requestedFps=${fps(context)} effectiveFps=${minOf(fps(context), AirPlayPersistence.loadFps(context))} placement=${if (nativeSize(context)) "native-centered" else "fit-centered"}")
        appendLine("wmEx5WindowType=${if (Build.VERSION.SDK_INT == 19 || Build.VERSION.SDK_INT == 23) "TYPE_TOAST" else "Presentation default"}")
        val display = findDisplay(context)
        if (display == null) append("wmEx5Display=none") else {
            val size = ClusterMapPresentation.sizeOf(display)
            append("wmEx5Display=${display.displayId}:${display.name} ${size.x}x${size.y} viewport=${viewport(context, size.x, size.y)} videoViewport=${videoViewport(context, size.x, size.y)} stream=${streamConfig(context, size.x, size.y).let { "${it.widthPixels}x${it.heightPixels}" }}")
        }
    }
}

/** Tests the same display/window/viewport without an iPhone, authentication or a video decoder. */
internal class WmEx5ClusterTestPresentation(context: Context, display: Display) : Presentation(context, display) {
    init { WmEx5ClusterProfile.configureWindow(window) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = ClusterMapPresentation.sizeOf(display)
        val area = WmEx5ClusterProfile.videoViewport(context, size.x, size.y)
        val root = FrameLayout(context)
        val pattern = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(15, 22, 30))
                val corner = minOf(width, height) * 0.12f
                val positions = listOf(0f to 0f, width - corner to 0f,
                    0f to height - corner, width - corner to height - corner)
                positions.zip(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)).forEach { (p, color) ->
                    paint.color = color; canvas.drawRect(p.first, p.second, p.first + corner, p.second + corner, paint)
                }
                paint.color = Color.WHITE; paint.strokeWidth = 2f
                canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
                canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = (minOf(width, height) / 15f).coerceAtLeast(12f)
                canvas.drawText(context.getString(R.string.settings_wm_cluster_test_title), width / 2f, height * .32f, paint)
                canvas.drawText("${display.displayId}: ${size.x} x ${size.y}", width / 2f, height * .68f, paint)
            }
        }
        root.addView(pattern, FrameLayout.LayoutParams(area.width(), area.height()).apply {
            leftMargin = area.left; topMargin = area.top
        })
        setContentView(root)
    }
}
