package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.mfi.MfiProtocolMajorResult
import com.shilapi.xcertplay.mfi.MfiSelfCheck
import com.shilapi.xcertplay.mfi.MfiSelfCheckResult
import com.shilapi.xcertplay.transport.LinuxI2cTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Board diagnostic kept intentionally View-based so the APK can run on API 19. */
class MainActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var devicePath: EditText
    private lateinit var runButton: Button
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(12, 17, 27))
        }
        content.addView(text("Board I2C diagnostic", 24f))
        devicePath = EditText(this).apply {
            setText("/dev/i2c-1")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            hint = "Linux I2C device"
            setSingleLine(true)
        }
        content.addView(devicePath, matchWidth())
        runButton = Button(this).apply {
            text = "Run MFi self-check"
            setOnClickListener { runSelfCheck(devicePath.text.toString()) }
        }
        content.addView(runButton, matchWidth())
        statusView = text("Idle", 16f)
        content.addView(statusView, matchWidth())
        content.addView(text("CH341 requires deployment-specific VID/PID configuration.", 14f), matchWidth())
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runSelfCheck(path: String) {
        runButton.isEnabled = false
        devicePath.isEnabled = false
        statusView.text = "Running…"
        executor.execute {
            val next = try {
                LinuxI2cTransport.open(path).use { MfiSelfCheck(it).run() }
                    .let { DiagnosticStatus.Result(it) }
            } catch (error: LinkageError) {
                DiagnosticStatus.Failure(error.message ?: "I2C native library is unavailable")
            } catch (error: Exception) {
                DiagnosticStatus.Failure(error.message ?: error.javaClass.simpleName)
            }
            runOnUiThread {
                if (!isFinishing) {
                    statusView.text = next.message()
                    runButton.isEnabled = true
                    devicePath.isEnabled = true
                }
            }
        }
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private sealed class DiagnosticStatus {
    data class Result(val selfCheck: MfiSelfCheckResult) : DiagnosticStatus()
    data class Failure(val message: String) : DiagnosticStatus()

    fun message(): String = when (this) {
        is Failure -> "Failed: $message"
        is Result -> {
            val chip = selfCheck.chip ?: return if (selfCheck.discovery.interrupted) {
                "MFi scan interrupted"
            } else {
                "Found: none"
            }
            val major = when (val result = chip.protocolMajor) {
                is MfiProtocolMajorResult.Value -> "%d".format(result.major)
                is MfiProtocolMajorResult.MfiFailure -> result.error.message ?: result.error.javaClass.simpleName
                is MfiProtocolMajorResult.TransportFailure -> result.error.message ?: result.error.javaClass.simpleName
            }
            "Found: 0x%02X; device version: 0x%02X; protocol major (raw): %s".format(
                chip.address7Bit,
                chip.deviceVersion,
                major,
            )
        }
    }
}
