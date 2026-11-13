package com.bobcar.vehicle.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class SeatOccupancyTest {

    @Test
    fun sdk232IsInverted() {
        assertEquals(false, seatOccupiedFromSdk(raw = true, sdkVersion = "2.3.2"))
        assertEquals(true, seatOccupiedFromSdk(raw = false, sdkVersion = "2.3.2"))
    }

    @Test
    fun otherVersionsAreTakenAsIs() {
        listOf("2.3.3", "2.4.0", "3.0.0", "2.3.1").forEach { version ->
            assertEquals(version, true, seatOccupiedFromSdk(raw = true, sdkVersion = version))
            assertEquals(version, false, seatOccupiedFromSdk(raw = false, sdkVersion = version))
        }
    }
}
