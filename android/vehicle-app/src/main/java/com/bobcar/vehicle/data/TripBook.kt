package com.bobcar.vehicle.data

import androidx.room.withTransaction
import com.bobcar.vehicle.trip.Purpose

/**
 * Keeps the logbook's trip rows and the vehicle baseline in step with driving events.
 * All times are wall-clock milliseconds. Calls must come one at a time (see [TripRecorder]).
 */
class TripBook(private val db: BobCarDatabase) {

    /** Odometer and battery at the moment of an event. */
    data class Reading(val odometerKm: Float?, val socPercent: Int?)

    /** What navigation guidance said when it started. */
    data class Route(val destinationName: String, val tollFare: Int)

    /** Who drives: confirmed on the driver card, or the default recorded as provisional. */
    data class DriverChoice(val driverId: String, val provisional: Boolean)

    /**
     * Driver and purpose the trip is saved with when it ends (trip-end card answer, or the suggestion).
     * [driverId] null keeps the trip's driver. A PERSONAL trip loses its destination (CLAUDE.md rule 6).
     */
    data class EndOutcome(
        val driverId: String?,
        val driverProvisional: Boolean,
        val purpose: Purpose,
        val purposeProvisional: Boolean,
    )

    private val trips = db.tripDao()
    private val baselines = db.vehicleBaselineDao()

    /**
     * Guidance started while no trip was running (usually set up in P before leaving).
     * Attached to the next trip, dropped when a trip ends. Memory only.
     */
    private var pendingRoute: Route? = null

    /**
     * Starts a trip, or continues the last one when [merged]. Returns the running trip.
     * [driver] goes on a new trip; a continued trip keeps its driver (merging needs the same driver).
     */
    suspend fun onTripStarted(
        at: Long,
        merged: Boolean,
        reading: Reading,
        driver: DriverChoice? = null,
    ): TripEntity = db.withTransaction {
        // Already running (e.g. app restarted mid-trip): keep it, never open a second one
        trips.getActive()?.let { return@withTransaction it }

        val trip = (if (merged) reopenLastEnded() else null) ?: run {
            val route = pendingRoute
            val new = TripEntity(
                status = TripEntity.STATUS_ACTIVE,
                startedAt = at,
                startOdometerKm = reading.odometerKm,
                driverId = driver?.driverId,
                driverProvisional = driver?.provisional ?: true,
                destinationName = route?.destinationName?.takeIf { it.isNotBlank() },
                expectedTollFare = route?.tollFare,
            )
            new.copy(id = trips.insert(new))
        }
        pendingRoute = null
        updateBaseline(at) { it.copy(activeTripId = trip.id) }
        trip
    }

    /** Guidance started: goes on the running trip (latest wins), or waits for the next trip. */
    suspend fun onRouteStarted(route: Route): TripEntity? {
        val active = trips.getActive()
        if (active == null) {
            pendingRoute = route
            return null
        }
        val updated = active.copy(
            destinationName = route.destinationName.takeIf { it.isNotBlank() },
            expectedTollFare = route.tollFare,
        )
        trips.update(updated)
        return updated
    }

    /** The driver was confirmed while a trip runs: the running trip gets that driver, no longer provisional. */
    suspend fun onDriverConfirmed(driverId: String): TripEntity? {
        val active = trips.getActive() ?: return null
        val updated = active.copy(driverId = driverId, driverProvisional = false)
        trips.update(updated)
        return updated
    }

    /** Fills the start odometer when the trip started before the first odometer value came in. */
    suspend fun onOdometer(km: Float): TripEntity? {
        val active = trips.getActive() ?: return null
        if (active.startOdometerKm != null) return null
        val updated = active.copy(startOdometerKm = km)
        trips.update(updated)
        return updated
    }

    /**
     * Ends the running trip with [outcome] (driver and purpose) and records the baseline the next driver
     * is compared against.
     */
    suspend fun onTripEnded(
        at: Long,
        reason: String,
        reading: Reading,
        outcome: EndOutcome? = null,
    ): TripEntity? = db.withTransaction {
        pendingRoute = null
        val active = trips.getActive() ?: return@withTransaction null
        val withOutcome = if (outcome == null) active else active.copy(
            driverId = outcome.driverId ?: active.driverId,
            driverProvisional = if (outcome.driverId == null) active.driverProvisional else outcome.driverProvisional,
            purpose = outcome.purpose.name,
            purposeProvisional = outcome.purposeProvisional,
            destinationName = if (outcome.purpose == Purpose.PERSONAL) null else active.destinationName,
        )
        val ended = withOutcome.copy(
            status = TripEntity.STATUS_ENDED,
            endedAt = at,
            endOdometerKm = reading.odometerKm,
            endReason = reason,
        )
        trips.update(ended)
        updateBaseline(at) {
            it.copy(
                lastOdometerKm = reading.odometerKm ?: it.lastOdometerKm,
                lastSocPercent = reading.socPercent ?: it.lastSocPercent,
                lastTripEndedAt = at,
                activeTripId = null,
            )
        }
        ended
    }

    /**
     * App or service came back. Lines up the stored trip with what the car is doing now:
     * driving with no stored trip → start one now; a stored trip but not driving → it ended while
     * the app was off, close it now ([TripEntity.END_APP_RESTART]). Otherwise nothing changes.
     * [drivingNow] null = gear not known yet: leave everything as it is until it is.
     * [driver] is used only for a trip started here (nobody saw the driver card); [endOutcome] only for a
     * trip closed here (nobody saw the trip-end card).
     */
    suspend fun onAppResumed(
        at: Long,
        drivingNow: Boolean?,
        reading: Reading,
        driver: DriverChoice? = null,
        endOutcome: EndOutcome? = null,
    ): TripEntity? {
        val active = trips.getActive()
        return when {
            drivingNow == true && active == null -> onTripStarted(at, merged = false, reading, driver)
            drivingNow == false && active != null -> onTripEnded(at, TripEntity.END_APP_RESTART, reading, endOutcome)
            else -> active
        }
    }

    private suspend fun reopenLastEnded(): TripEntity? {
        val last = trips.getLastEnded() ?: return null
        // The purpose is decided again on the next trip-end card (a deleted personal destination stays deleted)
        val reopened = last.copy(
            status = TripEntity.STATUS_ACTIVE,
            endedAt = null,
            endOdometerKm = null,
            endReason = null,
            purpose = null,
            purposeProvisional = true,
        )
        trips.update(reopened)
        return reopened
    }

    private suspend fun updateBaseline(at: Long, change: (VehicleBaselineEntity) -> VehicleBaselineEntity) {
        val current = baselines.get() ?: VehicleBaselineEntity(updatedAt = at)
        baselines.upsert(change(current).copy(updatedAt = at))
    }
}
