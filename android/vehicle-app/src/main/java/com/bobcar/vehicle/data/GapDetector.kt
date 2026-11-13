package com.bobcar.vehicle.data

import android.util.Log
import androidx.room.withTransaction
import com.bobcar.vehicle.energy.Charge
import com.bobcar.vehicle.energy.GapCheck
import java.util.Locale

/**
 * Finds what happened to the car while no trip was recorded: a charge (battery up) and a drive nobody
 * recorded (odometer up). Every check compares the current reading with the snapshot stored last
 * (trip end, boarding, restart or departure) and then stores the current reading as the next reference,
 * so the same gap is never counted twice (e.g. a restart followed by boarding).
 *
 * Checks are asked for when the driver boards and when the app or service comes back ([request]), and
 * when a trip is about to start ([checkNow]). A reading with no odometer or battery value yet (right
 * after a restart) waits for the first values ([onReading]).
 *
 * Found events go to [recordEvent] (local DB + upload queue). Not thread-safe: calls must come one at
 * a time (see [TripRecorder]).
 */
class GapDetector(
    private val db: BobCarDatabase,
    /** Stores a vehicle event (type, detail, wall-clock time) with its upload entry. */
    private val recordEvent: (String, String?, Long) -> Unit,
) {

    /** What one check found. */
    data class Found(val charge: Charge?, val unrecordedKm: Float?) {
        companion object {
            val NOTHING = Found(null, null)
        }
    }

    private data class Waiting(val kind: String, val at: Long)

    private var waiting: Waiting? = null

    /**
     * Checks now when [reading] has both odometer and battery; otherwise the check waits for them
     * ([onReading]). A newer request replaces one still waiting.
     */
    suspend fun request(kind: String, at: Long, reading: TripBook.Reading): Found {
        if (reading.odometerKm == null || reading.socPercent == null) {
            waiting = Waiting(kind, at)
            Log.i(TAG, "check ($kind) waits for odometer and battery")
            return Found.NOTHING
        }
        return checkNow(kind, at, reading)
    }

    /** A new reading came in: runs the waiting check once odometer and battery are both there. */
    suspend fun onReading(reading: TripBook.Reading): Found {
        val pending = waiting ?: return Found.NOTHING
        if (reading.odometerKm == null || reading.socPercent == null) return Found.NOTHING
        return checkNow(pending.kind, pending.at, reading)
    }

    /**
     * Compares [reading] with the last snapshot and stores it as the new reference. A missing value is
     * not compared (and gives the next check no reference for that value). Drops a waiting check.
     */
    suspend fun checkNow(kind: String, at: Long, reading: TripBook.Reading): Found {
        waiting = null
        val snapshots = db.energySnapshotDao()
        val found = db.withTransaction {
            val last = snapshots.getLast()
            // The capacity is a fixed value of the car: an earlier reading of it is as good
            val capacityWh = reading.batteryCapacityWh ?: last?.batteryCapacityWh
            snapshots.insert(
                EnergySnapshotEntity(
                    kind = kind,
                    takenAt = at,
                    socPercent = reading.socPercent,
                    batteryCapacityWh = capacityWh,
                    odometerKm = reading.odometerKm,
                ),
            )
            if (last == null) return@withTransaction Found.NOTHING
            val charge = GapCheck.charge(last.socPercent, reading.socPercent, capacityWh)
            val km = GapCheck.unrecordedKm(last.odometerKm, reading.odometerKm)
            charge?.let {
                recordEvent(
                    EVENT_CHARGE,
                    "gainedPercent=${it.gainedPercent} from=${last.socPercent} to=${reading.socPercent} " +
                        "kwh=${it.kwh?.let(::twoDecimals) ?: "unknown"} foundAt=$kind since=${last.kind} sinceAt=${last.takenAt}",
                    at,
                )
            }
            km?.let {
                recordEvent(
                    EVENT_UNRECORDED_DRIVE,
                    "km=${twoDecimals(it)} from=${last.odometerKm} to=${reading.odometerKm} foundAt=$kind since=${last.kind} sinceAt=${last.takenAt}",
                    at,
                )
            }
            Found(charge, km)
        }
        Log.i(
            TAG,
            "check ($kind) soc=${reading.socPercent} odo=${reading.odometerKm}: " +
                "charge=${found.charge?.let { "+${it.gainedPercent}%p ${it.kwh?.let(::twoDecimals) ?: "?"} kWh" } ?: "none"} " +
                "unrecorded=${found.unrecordedKm?.let { "${twoDecimals(it)} km" } ?: "none"}",
        )
        return found
    }

    private fun twoDecimals(value: Float): String = String.format(Locale.US, "%.2f", value)

    companion object {
        private const val TAG = "BOBCar.Gap"
        const val EVENT_CHARGE = "ChargeDetected"
        const val EVENT_UNRECORDED_DRIVE = "UnrecordedDrive"

        /** Event types found by comparing snapshots: found by whoever boards next, not caused by them. */
        val EVENT_TYPES = listOf(EVENT_CHARGE, EVENT_UNRECORDED_DRIVE)
    }
}
