package com.bobcar.vehicle.checkin

import com.bobcar.vehicle.data.BobCarDatabase

/** Where the suggested driver came from. */
enum class DefaultSource { PROFILE, RESERVATION, LAST_DRIVER }

/** The driver shown first on the driver card, recorded as provisional when nobody confirms. */
data class DefaultDriver(val driverId: String, val source: DefaultSource)

/**
 * The one place that decides the default driver (CLAUDE.md rule 5):
 * ① driver linked to the vehicle profile → ② driver who reserved this time → ③ previous driver.
 * Only active drivers on the list count; a candidate that is not on it is skipped.
 */
class DriverDefaults(
    db: BobCarDatabase,
    private val profile: VehicleProfileLink = NoProfileLink,
) {
    private val drivers = db.driverDao()
    private val reservations = db.reservationDao()
    private val trips = db.tripDao()

    /** [at] is wall-clock milliseconds. null when no candidate is known. */
    suspend fun resolve(at: Long): DefaultDriver? =
        candidate(profile.linkedDriverId(), DefaultSource.PROFILE)
            ?: candidate(reservations.getAt(at)?.driverId, DefaultSource.RESERVATION)
            ?: candidate(trips.getLastWithDriver()?.driverId, DefaultSource.LAST_DRIVER)

    private suspend fun candidate(driverId: String?, source: DefaultSource): DefaultDriver? {
        if (driverId.isNullOrBlank()) return null
        drivers.getActiveById(driverId) ?: return null
        return DefaultDriver(driverId, source)
    }
}
