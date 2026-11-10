package com.bobcar.vehicle.data

import androidx.room.withTransaction

/**
 * Keeps the logbook's trip rows and the vehicle baseline in step with driving events.
 * All times are wall-clock milliseconds. Calls must come one at a time (see [TripRecorder]).
 */
class TripBook(private val db: BobCarDatabase) {

    /** Odometer and battery at the moment of an event. */
    data class Reading(val odometerKm: Float?, val socPercent: Int?)

    /** What navigation guidance said when it started. */
    data class Route(val destinationName: String, val tollFare: Int)

    private val trips = db.tripDao()
    private val baselines = db.vehicleBaselineDao()

    /**
     * Guidance started while no trip was running (usually set up in P before leaving).
     * Attached to the next trip, dropped when a trip ends. Memory only.
     */
    private var pendingRoute: Route? = null

    /** Starts a trip, or continues the last one when [merged]. Returns the running trip. */
    suspend fun onTripStarted(at: Long, merged: Boolean, reading: Reading): TripEntity = db.withTransaction {
        // Already running (e.g. app restarted mid-trip): keep it, never open a second one
        trips.getActive()?.let { return@withTransaction it }

        val trip = (if (merged) reopenLastEnded() else null) ?: run {
            val route = pendingRoute
            val new = TripEntity(
                status = TripEntity.STATUS_ACTIVE,
                startedAt = at,
                startOdometerKm = reading.odometerKm,
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

    /** Fills the start odometer when the trip started before the first odometer value came in. */
    suspend fun onOdometer(km: Float): TripEntity? {
        val active = trips.getActive() ?: return null
        if (active.startOdometerKm != null) return null
        val updated = active.copy(startOdometerKm = km)
        trips.update(updated)
        return updated
    }

    /** Ends the running trip and records the baseline the next driver is compared against. */
    suspend fun onTripEnded(at: Long, reason: String, reading: Reading): TripEntity? = db.withTransaction {
        pendingRoute = null
        val active = trips.getActive() ?: return@withTransaction null
        val ended = active.copy(
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
     */
    suspend fun onAppResumed(at: Long, drivingNow: Boolean?, reading: Reading): TripEntity? {
        val active = trips.getActive()
        return when {
            drivingNow == true && active == null -> onTripStarted(at, merged = false, reading)
            drivingNow == false && active != null -> onTripEnded(at, TripEntity.END_APP_RESTART, reading)
            else -> active
        }
    }

    private suspend fun reopenLastEnded(): TripEntity? {
        val last = trips.getLastEnded() ?: return null
        val reopened = last.copy(
            status = TripEntity.STATUS_ACTIVE,
            endedAt = null,
            endOdometerKm = null,
            endReason = null,
        )
        trips.update(reopened)
        return reopened
    }

    private suspend fun updateBaseline(at: Long, change: (VehicleBaselineEntity) -> VehicleBaselineEntity) {
        val current = baselines.get() ?: VehicleBaselineEntity(updatedAt = at)
        baselines.upsert(change(current).copy(updatedAt = at))
    }
}
