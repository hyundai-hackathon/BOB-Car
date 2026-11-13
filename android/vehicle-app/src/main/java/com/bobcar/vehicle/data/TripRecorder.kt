package com.bobcar.vehicle.data

import android.util.Log
import com.bobcar.vehicle.checkin.ConsentNotice
import com.bobcar.vehicle.checkin.DriverCheckin
import com.bobcar.vehicle.checkin.DriverDefaults
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
 * Feeds [TripBook] from driving events (start, end, resume), navigation guidance and odometer values,
 * and runs the driver check-in ([checkin]) that decides who the trip is recorded for:
 * - Boarded (or resumed seated): new check-in with the default driver from [defaults]
 * - driver confirmed: check-in confirmed; a running trip gets the driver too
 * - CheckinClosed while unconfirmed: the default is recorded as provisional ([recordEvent])
 * - TripStarted: the trip gets the check-in's driver
 * - consent card answered: stored per driver (records of drivers who did not agree stay on the device)
 *
 * Readings are taken from the signal snapshot on the signal thread when the event happens, then
 * everything is written one at a time in arrival order (so the check-in is always ready before the
 * trip starts). Logs mask the destination (CLAUDE.md rule 6); driver IDs are app-made.
 */
class TripRecorder(
    private val db: BobCarDatabase,
    private val signals: VehicleSignals,
    private val checkin: DriverCheckin,
    private val defaults: DriverDefaults,
    /** Stores a vehicle event (type, detail, wall-clock time) with its upload entry. */
    private val recordEvent: (String, String?, Long) -> Unit = { _, _, _ -> },
) {

    private val book = TripBook(db)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Register with DrivingMonitor.addEventListener. Called on the signal thread. */
    val onDrivingEvent: (DrivingEvent) -> Unit = { event ->
        val snapshot = signals.snapshot.value
        val reading = TripBook.Reading(snapshot.odometerKm, snapshot.socPercent)
        val at = WallClock.fromElapsed(event.at)
        when (event) {
            is DrivingEvent.Boarded -> write("check-in") { startCheckin(at) }
            is DrivingEvent.CheckinClosed -> write("check-in close") { closeCheckin(at) }
            is DrivingEvent.TripStarted -> write("trip start") {
                val trip = book.onTripStarted(at, event.mergedWithPrevious, reading, checkinDriver())
                Log.i(TAG, "trip #${trip.id} running${if (event.mergedWithPrevious) " (merged)" else ""} ${describe(trip)}")
            }
            is DrivingEvent.TripEnded -> write("trip end") {
                val trip = book.onTripEnded(at, event.reason.name, reading)
                if (trip == null) Log.w(TAG, "trip end (${event.reason}) with no running trip")
                else Log.i(TAG, "trip #${trip.id} ended (${trip.endReason}) ${describe(trip)}")
            }
            is DrivingEvent.AppResumed -> write("resume") {
                // Seated without a trip: the driver card is due again. Not seated: nobody to check in.
                when (event.state) {
                    is DrivingState.CheckingIn -> if (checkin.current == null) startCheckin(at)
                    DrivingState.Idle -> checkin.clear()
                    else -> Unit
                }
                val drivingNow = if (!event.gearKnown) null
                else event.state is DrivingState.Driving || event.state is DrivingState.EndCountdown
                // A trip started here was never checked in: default driver, provisional
                val driver = if (drivingNow == true) defaults.resolve(at)?.let { TripBook.DriverChoice(it.driverId, true) } else null
                val trip = book.onAppResumed(at, drivingNow, reading, driver)
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
        // Mock drivers and today's reservation first, so the first check-in can find a default
        write("mock data") { Log.i(TAG, "local mock data: ${LocalMockData.seed(db, System.currentTimeMillis())}") }
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

    /** The driver card was answered (or [다른 사람] chosen). Unknown or inactive IDs are ignored. */
    fun confirmDriver(driverId: String) = write("driver confirm") {
        if (db.driverDao().getActiveById(driverId) == null) {
            Log.w(TAG, "driver confirm ignored: $driverId is not an active driver")
            return@write
        }
        val at = System.currentTimeMillis()
        val state = checkin.confirm(driverId)
        val trip = book.onDriverConfirmed(driverId)
        recordEvent(EVENT_DRIVER_CONFIRMED, "driver=$driverId", at)
        Log.i(TAG, "driver confirmed: $driverId (check-in=${state != null}, trip=${trip?.id ?: "none"})")
    }

    /**
     * The consent card was answered. [agreed] stores AGREED and records [EVENT_CONSENT_AGREED] (the server
     * clears "동의 대기" with it); [나중에] stores DEFERRED on the device only and asks again next check-in.
     */
    fun answerConsent(driverId: String, agreed: Boolean) = write("consent") {
        if (db.driverDao().getActiveById(driverId) == null) {
            Log.w(TAG, "consent ignored: $driverId is not an active driver")
            return@write
        }
        val at = System.currentTimeMillis()
        val status = if (agreed) DriverConsentEntity.STATUS_AGREED else DriverConsentEntity.STATUS_DEFERRED
        db.driverConsentDao().upsert(DriverConsentEntity(driverId, status, at, ConsentNotice.VERSION))
        checkin.consentAnswered(driverId)
        if (agreed) recordEvent(EVENT_CONSENT_AGREED, "driver=$driverId version=${ConsentNotice.VERSION}", at)
        Log.i(TAG, "consent $status: $driverId (notice v${ConsentNotice.VERSION})")
    }

    /** Debug: forgets the driver's consent answer, so the first check-in can be tried again. */
    fun resetConsent(driverId: String) = write("consent reset") {
        val removed = db.driverConsentDao().delete(driverId)
        checkin.consentReset(driverId)
        Log.i(TAG, "consent reset: $driverId (removed=$removed)")
    }

    /** Debug: the mock reservation covering now is replaced (driver ID) or removed (null). */
    fun setReservationNow(driverId: String?) = write("reservation") {
        LocalMockData.setReservationNow(db, System.currentTimeMillis(), driverId)
        Log.i(TAG, "mock reservation now: ${driverId ?: "none"}")
    }

    fun close() {
        signals.removeListener(onSignal)
        scope.cancel()
    }

    // ---------------------------------------------------------------- check-in (write queue)

    private suspend fun startCheckin(at: Long) {
        val default = defaults.resolve(at)
        checkin.start(at, default)
        Log.i(TAG, "check-in: default driver ${default?.let { "${it.driverId} (${it.source})" } ?: "none"}")
    }

    /** Card closes. Unconfirmed → the default is recorded as provisional (or that there is none). */
    private suspend fun closeCheckin(at: Long) {
        if (checkin.current == null) startCheckin(at) // e.g. drove off without a seat signal
        val state = checkin.close() ?: return
        if (!state.provisional) return
        val detail = state.default?.let { "driver=${it.driverId} source=${it.source}" } ?: "driver=none"
        recordEvent(EVENT_DRIVER_PROVISIONAL, detail, at)
        Log.i(TAG, "check-in closed unconfirmed, provisional $detail")
    }

    private fun checkinDriver(): TripBook.DriverChoice? {
        val state = checkin.current ?: return null
        val id = state.driverId ?: return null
        return TripBook.DriverChoice(id, state.provisional)
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
            "dest=${maskForLog(trip.destinationName)} toll=${trip.expectedTollFare} " +
            "driver=${trip.driverId ?: "none"}${if (trip.driverProvisional) " (provisional)" else ""}"

    private companion object {
        const val TAG = "BOBCar.Trip"
        const val EVENT_DRIVER_PROVISIONAL = "DriverProvisional"
        const val EVENT_DRIVER_CONFIRMED = "DriverConfirmed"
        const val EVENT_CONSENT_AGREED = "ConsentAgreed"
    }
}
