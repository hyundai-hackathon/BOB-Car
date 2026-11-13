package com.bobcar.vehicle.checkin

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.data.LocalMockData
import com.bobcar.vehicle.data.ReservationEntity
import com.bobcar.vehicle.data.TripBook
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriverDefaultsTest {

    private lateinit var db: BobCarDatabase

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.driverDao().upsertAll(
            listOf(
                DriverEntity("drv-a", "가"),
                DriverEntity("drv-b", "나"),
                DriverEntity("drv-c", "다"),
                DriverEntity("drv-gone", "라", active = false),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun reserve(driverId: String, start: Long = 1_000, end: Long = 10_000) {
        db.reservationDao().insert(ReservationEntity(driverId = driverId, startAt = start, endAt = end))
    }

    private suspend fun previousTrip(driverId: String, provisional: Boolean) {
        val book = TripBook(db)
        book.onTripStarted(100, merged = false, TripBook.Reading(0f, 50), TripBook.DriverChoice(driverId, provisional))
        book.onTripEnded(200, "COUNTDOWN", TripBook.Reading(1f, 50))
    }

    @Test
    fun reservedDriverIsTheDefault() = runTest {
        reserve("drv-a")
        previousTrip("drv-b", provisional = false)

        assertEquals(DefaultDriver("drv-a", DefaultSource.RESERVATION), DriverDefaults(db).resolve(5_000))
    }

    @Test
    fun profileComesBeforeReservation() = runTest {
        reserve("drv-a")

        val defaults = DriverDefaults(db, profile = { "drv-c" })

        assertEquals(DefaultDriver("drv-c", DefaultSource.PROFILE), defaults.resolve(5_000))
    }

    @Test
    fun previousDriverWhenNoReservationCoversNow() = runTest {
        reserve("drv-a", start = 1_000, end = 2_000)
        previousTrip("drv-b", provisional = true)

        assertEquals(DefaultDriver("drv-b", DefaultSource.LAST_DRIVER), DriverDefaults(db).resolve(5_000))
    }

    @Test
    fun inactiveOrUnknownCandidatesAreSkipped() = runTest {
        reserve("drv-gone")
        previousTrip("drv-b", provisional = false)

        val defaults = DriverDefaults(db, profile = { "nobody" })

        assertEquals(DefaultDriver("drv-b", DefaultSource.LAST_DRIVER), defaults.resolve(5_000))
    }

    @Test
    fun noCandidateGivesNoDefault() = runTest {
        assertNull(DriverDefaults(db).resolve(5_000))
    }

    @Test
    fun checkinRecordsTheDefaultAsProvisionalUntilConfirmed() {
        val checkin = DriverCheckin()
        val started = checkin.start(1_000, DefaultDriver("drv-a", DefaultSource.RESERVATION))
        assertEquals("drv-a", started.driverId)
        assertTrue(started.provisional)

        val confirmed = checkin.confirm("drv-b")!!
        assertEquals("drv-b", confirmed.driverId)
        assertFalse(confirmed.provisional)

        val closed = checkin.close()!!
        assertTrue(closed.closed)
        assertEquals("drv-b", closed.driverId)
    }

    @Test
    fun confirmWithoutCheckinDoesNothing() {
        val checkin = DriverCheckin()
        assertNull(checkin.confirm("drv-a"))
        assertNull(checkin.current)
    }

    @Test
    fun mockSeedAddsDriversOnceAndTodaysReservation() = runTest {
        val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val now = System.currentTimeMillis()

        LocalMockData.seed(fresh, now)
        LocalMockData.seed(fresh, now)

        assertEquals(LocalMockData.drivers.size, fresh.driverDao().count())
        assertEquals(LocalMockData.RESERVED_DRIVER_ID, fresh.reservationDao().getAt(now)?.driverId)
        assertEquals(DefaultSource.RESERVATION, DriverDefaults(fresh).resolve(now)?.source)

        LocalMockData.setReservationNow(fresh, now, null)
        assertNull(fresh.reservationDao().getAt(now))
        fresh.close()
    }
}
