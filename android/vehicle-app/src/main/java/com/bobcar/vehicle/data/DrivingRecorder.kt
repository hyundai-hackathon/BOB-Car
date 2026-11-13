package com.bobcar.vehicle.data

import android.util.Log
import androidx.room.withTransaction
import com.bobcar.vehicle.driving.DrivingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Stores every driving event in the local DB and adds it to the upload queue in the same transaction,
 * so a record is never kept without its upload entry (works with the server off, CLAUDE.md rule 8).
 * Writes run one at a time in arrival order. [onStored] runs after each successful write
 * (used to request an upload).
 *
 * Each queue entry carries the driver checked in at the moment it is written ([currentDriver]), so the
 * upload can hold back the records of drivers who have not agreed to collection. [assignDriver] runs on
 * the same write order, so an event stored around a check-in change is never missed.
 *
 * Trip rows ([recordTrip]) are queued whole each time the trip changes; the server keeps the newest.
 */
class DrivingRecorder(
    private val db: BobCarDatabase,
    private val currentDriver: () -> String? = { null },
    private val onStored: () -> Unit = {},
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Event times are elapsedRealtime; convert to wall-clock right away. */
    fun record(event: DrivingEvent) {
        val (type, detail) = typeAndDetail(event)
        record(type, detail, WallClock.fromElapsed(event.at))
    }

    /** Other vehicle events (e.g. driver provisional / confirmed). [occurredAt] is wall-clock. No personal data in [detail]. */
    fun record(type: String, detail: String?, occurredAt: Long) = store(type, detail, occurredAt, KIND_VEHICLE_EVENT, null)

    /** [driverId] agreed to collection. Queued as that driver's own record (it never follows a check-in change). */
    fun recordConsent(driverId: String, detail: String, occurredAt: Long) =
        store(TYPE_CONSENT_AGREED, detail, occurredAt, KIND_CONSENT, driverId)

    /**
     * The driver of the stay in the seat that began at [since] (wall-clock) is now [driverId]:
     * unsent events stored since then become that driver's.
     */
    fun assignDriver(since: Long, driverId: String?) {
        scope.launch {
            try {
                val changed = db.uploadQueueDao().assignDriver(since, driverId)
                Log.d(TAG, "queue driver=${driverId ?: "none"} rows=$changed")
            } catch (e: Exception) {
                Log.e(TAG, "queue driver assign failed", e)
                return@launch
            }
            onStored()
        }
    }

    /**
     * Charge and unrecorded-drive events found while nobody was checked in become [driverId]'s
     * (the driver who boards next found them).
     */
    fun claimFindings(driverId: String) {
        scope.launch {
            try {
                val changed = db.uploadQueueDao().claimUnowned(driverId, GapDetector.EVENT_TYPES)
                if (changed == 0) return@launch
                Log.d(TAG, "queue findings driver=$driverId rows=$changed")
            } catch (e: Exception) {
                Log.e(TAG, "queue findings claim failed", e)
                return@launch
            }
            onStored()
        }
    }

    /**
     * The trip changed (started, driver confirmed, route, ended, closed on restart): its whole row goes to the
     * upload queue as that trip's driver's record. [endSocPercent] = battery at the end (ended rows only).
     * A trip with no driver yet is not queued (it could not be sent); a later change queues it.
     */
    fun recordTrip(trip: TripEntity, endSocPercent: Int?) {
        val driverId = trip.driverId ?: run {
            Log.d(TAG, "trip #${trip.id} has no driver yet, not queued")
            return
        }
        scope.launch {
            try {
                db.uploadQueueDao().insert(
                    UploadQueueEntity(
                        kind = KIND_TRIP,
                        refId = trip.id,
                        payloadJson = tripPayload(trip, endSocPercent).toString(),
                        createdAt = System.currentTimeMillis(),
                        driverId = driverId,
                    ),
                )
                Log.d(TAG, "stored trip #${trip.id} ${trip.status} driver=$driverId")
            } catch (e: Exception) {
                Log.e(TAG, "store trip #${trip.id} failed", e)
                return@launch
            }
            onStored()
        }
    }

    private fun store(type: String, detail: String?, occurredAt: Long, kind: String, fixedDriverId: String?) {
        scope.launch {
            val driverId = fixedDriverId ?: currentDriver()
            try {
                insertWithQueue(db, type, detail, occurredAt, kind, driverId)
                Log.d(TAG, "stored $type driver=${driverId ?: "none"}")
            } catch (e: Exception) {
                Log.e(TAG, "store $type failed", e)
                return@launch
            }
            onStored()
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun typeAndDetail(event: DrivingEvent): Pair<String, String?> = when (event) {
        is DrivingEvent.Boarded -> "Boarded" to null
        is DrivingEvent.CheckinClosed -> "CheckinClosed" to "trigger=${event.trigger}"
        is DrivingEvent.TripStarted -> "TripStarted" to "merged=${event.mergedWithPrevious}"
        is DrivingEvent.EndCountdownStarted -> "EndCountdownStarted" to "countdownMs=${event.deadline - event.at}"
        is DrivingEvent.EndCountdownCancelled -> "EndCountdownCancelled" to "reason=${event.reason}"
        is DrivingEvent.TripEnded -> "TripEnded" to "reason=${event.reason}"
        is DrivingEvent.AppResumed ->
            "AppResumed" to "state=${event.state::class.simpleName}${if (event.gearKnown) "" else " gear=unknown"}"
    }

    companion object {
        private const val TAG = "BOBCar.Recorder"
        const val KIND_VEHICLE_EVENT = "vehicle_event"
        const val KIND_CONSENT = "consent"
        const val KIND_TRIP = "trip"
        const val TYPE_CONSENT_AGREED = "ConsentAgreed"

        /** The event row and its upload entry, in one transaction. */
        private suspend fun insertWithQueue(
            db: BobCarDatabase,
            type: String,
            detail: String?,
            occurredAt: Long,
            kind: String,
            driverId: String?,
        ) = db.withTransaction {
            val id = db.vehicleEventDao().insert(VehicleEventEntity(type = type, occurredAt = occurredAt, detail = detail))
            db.uploadQueueDao().insert(
                UploadQueueEntity(
                    kind = kind,
                    refId = id,
                    payloadJson = JSONObject()
                        .put("eventId", id)
                        .put("type", type)
                        .put("occurredAt", occurredAt)
                        .put("detail", detail ?: JSONObject.NULL)
                        .toString(),
                    createdAt = System.currentTimeMillis(),
                    driverId = driverId,
                ),
            )
        }

        /**
         * The server lost [driverId]'s consent (e.g. its data was wiped): queue the consent stored on this
         * device again. Nothing is queued unless the driver agreed here.
         */
        suspend fun requeueConsent(db: BobCarDatabase, driverId: String) {
            val consent = db.driverConsentDao().get(driverId) ?: return
            if (consent.status != DriverConsentEntity.STATUS_AGREED) return
            insertWithQueue(
                db,
                TYPE_CONSENT_AGREED,
                "driver=$driverId version=${consent.noticeVersion}",
                consent.decidedAt,
                KIND_CONSENT,
                driverId,
            )
            Log.i(TAG, "consent queued again for $driverId (server did not have it)")
        }

        /**
         * The trip row the server stores (server/functions/src/trips.ts). The destination goes only with an
         * ended trip that is not personal: a running trip's purpose is not decided yet (CLAUDE.md rule 6).
         */
        fun tripPayload(trip: TripEntity, endSocPercent: Int?): JSONObject {
            val ended = trip.status == TripEntity.STATUS_ENDED
            val destination = if (ended && trip.purpose != "PERSONAL") trip.destinationName else null
            return JSONObject()
                .put("tripId", trip.id)
                .put("status", trip.status)
                .put("startedAt", trip.startedAt)
                .put("endedAt", trip.endedAt ?: JSONObject.NULL)
                .put("startOdometerKm", trip.startOdometerKm?.toDouble() ?: JSONObject.NULL)
                .put("endOdometerKm", trip.endOdometerKm?.toDouble() ?: JSONObject.NULL)
                .put("endSocPercent", (if (ended) endSocPercent else null) ?: JSONObject.NULL)
                .put("driverId", trip.driverId ?: JSONObject.NULL)
                .put("driverProvisional", trip.driverProvisional)
                .put("purpose", trip.purpose ?: JSONObject.NULL)
                .put("purposeProvisional", trip.purposeProvisional)
                .put("destinationName", destination ?: JSONObject.NULL)
                .put("expectedTollFare", trip.expectedTollFare ?: JSONObject.NULL)
                .put("endReason", trip.endReason ?: JSONObject.NULL)
        }
    }
}
