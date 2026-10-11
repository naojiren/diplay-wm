package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test fun lowRatesSurviveSanitizationAndLegacyFiveStepRatesStayCompatible() {
        for (fps in listOf(4, 8, 12, 18, 24, 30, 35, 60)) assertEquals(fps, AirPlayDisplaySettings.sanitizeFps(fps))
        assertEquals(4, AirPlayDisplaySettings.sanitizeFps(Int.MIN_VALUE))
        assertEquals(60, AirPlayDisplaySettings.sanitizeFps(Int.MAX_VALUE))
        assertEquals(35, AirPlayDisplaySettings.sanitizeFps(37))
        assertEquals(0, AirPlayDisplaySettings.fpsProgress(4))
        assertEquals(1, AirPlayDisplaySettings.fpsProgress(8))
        assertEquals(2, AirPlayDisplaySettings.fpsProgress(12))
        assertEquals(3, AirPlayDisplaySettings.fpsProgress(18))
        assertEquals(4, AirPlayDisplaySettings.fpsProgress(24))
    }

    @Test
    fun maximumObservedWidthScalesPhysicalSizeWithCurrentActivityWidth() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 768,
            currentHeightPixels = 800,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(80, size.widthMm)
        assertEquals(83, size.heightMm)
    }

    @Test
    fun maximumObservedHeightScalesPhysicalSizeWithCurrentActivityHeight() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 978,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
        )

        assertEquals(356, size.widthMm)
        assertEquals(181, size.heightMm)
    }

    @Test
    fun maximumResolutionKeepsConfiguredReferenceLength() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(250, size.widthMm)
        assertEquals(141, size.heightMm)
    }
}
