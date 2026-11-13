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

    private fun store(type: String, detail: String?, occurredAt: Long, kind: String, fixedDriverId: String?) {
        scope.launch {
            val driverId = fixedDriverId ?: currentDriver()
            try {
                db.withTransaction {
                    val id = db.vehicleEventDao().insert(
                        VehicleEventEntity(type = type, occurredAt = occurredAt, detail = detail),
                    )
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
        const val TYPE_CONSENT_AGREED = "ConsentAgreed"
    }
}
