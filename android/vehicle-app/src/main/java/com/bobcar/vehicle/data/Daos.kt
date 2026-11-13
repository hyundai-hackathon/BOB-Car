package com.bobcar.vehicle.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getById(id: Long): TripEntity?

    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActive(): TripEntity?

    @Query("SELECT * FROM trip WHERE status = 'ENDED' ORDER BY endedAt DESC LIMIT 1")
    suspend fun getLastEnded(): TripEntity?

    /** The latest ended trip that has a purpose: the previous purpose for the suggestion. */
    @Query("SELECT * FROM trip WHERE status = 'ENDED' AND purpose IS NOT NULL ORDER BY endedAt DESC LIMIT 1")
    suspend fun getLastEndedWithPurpose(): TripEntity?

    @Query("SELECT COUNT(*) FROM trip")
    fun countFlow(): Flow<Int>

    /** The running trip for the screen; null when none. */
    @Query("SELECT * FROM trip WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun activeFlow(): Flow<TripEntity?>

    @Query("SELECT * FROM trip WHERE status = 'ENDED' ORDER BY endedAt DESC LIMIT 1")
    fun lastEndedFlow(): Flow<TripEntity?>

    /** The most recent trip that has a driver (confirmed or provisional): the previous driver. */
    @Query("SELECT * FROM trip WHERE driverId IS NOT NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLastWithDriver(): TripEntity?
}

@Dao
interface EnergySnapshotDao {
    @Insert
    suspend fun insert(snapshot: EnergySnapshotEntity): Long

    @Query("SELECT * FROM energy_snapshot WHERE kind = :kind ORDER BY takenAt DESC LIMIT 1")
    suspend fun getLatest(kind: String): EnergySnapshotEntity?

    /** The snapshot stored last, of any kind: what the next snapshot is compared against. */
    @Query("SELECT * FROM energy_snapshot ORDER BY id DESC LIMIT 1")
    suspend fun getLast(): EnergySnapshotEntity?

    @Query("SELECT COUNT(*) FROM energy_snapshot")
    suspend fun count(): Int
}

@Dao
interface VehicleEventDao {
    @Insert
    suspend fun insert(event: VehicleEventEntity): Long

    @Query("SELECT * FROM vehicle_event ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<VehicleEventEntity>

    @Query("SELECT COUNT(*) FROM vehicle_event")
    fun countFlow(): Flow<Int>
}

@Dao
interface VehicleBaselineDao {
    @Query("SELECT * FROM vehicle_baseline WHERE id = ${VehicleBaselineEntity.SINGLE_ROW_ID}")
    suspend fun get(): VehicleBaselineEntity?

    @Upsert
    suspend fun upsert(baseline: VehicleBaselineEntity)

    @Query("SELECT * FROM vehicle_baseline WHERE id = ${VehicleBaselineEntity.SINGLE_ROW_ID}")
    fun flow(): Flow<VehicleBaselineEntity?>
}

@Dao
interface DriverDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(drivers: List<DriverEntity>)

    @Query("SELECT * FROM driver WHERE active = 1 ORDER BY name")
    suspend fun getActive(): List<DriverEntity>

    /** Insert drivers that are not there yet; existing rows are left alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(drivers: List<DriverEntity>): List<Long>

    /** For the driver list on screen, in registration (ID) order. */
    @Query("SELECT * FROM driver WHERE active = 1 ORDER BY id")
    fun activeFlow(): Flow<List<DriverEntity>>

    @Query("SELECT * FROM driver WHERE id = :id AND active = 1")
    suspend fun getActiveById(id: String): DriverEntity?

    @Query("SELECT COUNT(*) FROM driver")
    suspend fun count(): Int
}

@Dao
interface DriverConsentDao {
    @Upsert
    suspend fun upsert(consent: DriverConsentEntity)

    @Query("SELECT * FROM driver_consent WHERE driverId = :driverId")
    suspend fun get(driverId: String): DriverConsentEntity?

    /** All answers, for the consent card (a handful of drivers per vehicle). */
    @Query("SELECT * FROM driver_consent")
    fun allFlow(): Flow<List<DriverConsentEntity>>

    /** All answers, for the upload check (a handful of drivers per vehicle). */
    @Query("SELECT * FROM driver_consent")
    suspend fun getAll(): List<DriverConsentEntity>

    /** Debug: forget the answer so the first check-in can be tried again. */
    @Query("DELETE FROM driver_consent WHERE driverId = :driverId")
    suspend fun delete(driverId: String): Int
}

@Dao
interface ReservationDao {
    @Insert
    suspend fun insert(reservation: ReservationEntity): Long

    /** The reservation covering [at], if any (start inclusive, end exclusive). */
    @Query("SELECT * FROM reservation WHERE startAt <= :at AND :at < endAt ORDER BY startAt DESC LIMIT 1")
    suspend fun getAt(at: Long): ReservationEntity?

    /** Removes the reservations overlapping [start, end) (replaced by the server's). */
    @Query("DELETE FROM reservation WHERE startAt < :end AND endAt > :start")
    suspend fun deleteOverlapping(start: Long, end: Long): Int

    /** Removes the reservations covering [at] (debug: test the no-reservation case). */
    @Query("DELETE FROM reservation WHERE startAt <= :at AND :at < endAt")
    suspend fun deleteAt(at: Long): Int
}

@Dao
interface UploadQueueDao {
    @Insert
    suspend fun insert(item: UploadQueueEntity): Long

    /** Oldest unsent items first. */
    @Query("SELECT * FROM upload_queue WHERE sentAt IS NULL ORDER BY id LIMIT :limit")
    suspend fun getPending(limit: Int): List<UploadQueueEntity>

    /**
     * Unsent items to send now: records of [driverIds] (drivers who agreed) only, consent records first,
     * then oldest first, skipping ones the server refused [maxAttempts] times.
     */
    @Query(
        "SELECT * FROM upload_queue WHERE sentAt IS NULL AND attempts < :maxAttempts AND driverId IN (:driverIds) " +
            "ORDER BY (kind = 'consent') DESC, id LIMIT :limit",
    )
    suspend fun getSendable(limit: Int, maxAttempts: Int, driverIds: List<String>): List<UploadQueueEntity>

    /**
     * The driver of the stay in the seat that began at [since] is now [driverId] (check-in started or confirmed):
     * unsent vehicle events stored since then become that driver's. Consent rows keep their driver.
     */
    @Query(
        "UPDATE upload_queue SET driverId = :driverId " +
            "WHERE sentAt IS NULL AND kind = 'vehicle_event' AND createdAt >= :since",
    )
    suspend fun assignDriver(since: Long, driverId: String?): Int

    /**
     * Unsent events of [types] that nobody owns yet (found while nobody was checked in, e.g. on a restart
     * with an empty seat) become [driverId]'s: the next driver to board is the one who found them.
     */
    @Query(
        "UPDATE upload_queue SET driverId = :driverId " +
            "WHERE sentAt IS NULL AND driverId IS NULL AND kind = 'vehicle_event' " +
            "AND refId IN (SELECT id FROM vehicle_event WHERE type IN (:types))",
    )
    suspend fun claimUnowned(driverId: String, types: List<String>): Int

    @Query("UPDATE upload_queue SET sentAt = :sentAt WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>, sentAt: Long)

    @Query("UPDATE upload_queue SET attempts = attempts + 1, lastError = :error WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, error: String)

    @Query("SELECT COUNT(*) FROM upload_queue WHERE sentAt IS NULL")
    fun pendingCountFlow(): Flow<Int>

    /** Sent rows are kept for a while for checking, then removed. */
    @Query("DELETE FROM upload_queue WHERE sentAt IS NOT NULL AND sentAt < :before")
    suspend fun deleteSentBefore(before: Long): Int
}
