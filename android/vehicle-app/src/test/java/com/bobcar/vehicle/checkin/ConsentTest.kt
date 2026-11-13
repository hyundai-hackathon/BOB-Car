package com.bobcar.vehicle.checkin

import com.bobcar.vehicle.data.DriverConsentEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentTest {

    private val checkingIn = DrivingState.CheckingIn(since = 0, checkinClosed = false)
    private val default = DefaultDriver("drv-001", DefaultSource.RESERVATION)
    private val confirmed = CheckinState(boardedAt = 0, default = default, confirmedDriverId = "drv-001")

    private fun answer(status: String, version: Int = ConsentNotice.VERSION) =
        DriverConsentEntity("drv-001", status, decidedAt = 1, noticeVersion = version)

    @Test
    fun confirmedDriverWithoutAnswerSeesTheCardOnFirstCheckin() {
        assertTrue(showsConsentCard(checkingIn, confirmed, Gear.PARK, emptyList()))
    }

    @Test
    fun provisionalDefaultIsNotAsked() {
        assertFalse(showsConsentCard(checkingIn, CheckinState(0, default), Gear.PARK, emptyList()))
    }

    @Test
    fun agreedDriverIsNotAskedAgainUnlessTheNoticeChanged() {
        assertFalse(showsConsentCard(checkingIn, confirmed, Gear.PARK, listOf(answer(DriverConsentEntity.STATUS_AGREED))))
        assertTrue(showsConsentCard(checkingIn, confirmed, Gear.PARK, listOf(answer(DriverConsentEntity.STATUS_AGREED, version = 0))))
    }

    @Test
    fun laterHidesItForThisStayAndAsksAgainAtTheNextCheckin() {
        val deferred = listOf(answer(DriverConsentEntity.STATUS_DEFERRED))
        assertFalse(showsConsentCard(checkingIn, confirmed.copy(consentAnswered = setOf("drv-001")), Gear.PARK, deferred))
        assertTrue(showsConsentCard(checkingIn, confirmed, Gear.PARK, deferred))
    }

    @Test
    fun onlyInParkBeforeOrAfterATrip() {
        assertFalse(showsConsentCard(checkingIn, confirmed, Gear.DRIVE, emptyList()))
        assertFalse(showsConsentCard(checkingIn, confirmed, null, emptyList()))
        assertFalse(showsConsentCard(DrivingState.EndCountdown(tripStartedAt = 0, deadline = 10_000), confirmed, Gear.PARK, emptyList()))
        // Left P while the card was up → back at the next stop in P after the trip
        assertTrue(showsConsentCard(DrivingState.Parked(tripEndedAt = 20_000), confirmed, Gear.PARK, emptyList()))
        // Brake pressed in P closes the driver card, not the consent card
        assertTrue(showsConsentCard(checkingIn.copy(checkinClosed = true), confirmed.copy(closed = true), Gear.PARK, emptyList()))
    }

    @Test
    fun nothingShowsBeforeTheAnswersAreLoaded() {
        assertFalse(showsConsentCard(checkingIn, confirmed, Gear.PARK, null))
    }
}
