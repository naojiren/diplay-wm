package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** One-time EX5 upgrade defaults; subsequent driver choices remain editable. */
internal object WmEx5Startup {
    @Synchronized fun applyDefaults(context: Context) {
        if (!WmEx5PerformanceProfile.isTestPackage(context)) return
        val prefs = context.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("perf6_manufacturer_applied", false)) {
            AirPlayPersistence.saveManufacturer(context, WmEx5PerformanceProfile.MANUFACTURER)
            prefs.edit().putBoolean("perf6_manufacturer_applied", true).commit()
        }
        if (!prefs.getBoolean("perf5_cluster_defaults_applied", false)) {
            WmEx5ClusterProfile.setResolutionPercent(context, WmEx5ClusterProfile.DEFAULT_RESOLUTION_PERCENT)
            WmEx5ClusterProfile.setFps(context, WmEx5ClusterProfile.DEFAULT_FPS)
            WmEx5ClusterProfile.setNativeSize(context, false)
            prefs.edit().putBoolean("perf5_cluster_defaults_applied", true).commit()
            Log.i("DiPlayWM", "EX5 cluster defaults applied: resolution=${WmEx5ClusterProfile.DEFAULT_RESOLUTION_PERCENT}% fps=${WmEx5ClusterProfile.DEFAULT_FPS} centered-fit")
        }
        applyLegacyDefaults(context)
        if (!prefs.getBoolean("perf11_layout_defaults_applied", false)) {
            // Apply only the values requested for this upgrade. Keep connection mode,
            // credentials, audio, instrument quality and the other margins as saved.
            AirPlayPersistence.saveFps(context, WmEx5PerformanceProfile.DEFAULT_FPS)
            AirPlayPersistence.saveDisplayScalePercent(context, WmEx5PerformanceProfile.DEFAULT_RESOLUTION_PERCENT)
            WmEx5PerformanceProfile.saveChannel(context, WmEx5PerformanceProfile.DEFAULT_CHANNEL)
            WmEx5ClusterProfile.setMargin(context, "left", WmEx5ClusterProfile.DEFAULT_LEFT_MARGIN)
            WmEx5ClusterProfile.setMargin(context, "bottom", WmEx5ClusterProfile.DEFAULT_BOTTOM_MARGIN)
            prefs.edit().putBoolean("perf11_layout_defaults_applied", true).commit()
            Log.i("DiPlayWM", "EX5 layout defaults applied: main=50%/${WmEx5PerformanceProfile.DEFAULT_FPS}fps channel=${WmEx5PerformanceProfile.DEFAULT_CHANNEL} clusterMargins=left24%/bottom5%")
        }
    }

    private fun applyLegacyDefaults(context: Context) {
        val prefs = context.getSharedPreferences("diplay_wm_ex5_performance", Context.MODE_PRIVATE)
        if (prefs.getBoolean("perf4_defaults_applied", false)) return
        AirPlayPersistence.saveFps(context, WmEx5PerformanceProfile.DEFAULT_FPS)
        AirPlayPersistence.saveDisplayScalePercent(context, 40)
        // Only initialize the previous profile on installations that have not run perf3.
        // A perf3 upgrade changes resolution/fps without resetting other driver choices.
        if (!prefs.getBoolean("perf3_defaults_applied", false)) {
            // The supplied logs show HEVC selected on the API 23 TI decoder. Start with H.264.
            AirPlayPersistence.saveHevcEnabled(context, false)
            WmEx5ClusterProfile.setEnabled(context, true)
            // Earlier EX5 builds ignored the generic mode preference. Keep an explicitly saved
            // same-router connection, otherwise continue using the fixed-channel Wi-Fi Direct path.
            if (AirPlayPersistence.loadWirelessHotspotMode(context) != WirelessHotspotMode.EXISTING_WIFI) {
                AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
            }
        }
        prefs.edit().putBoolean("perf3_defaults_applied", true).putBoolean("perf4_defaults_applied", true).commit()
        Log.i("DiPlayWM", "EX5 legacy defaults applied: fps=${AirPlayPersistence.loadFps(context)} resolution=40%")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun enableBluetooth(context: Context) {
        if (!WmEx5PerformanceProfile.isTestPackage(context) || Build.VERSION.SDK_INT >= 31) return
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
            when (adapter?.state) {
                BluetoothAdapter.STATE_OFF -> Log.i("DiPlayWM", "Bluetooth startup enable accepted=${adapter.enable()}")
                null -> Log.i("DiPlayWM", "Bluetooth startup: adapter unavailable")
                else -> Unit // Already enabled or changing state; do not issue duplicate requests.
            }
        } catch (error: Exception) {
            Log.w("DiPlayWM", "Bluetooth startup enable failed", error)
        }
    }
}
