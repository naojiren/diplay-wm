package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class LocalHotspotRadioPolicyTest {
    @Test fun androidEightAndNineAcceptSystemGeneratedTwoPointFourGhzHotspots() {
        for (sdk in 26..28) {
            assertTrue(LocalHotspotRadioPolicy.accepts(sdk, 2437, "2.4 GHz"))
            assertTrue(LocalHotspotRadioPolicy.accepts(sdk, null, "2.4 GHz"))
            assertEquals(6, LocalHotspotRadioPolicy.channel(sdk, 2437, 0, null))
            assertEquals(11, LocalHotspotRadioPolicy.channel(sdk, null, 11, null))
        }
    }

    @Test fun unknownLegacyChannelIsAutoAndNeverInventedFiveGhzChannel() {
        for (sdk in 26..28) {
            assertTrue(LocalHotspotRadioPolicy.accepts(sdk, null, "unknown"))
            assertEquals(0, LocalHotspotRadioPolicy.channel(sdk, null, 0, null))
            assertEquals(149, LocalHotspotRadioPolicy.channel(sdk, 5745, 0, null))
            assertFalse(LocalHotspotRadioPolicy.accepts(sdk, 6000, "unknown"))
        }
    }

    @Test fun newerFirmwareRetainsItsFiveGhzPolicy() {
        for (sdk in listOf(29, 30, 32, 33, 36)) {
            assertFalse(LocalHotspotRadioPolicy.accepts(sdk, 2437, "2.4 GHz"))
            assertFalse(LocalHotspotRadioPolicy.accepts(sdk, null, "2.4 GHz"))
            assertTrue(LocalHotspotRadioPolicy.accepts(sdk, 5180, "5 GHz"))
            assertEquals(36, LocalHotspotRadioPolicy.channel(sdk, null, 0, null))
        }
    }
}
