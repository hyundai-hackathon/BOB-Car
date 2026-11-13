package com.bobcar.vehicle.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEndChoicesTest {

    private val suggested = EndChoice(
        tripId = 7,
        driverId = "drv-001",
        driverConfirmed = false,
        purpose = Purpose.BUSINESS,
        purposeSource = PurposeSource.CUSTOMER,
    )

    @Test
    fun nothingToChooseWithoutACard() {
        val choices = TripEndChoices()

        assertNull(choices.choosePurpose(Purpose.PERSONAL))
        assertNull(choices.chooseDriver("drv-002"))
        assertNull(choices.current)
    }

    @Test
    fun tappingAPurposeConfirmsThePurposeAndTheDriverShown() {
        val choices = TripEndChoices().apply { set(suggested) }

        val chosen = choices.choosePurpose(Purpose.PERSONAL)!!

        assertEquals(Purpose.PERSONAL, chosen.purpose)
        assertTrue(chosen.answered)
        assertTrue(chosen.driverConfirmed)
        assertEquals("drv-001", chosen.driverId)
    }

    @Test
    fun tappingAPurposeWithNoDriverConfirmsNoDriver() {
        val choices = TripEndChoices().apply { set(suggested.copy(driverId = null)) }

        assertFalse(choices.choosePurpose(Purpose.COMMUTE)!!.driverConfirmed)
    }

    @Test
    fun choosingAnotherDriverKeepsTheSuggestedPurpose() {
        val choices = TripEndChoices().apply { set(suggested) }

        val chosen = choices.chooseDriver("drv-002")!!

        assertEquals("drv-002", chosen.driverId)
        assertTrue(chosen.driverConfirmed)
        assertTrue(chosen.answered)
        assertEquals(Purpose.BUSINESS, chosen.purpose)
    }

    @Test
    fun clearRemovesTheCard() {
        val choices = TripEndChoices().apply { set(suggested) }

        choices.clear()

        assertNull(choices.current)
    }
}
