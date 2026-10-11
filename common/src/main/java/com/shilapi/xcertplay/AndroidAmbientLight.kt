package com.shilapi.xcertplay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Activity-owned adapter. Sensor events and delayed transitions share the main looper. */
internal class AndroidAmbientLight(context: Context) :
    CarPlayNightModeController.LightSource, SensorEventListener {
    private val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.getSystemService(SensorManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private var onLux: ((Float) -> Unit)? = null
    override val available: Boolean get() = sensor != null

    override fun start(onLux: (Float) -> Unit): Boolean {
        stop()
        val light = sensor ?: return false
        this.onLux = onLux
        val registered = runCatching {
            manager?.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL, handler) == true
        }.getOrDefault(false)
        if (!registered) stop()
        return registered
    }

    override fun stop() {
        onLux = null
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            onLux?.invoke(event.values.firstOrNull() ?: Float.NaN)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

internal class MainThreadNightModeScheduler : CarPlayNightModeController.Scheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun postDelayed(task: Runnable, delayMillis: Long) {
        handler.postDelayed(task, delayMillis)
    }
    override fun remove(task: Runnable) {
        handler.removeCallbacks(task)
    }
}
