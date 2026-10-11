package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoInputCapacityTest {
    @Test fun lowResolutionStreamsDoNotReserveEightMiBPerInputBuffer() {
        assertEquals(512 * 1024, LowLatencyVideoPolicy.inputCapacity(270, 480))
        assertEquals(1024 * 1024, LowLatencyVideoPolicy.inputCapacity(540, 960))
        assertEquals(2 * 1024 * 1024, LowLatencyVideoPolicy.inputCapacity(1920, 480))
    }
    @Test fun largeCompressedFramesGrowTheNextConfigurationAndLimitsCannotOverflow() {
        assertEquals(4 * 1024 * 1024, LowLatencyVideoPolicy.inputCapacity(540, 960, 3 * 1024 * 1024))
        assertEquals(8 * 1024 * 1024, LowLatencyVideoPolicy.inputCapacity(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(512 * 1024, LowLatencyVideoPolicy.inputCapacity(-1, 0))
    }
}
