package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoBacklogRecoveryTest {
    @Test fun repeatedBacklogsCannotPostponeTheRecoveryDeadline() {
        val recovery = VideoBacklogRecovery(100)
        assertTrue(recovery.begin(10))
        assertTrue(recovery.begin(90))
        assertFalse(recovery.begin(110))
        assertTrue(recovery.expired(110))
    }

    @Test fun renderingAnOldHardwareOutputDoesNotHideAnUnrecoveredStream() {
        val recovery = VideoBacklogRecovery(100)
        recovery.begin(10)
        recovery.onRendered(9)
        assertTrue(recovery.expired(110))
    }

    @Test fun renderingANewOutputAllowsTheNextBacklogAnIndependentAttempt() {
        val recovery = VideoBacklogRecovery(100)
        recovery.begin(10)
        recovery.onRendered(11)
        assertFalse(recovery.expired(200))
        assertTrue(recovery.begin(200))
        assertFalse(recovery.expired(299))
        assertTrue(recovery.expired(300))
    }

    @Test fun hardRecoveryAndShutdownClearTheSoftRecoveryDeadline() {
        val recovery = VideoBacklogRecovery(100)
        recovery.begin(0)
        recovery.reset()
        assertFalse(recovery.expired(500))
        assertTrue(recovery.begin(500))
    }
}
