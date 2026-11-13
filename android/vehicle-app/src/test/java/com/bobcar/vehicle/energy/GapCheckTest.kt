package com.bobcar.vehicle.energy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GapCheckTest {

    @Test
    fun from23To68PercentIsAChargeOfAbout34Point8Kwh() {
        val charge = GapCheck.charge(23, 68, 77_400f)!!

        assertEquals(45, charge.gainedPercent)
        assertEquals(34.83f, charge.kwh!!, 0.01f)
    }

    @Test
    fun gainBelowFivePointsIsNotACharge() {
        assertNull(GapCheck.charge(23, 27, 77_400f))
        assertNull(GapCheck.charge(23, 23, 77_400f))
        assertNull(GapCheck.charge(68, 23, 77_400f))
    }

    @Test
    fun gainOfExactlyFivePointsIsACharge() {
        assertEquals(5, GapCheck.charge(23, 28, 77_400f)!!.gainedPercent)
    }

    @Test
    fun chargeWithoutCapacityHasNoKwh() {
        assertEquals(Charge(45, null), GapCheck.charge(23, 68, null))
        assertEquals(Charge(45, null), GapCheck.charge(23, 68, 0f))
    }

    @Test
    fun missingOrOutOfRangeBatteryValuesGiveNoCharge() {
        assertNull(GapCheck.charge(null, 68, 77_400f))
        assertNull(GapCheck.charge(23, null, 77_400f))
        assertNull(GapCheck.charge(-1, 68, 77_400f))
        assertNull(GapCheck.charge(23, 101, 77_400f))
    }

    @Test
    fun odometerGainAboveHalfAKilometreIsAnUnrecordedDrive() {
        assertEquals(6f, GapCheck.unrecordedKm(12_362f, 12_368f))
        assertNull(GapCheck.unrecordedKm(12_362f, 12_362.5f))
        assertNull(GapCheck.unrecordedKm(12_362f, 12_362f))
    }

    @Test
    fun odometerThatCannotBeComparedGivesNoUnrecordedDrive() {
        assertNull(GapCheck.unrecordedKm(null, 12_368f))
        assertNull(GapCheck.unrecordedKm(12_362f, null))
        assertNull(GapCheck.unrecordedKm(12_362f, 500f)) // went backwards
        assertNull(GapCheck.unrecordedKm(0f, 12_368f))
    }
}
