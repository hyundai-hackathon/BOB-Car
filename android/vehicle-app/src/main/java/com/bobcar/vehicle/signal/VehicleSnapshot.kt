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
