package com.bobcar.vehicle.checkin

/**
 * The driver linked to the vehicle profile that is signed in now, if any.
 *
 * Interface only: how a Pleos profile relates to an Android user (and so to a driver) is not
 * checked yet (day1-check 15). Implement it after that result; until then [NoProfileLink] is used
 * and the default driver comes from the reservation or the previous driver.
 */
fun interface VehicleProfileLink {
    suspend fun linkedDriverId(): String?
}

/** No profile information yet: never suggests a driver. */
val NoProfileLink = VehicleProfileLink { null }
