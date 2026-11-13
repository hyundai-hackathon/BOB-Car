package com.bobcar.vehicle.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BobCarDatabaseTest {

    private lateinit var db: BobCarDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun eventAndQueueEntryAreStoredTogetherAndCounted() = runTest {
        db.withTransaction {
            val id = db.vehicleEventDao().insert(VehicleEventEntity(type = "TripStarted", occurredAt = 1_000))
            db.uploadQueueDao().insert(UploadQueueEntity(kind = "vehicle_event", refId = id, payloadJson = "{}", createdAt = 1_000))
        }

        assertEquals(1, db.vehicleEventDao().countFlow().first())
        assertEquals(1, db.uploadQueueDao().pendingCountFlow().first())
        assertEquals("TripStarted", db.vehicleEventDao().getRecent(5).single().type)
    }

    @Test
    fun failedTransactionLeavesNeitherEventNorQueueEntry() = runTest {
        runCatching {
            db.withTransaction {
                db.vehicleEventDao().insert(VehicleEventEntity(type = "TripEnded", occurredAt = 1_000))
                error("simulated failure before the queue insert")
            }
        }

        assertEquals(0, db.vehicleEventDao().countFlow().first())
        assertEquals(0, db.uploadQueueDao().pendingCountFlow().first())
    }

    @Test
    fun pendingItemsComeOldestFirstAndLeaveWhenSent() = runTest {
        val dao = db.uploadQueueDao()
        val ids = (1..3).map { dao.insert(UploadQueueEntity(kind = "vehicle_event", payloadJson = "{\"n\":$it}", createdAt = it.toLong())) }

        assertEquals(ids, dao.getPending(10).map { it.id })

        dao.markFailed(listOf(ids[0]), "timeout")
        val retried = dao.getPending(10).first()
        assertEquals(1, retried.attempts)
        assertEquals("timeout", retried.lastError)

        dao.markSent(ids.take(2), sentAt = 5_000)
        assertEquals(listOf(ids[2]), dao.getPending(10).map { it.id })
        assertEquals(1, dao.pendingCountFlow().first())

        assertEquals(2, dao.deleteSentBefore(6_000))
        assertEquals(1, dao.getPending(10).size)
    }

    @Test
    fun activeTripIsFoundAndEndedTripBecomesLastEnded() = runTest {
        val dao = db.tripDao()
        val id = dao.insert(TripEntity(status = "ACTIVE", startedAt = 1_000, startOdometerKm = 12_340f))
        assertEquals(id, dao.getActive()?.id)

        dao.update(dao.getById(id)!!.copy(status = "ENDED", endedAt = 2_000, endOdometerKm = 12_362f, endReason = "COUNTDOWN"))

        assertNull(dao.getActive())
        val ended = dao.getLastEnded()!!
        assertEquals(22f, ended.endOdometerKm!! - ended.startOdometerKm!!)
    }

    @Test
    fun baselineIsASingleRowThatGetsReplaced() = runTest {
        val dao = db.vehicleBaselineDao()
        assertNull(dao.get())

        dao.upsert(VehicleBaselineEntity(lastOdometerKm = 12_340f, lastSocPercent = 23, updatedAt = 1_000))
        dao.upsert(VehicleBaselineEntity(lastOdometerKm = 12_362f, lastSocPercent = 68, updatedAt = 2_000))

        val baseline = dao.get()!!
        assertEquals(12_362f, baseline.lastOdometerKm)
        assertEquals(68, baseline.lastSocPercent)
    }

    @Test
    fun reservationIsFoundOnlyInsideItsTime() = runTest {
        val dao = db.reservationDao()
        dao.insert(ReservationEntity(driverId = "kim", startAt = 1_000, endAt = 2_000))

        assertNull(dao.getAt(999))
        assertEquals("kim", dao.getAt(1_000)?.driverId)
        assertEquals("kim", dao.getAt(1_999)?.driverId)
        assertNull(dao.getAt(2_000))
    }

    @Test
    fun latestSnapshotOfAKindIsReturned() = runTest {
        val dao = db.energySnapshotDao()
        dao.insert(EnergySnapshotEntity(kind = "TRIP_END", takenAt = 1_000, socPercent = 23))
        dao.insert(EnergySnapshotEntity(kind = "TRIP_END", takenAt = 3_000, socPercent = 40))
        dao.insert(EnergySnapshotEntity(kind = "BOARDING", takenAt = 4_000, socPercent = 68))

        assertEquals(40, dao.getLatest("TRIP_END")?.socPercent)
        assertTrue(dao.getLatest("APP_RESUME") == null)
    }

    @Test
    fun onlyActiveDriversAreListed() = runTest {
        val dao = db.driverDao()
        dao.upsertAll(listOf(DriverEntity("kim", "김"), DriverEntity("lee", "이", active = false)))

        assertEquals(listOf("kim"), dao.getActive().map { it.id })
    }
}
