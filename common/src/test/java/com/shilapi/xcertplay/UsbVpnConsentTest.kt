package com.shilapi.xcertplay

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class UsbVpnConsentTest {
    @Test fun missingEx5SystemDialogBecomesAReportedFailure() {
        val missing = Intent().setClassName("com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog")
        val error = ActivityNotFoundException("Unable to find explicit activity class com.android.vpndialogs.ConfirmDialog")
        val result = UsbVpnConsent.request({ missing }, { intent ->
            assertEquals("com.android.vpndialogs.ConfirmDialog", intent.component?.className)
            throw error
        })
        assertTrue(result is UsbVpnConsent.Result.Failed)
        assertSame(error, (result as UsbVpnConsent.Result.Failed).error)
    }
    @Test fun priorAuthorizationNeedsNoDialog() {
        assertEquals(UsbVpnConsent.Result.Ready, UsbVpnConsent.request({ null }, { fail("Unexpected authorization") }))
    }
    @Test fun launchOnlyMeansPendingAndNeverPermissionGranted() {
        var launched = false
        assertEquals(UsbVpnConsent.Result.Pending, UsbVpnConsent.request({ Intent("VPN") }, { launched = true }))
        assertTrue(launched)
    }
    @Test fun revokedPermissionOrBrokenPreparationIsReported() {
        val error = SecurityException("VPN service unavailable")
        val result = UsbVpnConsent.request({ throw error }, { fail("Unexpected launch") })
        assertSame(error, (result as UsbVpnConsent.Result.Failed).error)
    }
}
