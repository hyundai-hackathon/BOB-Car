package com.bobcar.vehicle.signal

/** Gear as the app understands it. Manual gears 1..9 count as DRIVE. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE }

/** Destination info received when navigation guidance starts. */
data class RouteStart(
    val tollFare: Int,
    val distanceMeters: Int,
    val destinationName: String,
    val at: Long,
)

/**
 * A place from navigation (guidance destination or bookmark), used to suggest the trip purpose.
 * Coordinates are for comparison only: never stored in the DB or logged (personal data).
 */
data class NaviPlace(
    val name: String,
    val poiId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** The driver's navigation bookmarks HOME and OFFICE; null when that one is not set. */
data class CommutePlaces(val home: NaviPlace?, val office: NaviPlace?)

/** Latest cleaned value of every signal. null = not received yet. */
data class VehicleSnapshot(
    val driverSeatOccupied: Boolean? = null,
    val gear: Gear? = null,
    val brakePressed: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val odometerKm: Float? = null,
    val socPercent: Int? = null,
    val batteryCapacityWh: Float? = null,
    val lastRouteStart: RouteStart? = null,
    /** Details (poiId, coordinates) of the latest guidance destination. Empty answers keep the previous one. */
    val destinationPlace: NaviPlace? = null,
    /** Navigation bookmarks HOME and OFFICE. null = not read yet. Re-read at every trip start (no change event, day1-check 14-5). */
    val commutePlaces: CommutePlaces? = null,
) {
    /** Remaining battery energy = SoC × capacity (the console's Energy slider is not used, B10). */
    val batteryEnergyWh: Float?
        get() = if (socPercent != null && batteryCapacityWh != null) socPercent * batteryCapacityWh / 100f else null
}

/** A change worth reacting to. Initial values only update the snapshot and produce no event. */
sealed interface SignalEvent {
    val at: Long

    data class DriverSeatChanged(val occupied: Boolean, override val at: Long) : SignalEvent
    data class GearChanged(val from: Gear, val to: Gear, override val at: Long) : SignalEvent
    data class BrakePressed(override val at: Long) : SignalEvent
    data class DriverDoorChanged(val open: Boolean, override val at: Long) : SignalEvent
    data class OdometerChanged(val km: Float, override val at: Long) : SignalEvent
    data class SocChanged(val percent: Int, override val at: Long) : SignalEvent
    data class RouteStarted(val route: RouteStart) : SignalEvent {
        override val at: Long get() = route.at
    }
}
