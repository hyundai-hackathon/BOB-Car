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
}

@Dao
interface DriverDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(drivers: List<DriverEntity>)

    @Query("SELECT * FROM driver WHERE active = 1 ORDER BY name")
    suspend fun getActive(): List<DriverEntity>

    @Query("SELECT * FROM driver WHERE active = 1 ORDER BY name")
    fun activeFlow(): Flow<List<DriverEntity>>

    @Query("SELECT * FROM driver WHERE id = :id AND active = 1")
    suspend fun getActiveById(id: String): DriverEntity?

    @Query("SELECT COUNT(*) FROM driver")
    suspend fun count(): Int
}

@Dao
interface ReservationDao {
    @Insert
    suspend fun insert(reservation: ReservationEntity): Long

    /** The reservation covering [at], if any (start inclusive, end exclusive). */
    @Query("SELECT * FROM reservation WHERE startAt <= :at AND :at < endAt ORDER BY startAt DESC LIMIT 1")
    suspend fun getAt(at: Long): ReservationEntity?

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

    /** Unsent items to send now: oldest first, skipping ones the server refused [maxAttempts] times. */
    @Query("SELECT * FROM upload_queue WHERE sentAt IS NULL AND attempts < :maxAttempts ORDER BY id LIMIT :limit")
    suspend fun getSendable(limit: Int, maxAttempts: Int): List<UploadQueueEntity>

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
