package com.bobcar.vehicle.data

import java.util.Calendar

/**
 * Driver list and reservations used until the server provides them (roadmap: server stage).
 * Names are made up. IDs are app-made (CLAUDE.md rule 6).
 */
object LocalMockData {

    val drivers = listOf(
        DriverEntity(id = "drv-001", name = "김민준"),
        DriverEntity(id = "drv-002", name = "이서연"),
        DriverEntity(id = "drv-003", name = "박지호"),
    )

    /** Holds today's reservation of this vehicle in the mock. */
    const val RESERVED_DRIVER_ID = "drv-001"

    /**
     * Adds the drivers when the list is empty, and a reservation for today (local time, whole day)
     * when nothing covers [now]. Runs on every service start, so a new day gets its reservation.
     */
    suspend fun seed(db: BobCarDatabase, now: Long): String {
        val driverDao = db.driverDao()
        val added = if (driverDao.count() == 0) drivers.also { driverDao.upsertAll(it) }.size else 0
        val reservationDao = db.reservationDao()
        val reserved = if (reservationDao.getAt(now) == null) {
            val (start, end) = dayWindow(now)
            reservationDao.insert(ReservationEntity(driverId = RESERVED_DRIVER_ID, startAt = start, endAt = end))
            true
        } else {
            false
        }
        return "drivers added=$added, today's reservation added=$reserved"
    }

    /** Replaces the reservation covering [now] with one for [driverId] today, or removes it when null. */
    suspend fun setReservationNow(db: BobCarDatabase, now: Long, driverId: String?) {
        val reservationDao = db.reservationDao()
        reservationDao.deleteAt(now)
        if (driverId != null) {
            val (start, end) = dayWindow(now)
            reservationDao.insert(ReservationEntity(driverId = driverId, startAt = start, endAt = end))
        }
    }

    /** Local midnight of [at]'s day and the next midnight. */
    fun dayWindow(at: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to cal.timeInMillis
    }
}
