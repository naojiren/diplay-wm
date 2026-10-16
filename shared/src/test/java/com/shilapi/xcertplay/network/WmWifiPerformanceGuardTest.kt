package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WmWifiPerformanceGuardTest {
    @Test fun onlyThisHeadUnitAndPackageCanRequestTheRootRadioLease() {
        assertTrue(WmWifiPerformanceGuard.eligible(23, "CQ1301", "j6headunitboard", "com.shihab.diplay.wmex5"))
        assertFalse(WmWifiPerformanceGuard.eligible(24, "CQ1301", "j6headunitboard", "com.shihab.diplay.wmex5"))
        assertFalse(WmWifiPerformanceGuard.eligible(23, "Other", "j6headunitboard", "com.shihab.diplay.wmex5"))
        assertFalse(WmWifiPerformanceGuard.eligible(23, "CQ1301", "other", "com.shihab.diplay.wmex5"))
        assertFalse(WmWifiPerformanceGuard.eligible(23, "CQ1301", "j6headunitboard", "com.shihab.diplay"))
    }
    @Test fun helperPathsCannotBecomeShellCommands() {
        assertEquals("'/data/user/0/a/files/guard'", WmWifiPerformanceGuard.shellQuote("/data/user/0/a/files/guard"))
        assertEquals("'/a'\\'';echo injected'", WmWifiPerformanceGuard.shellQuote("/a';echo injected"))
    }
    @Test fun launchRequiresBothAdbAcknowledgementAndLiveDriverReadback() {
        val reply = "WM radio guard launched=1 pid=999"
        val active = "WM radio guard active=1 originalPm=2 originalScanSuppress=0 originalSleepReturnMs=200"
        assertTrue(WmWifiPerformanceGuard.launchConfirmed(reply, active))
        assertFalse(WmWifiPerformanceGuard.launchConfirmed("", active))
        assertFalse(WmWifiPerformanceGuard.launchConfirmed(reply, "WM radio guard queryFailed errno=1"))
        assertFalse(WmWifiPerformanceGuard.launchConfirmed(reply, active + "\nWM radio guard restored=true"))
        assertFalse(WmWifiPerformanceGuard.launchConfirmed("WM radio guard launched=0 pid=999", active))
    }
}
