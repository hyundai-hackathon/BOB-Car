package com.bobcar.vehicle.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Local tables. Times are wall-clock epoch milliseconds. Enum-like values are stored as their names.
 * Only app-made IDs are stored (no VIN, CLAUDE.md rule 6).
 */

/** One trip in the logbook. Filled by the trip recording, check-in and trip-end features. */
@Entity(tableName = "trip", indices = [Index("status")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ACTIVE, ENDED */
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startOdometerKm: Float? = null,
    val endOdometerKm: Float? = null,
    val driverId: String? = null,
    /** true while the driver is the default and not confirmed yet */
    val driverProvisional: Boolean = true,
    /** BUSINESS, COMMUTE, PERSONAL; null until decided */
    val purpose: String? = null,
    /** Cleared when the trip is PERSONAL (CLAUDE.md rule 6) */
    val destinationName: String? = null,
    val expectedTollFare: Int? = null,
    /** COUNTDOWN, SEAT_EMPTY, DOOR_OPEN, or APP_RESTART (found unfinished when the app came back) */
    val endReason: String? = null,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_ENDED = "ENDED"
        const val END_APP_RESTART = "APP_RESTART"
    }
}

/**
 * Distance on the instrument cluster odometer, end − start. null when either reading is missing
 * or the odometer went backwards (the emulator resets it on restart, troubleshooting B8).
 */
fun TripEntity.distanceKm(): Float? {
    val start = startOdometerKm ?: return null
    val end = endOdometerKm ?: return null
    return (end - start).takeIf { it >= 0f }
}

/** Battery and odometer at a point in time, compared later for charge and unrecorded-drive detection. */
@Entity(tableName = "energy_snapshot", indices = [Index("takenAt")])
data class EnergySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TRIP_END, BOARDING, APP_RESUME */
    val kind: String,
    val takenAt: Long,
    val tripId: Long? = null,
    val socPercent: Int? = null,
    val batteryCapacityWh: Float? = null,
    val odometerKm: Float? = null,
)

/** Vehicle-side events: driving state changes now; charge detected and unrecorded drive later. */
@Entity(tableName = "vehicle_event", indices = [Index("occurredAt")])
data class VehicleEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val occurredAt: Long,
    val tripId: Long? = null,
    /** Short readable detail, e.g. "reason=COUNTDOWN". No personal data. */
    val detail: String? = null,
)

/** Single-row cache of per-vehicle baselines. The server is the source of truth once it exists. */
@Entity(tableName = "vehicle_baseline")
data class VehicleBaselineEntity(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val lastOdometerKm: Float? = null,
    val lastSocPercent: Int? = null,
    val lastTripEndedAt: Long? = null,
    val activeTripId: Long? = null,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW_ID = 0
    }
}

/** Driver list (local mock until the server provides it). */
@Entity(tableName = "driver")
data class DriverEntity(
    @PrimaryKey val id: String,
    val name: String,
    val active: Boolean = true,
)

/**
 * A driver's answer to the data collection notice (consent card), kept on this vehicle.
 * Only [STATUS_AGREED] for the current notice version lets that driver's records go to the server;
 * [STATUS_DEFERRED] ([나중에]) keeps them on the device and asks again at the next check-in.
 */
@Entity(tableName = "driver_consent")
data class DriverConsentEntity(
    @PrimaryKey val driverId: String,
    /** AGREED, DEFERRED */
    val status: String,
    val decidedAt: Long,
    /** Version of the notice text the driver answered (checkin ConsentNotice.VERSION) */
    val noticeVersion: Int,
) {
    companion object {
        const val STATUS_AGREED = "AGREED"
        const val STATUS_DEFERRED = "DEFERRED"
    }
}

/** Reservation of this vehicle (local mock until the server provides it). */
@Entity(tableName = "reservation", indices = [Index("startAt"), Index("endAt")])
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driverId: String,
    val startAt: Long,
    val endAt: Long,
)

/** Outbox: what still has to be sent to the server. Rows stay until sent, so nothing is lost offline. */
@Entity(tableName = "upload_queue", indices = [Index("sentAt")])
data class UploadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** e.g. vehicle_event, trip */
    val kind: String,
    val refId: Long? = null,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val sentAt: Long? = null,
)
