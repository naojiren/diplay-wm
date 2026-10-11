package com.shilapi.xcertplay.network

/** Android 8/9 choose the AP band themselves and may hide its channel from apps. */
internal object LocalHotspotRadioPolicy {
    fun supportsSystemBand(sdk: Int) = sdk in 26..28

    fun accepts(sdk: Int, frequencyMHz: Int?, bandLabel: String): Boolean =
        if (supportsSystemBand(sdk)) {
            frequencyMHz == null || frequencyMHz in 2412..2484 || frequencyMHz in 5160..5895
        } else {
            frequencyMHz?.let { it in 5160..5895 } ?: (bandLabel == "5 GHz")
        }

    fun channel(sdk: Int, frequencyMHz: Int?, configured: Int, requested: Int?): Int =
        frequencyMHz?.let(::wifiFrequencyMhzToChannel)
            ?: configured.takeIf { it > 0 }
            ?: requested
            ?: if (supportsSystemBand(sdk)) 0 else 36
}
