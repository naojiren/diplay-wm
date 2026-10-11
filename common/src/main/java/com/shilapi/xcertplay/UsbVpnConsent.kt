package com.shilapi.xcertplay

import android.content.Intent

/** Vendor firmware can return a VPN consent intent whose system activity is absent. */
internal object UsbVpnConsent {
    sealed interface Result {
        data object Ready : Result
        data object Pending : Result
        data class Failed(val error: Exception) : Result
    }

    fun request(prepare: () -> Intent?, launch: (Intent) -> Unit): Result = try {
        val intent = prepare()
        if (intent == null) Result.Ready else {
            launch(intent)
            Result.Pending
        }
    } catch (error: Exception) {
        Result.Failed(error)
    }
}
