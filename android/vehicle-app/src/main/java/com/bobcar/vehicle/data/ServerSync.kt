package com.bobcar.vehicle.data

import android.util.Log
import androidx.room.withTransaction
import com.bobcar.vehicle.server.ServerApi

/**
 * Brings server values into the local DB, which the app keeps using (works with the server off, CLAUDE.md
 * rule 8). Runs when collection starts and when a driver boards.
 *
 * - Drivers: the server's list is added or updated (names), so check-in shows the same people as the server.
 * - Reservations: today's reservations (local day) are replaced by the server's.
 * - Baseline: the server's per-vehicle baseline wins when its last trip end is newer than the local one
 *   (another profile of the same car, or app data cleared). The running trip stays local.
 * Anything the server cannot give is left as it is. Logs counts only.
 */
class ServerSync(
    private val db: BobCarDatabase,
    private val api: ServerApi,
) {
    /** Debug: a fake reservation was set; keep it (no server reservations) until the app restarts. */
    @Volatile
    var keepLocalReservations = false

    data class Outcome(val drivers: Int?, val reservations: Int?, val baselineTaken: Boolean?)

    suspend fun refresh(now: Long): Outcome {
        val drivers = api.drivers()?.let { list ->
            db.driverDao().upsertAll(list.map { DriverEntity(id = it.id, name = it.name) })
            list.size
        }
        val reservations = if (keepLocalReservations) null else {
            val (start, end) = LocalMockData.dayWindow(now)
            api.reservations(start, end)?.let { list ->
                db.withTransaction {
                    db.reservationDao().deleteOverlapping(start, end)
                    list.forEach { db.reservationDao().insert(ReservationEntity(driverId = it.driverId, startAt = it.startAt, endAt = it.endAt)) }
                }
                list.size
            }
        }
        val baselineTaken = api.baseline()?.let { takeBaseline(it, now) }
        val outcome = Outcome(drivers, reservations, baselineTaken)
        Log.i(TAG, "server sync drivers=${drivers ?: "-"} reservations=${reservations ?: "-"} baseline=${baselineTaken ?: "-"}")
        return outcome
    }

    /** true when the server's baseline replaced the local values. */
    private suspend fun takeBaseline(server: ServerApi.Baseline, now: Long): Boolean = db.withTransaction {
        val serverEnded = server.lastTripEndedAt ?: return@withTransaction false
        val local = db.vehicleBaselineDao().get()
        if (local?.lastTripEndedAt != null && local.lastTripEndedAt >= serverEnded) return@withTransaction false
        db.vehicleBaselineDao().upsert(
            (local ?: VehicleBaselineEntity(updatedAt = now)).copy(
                lastOdometerKm = server.lastOdometerKm ?: local?.lastOdometerKm,
                lastSocPercent = server.lastSocPercent ?: local?.lastSocPercent,
                lastTripEndedAt = serverEnded,
                updatedAt = now,
            ),
        )
        true
    }

    private companion object {
        const val TAG = "BOBCar.Sync"
    }
}
