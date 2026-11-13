package com.bobcar.vehicle.checkin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverCheckinTest {

    private val default = DefaultDriver("drv-001", DefaultSource.RESERVATION)

    @Test
    fun uploadsOfTheStayWaitWhileTheCardIsOpen() {
        val checkin = DriverCheckin()
        checkin.start(10_000, default)

        assertEquals(10_000 - CheckinState.HOLD_MARGIN_MS, checkin.current!!.uploadHoldSince)
    }

    @Test
    fun confirmingTheDriverReleasesThem() {
        val checkin = DriverCheckin()
        checkin.start(10_000, default)
        checkin.confirm("drv-002")

        assertNull(checkin.current!!.uploadHoldSince)
    }

    @Test
    fun closingTheCardUnansweredReleasesThemAsTheProvisionalDefault() {
        val checkin = DriverCheckin()
        checkin.start(10_000, default)
        checkin.close()

        assertNull(checkin.current!!.uploadHoldSince)
    }
}
