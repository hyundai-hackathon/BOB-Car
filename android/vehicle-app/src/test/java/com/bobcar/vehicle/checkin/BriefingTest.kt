package com.bobcar.vehicle.checkin

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefingTest {

    @Test
    fun sameOdometerIsNoUnrecordedDrive() {
        assertEquals(Unrecorded.None, Briefing.unrecorded(12_362f, 12_362f))
    }

    @Test
    fun halfAKilometreOrLessIsNoUnrecordedDrive() {
        assertEquals(Unrecorded.None, Briefing.unrecorded(12_362f, 12_362.5f))
    }

    @Test
    fun moreThanHalfAKilometreIsAnUnrecordedDrive() {
        assertEquals(Unrecorded.Found(6f), Briefing.unrecorded(12_362f, 12_368f))
    }

    @Test
    fun missingZeroOrBackwardsValuesAreUnknown() {
        assertEquals(Unrecorded.Unknown, Briefing.unrecorded(null, 12_368f))
        assertEquals(Unrecorded.Unknown, Briefing.unrecorded(12_362f, null))
        assertEquals(Unrecorded.Unknown, Briefing.unrecorded(0f, 12_368f)) // baseline taken right after an emulator reset
        assertEquals(Unrecorded.Unknown, Briefing.unrecorded(12_362f, 0f))
        assertEquals(Unrecorded.Unknown, Briefing.unrecorded(12_362f, 500f)) // odometer went backwards
    }

    @Test
    fun briefingKeepsBatteryAndOdometer() {
        val briefing = Briefing.of(socPercent = 78, odometerKm = 12_482f, lastRecordedOdometerKm = 12_482f)
        assertEquals(78, briefing.socPercent)
        assertEquals(12_482f, briefing.odometerKm)
        assertEquals(Unrecorded.None, briefing.unrecorded)
    }
}
