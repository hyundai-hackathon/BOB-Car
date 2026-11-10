package com.bobcar.vehicle.data

import android.os.SystemClock
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
 */
class DrivingRecorder(
    private val db: BobCarDatabase,
    private val onStored: () -> Unit = {},
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Event times are elapsedRealtime; convert to wall-clock right away. */
    fun record(event: DrivingEvent) {
        val occurredAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - event.at)
        val (type, detail) = typeAndDetail(event)
        scope.launch {
            try {
                db.withTransaction {
                    val id = db.vehicleEventDao().insert(
                        VehicleEventEntity(type = type, occurredAt = occurredAt, detail = detail),
                    )
                    db.uploadQueueDao().insert(
                        UploadQueueEntity(
                            kind = KIND_VEHICLE_EVENT,
                            refId = id,
                            payloadJson = JSONObject()
                                .put("eventId", id)
                                .put("type", type)
                                .put("occurredAt", occurredAt)
                                .put("detail", detail ?: JSONObject.NULL)
                                .toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
                Log.d(TAG, "stored $type")
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
        is DrivingEvent.AppResumed -> "AppResumed" to "state=${event.state::class.simpleName}"
    }

    private companion object {
        const val TAG = "BOBCar.Recorder"
        const val KIND_VEHICLE_EVENT = "vehicle_event"
    }
}
