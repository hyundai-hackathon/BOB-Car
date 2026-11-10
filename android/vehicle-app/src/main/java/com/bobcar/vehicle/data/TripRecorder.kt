package com.bobcar.vehicle.data

import android.util.Log
import com.bobcar.vehicle.driving.DrivingEvent
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals
import com.bobcar.vehicle.util.maskForLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feeds [TripBook] from driving events (start, end, resume), navigation guidance and odometer values.
 * Readings are taken from the signal snapshot on the signal thread when the event happens, then
 * written one at a time in arrival order. Logs mask the destination (CLAUDE.md rule 6).
 */
class TripRecorder(db: BobCarDatabase, private val signals: VehicleSignals) {

    private val book = TripBook(db)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Register with DrivingMonitor.addEventListener. Called on the signal thread. */
    val onDrivingEvent: (DrivingEvent) -> Unit = { event ->
        val snapshot = signals.snapshot.value
        val reading = TripBook.Reading(snapshot.odometerKm, snapshot.socPercent)
        val at = WallClock.fromElapsed(event.at)
        when (event) {
            is DrivingEvent.TripStarted -> write("trip start") {
                val trip = book.onTripStarted(at, event.mergedWithPrevious, reading)
                Log.i(TAG, "trip #${trip.id} running${if (event.mergedWithPrevious) " (merged)" else ""} ${describe(trip)}")
            }
            is DrivingEvent.TripEnded -> write("trip end") {
                val trip = book.onTripEnded(at, event.reason.name, reading)
                if (trip == null) Log.w(TAG, "trip end (${event.reason}) with no running trip")
                else Log.i(TAG, "trip #${trip.id} ended (${trip.endReason}) ${describe(trip)}")
            }
            is DrivingEvent.AppResumed -> write("resume") {
                val drivingNow = event.state is DrivingState.Driving || event.state is DrivingState.EndCountdown
                val trip = book.onAppResumed(at, drivingNow, reading)
                Log.i(TAG, "resume drivingNow=$drivingNow trip=${trip?.let { "#${it.id} ${it.status} ${describe(it)}" } ?: "none"}")
            }
            else -> Unit
        }
    }

    private val onSignal: (SignalEvent) -> Unit = { event ->
        if (event is SignalEvent.RouteStarted) {
            val route = TripBook.Route(event.route.destinationName, event.route.tollFare)
            write("route") {
                val trip = book.onRouteStarted(route)
                if (trip == null) Log.i(TAG, "route kept for the next trip: dest=${maskForLog(route.destinationName)} toll=${route.tollFare}")
                else Log.i(TAG, "route on trip #${trip.id}: ${describe(trip)}")
            }
        }
    }

    fun start() {
        signals.addListener(onSignal)
        // Odometer, including its first value (which produces no SignalEvent), for a start reading that was missing
        scope.launch {
            signals.snapshot.map { it.odometerKm }.filterNotNull().distinctUntilChanged().collect { km ->
                runCatching { book.onOdometer(km) }
                    .onSuccess { trip -> trip?.let { Log.i(TAG, "trip #${it.id} start odometer filled: ${it.startOdometerKm} km") } }
                    .onFailure { Log.e(TAG, "odometer fill failed", it) }
            }
        }
    }

    fun close() {
        signals.removeListener(onSignal)
        scope.cancel()
    }

    private fun write(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "$what failed", e)
            }
        }
    }

    private fun describe(trip: TripEntity): String =
        "odo=${trip.startOdometerKm}→${trip.endOdometerKm} distance=${trip.distanceKm()} " +
            "dest=${maskForLog(trip.destinationName)} toll=${trip.expectedTollFare}"

    private companion object {
        const val TAG = "BOBCar.Trip"
    }
}
