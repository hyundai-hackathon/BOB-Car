package com.bobcar.vehicle.checkin

import com.bobcar.shared.consent.ConsentNotice
import com.bobcar.vehicle.data.DriverConsentEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear

/** Whether this answer lets the driver's records go to the server: agreed to the current notice. */
fun DriverConsentEntity?.isAgreed(): Boolean =
    this != null && status == DriverConsentEntity.STATUS_AGREED && noticeVersion >= ConsentNotice.VERSION

/**
 * The consent card is up when the confirmed driver has not agreed yet and has not answered during this
 * stay in the seat, and the car is stopped in P before or after a trip (CLAUDE.md rule 3).
 * - Only a confirmed driver is asked: a provisional default may not be the person in the seat.
 * - During the end countdown the trip-end card comes first, so it waits until the trip has ended.
 * - Leaving P hides it; it comes back at the next stop in P, or at the next check-in.
 * - [consents] null = not loaded yet: nothing is shown rather than asking someone who already agreed.
 */
fun showsConsentCard(
    state: DrivingState,
    checkin: CheckinState?,
    gear: Gear?,
    consents: List<DriverConsentEntity>?,
): Boolean {
    if (gear != Gear.PARK) return false
    if (state !is DrivingState.CheckingIn && state !is DrivingState.Parked) return false
    val driverId = checkin?.confirmedDriverId ?: return false
    if (driverId in checkin.consentAnswered) return false
    if (consents == null) return false
    return !consents.firstOrNull { it.driverId == driverId }.isAgreed()
}
