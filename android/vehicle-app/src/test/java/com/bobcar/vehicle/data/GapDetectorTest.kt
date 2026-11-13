package com.bobcar.vehicle.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class GapDetectorTest {

    private data class Event(val type: String, val detail: String?, val at: Long)

    private lateinit var db: BobCarDatabase
    private lateinit var gaps: GapDetector
    private val events = mutableListOf<Event>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gaps = GapDetector(db) { type, detail, at -> events += Event(type, detail, at) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun reading(km: Float?, soc: Int?, capacityWh: Float? = CAPACITY_WH) = TripBook.Reading(km, soc, capacityWh)

    /** A trip from 12,340 km that ends with [km] and [soc]: leaves the TRIP_END snapshot. */
    private suspend fun endTrip(km: Float?, soc: Int?, capacityWh: Float? = CAPACITY_WH) {
        val book = TripBook(db)
        book.onTripStarted(1_000, merged = false, reading(12_340f, 80))
        book.onTripEnded(2_000, "COUNTDOWN", reading(km, soc, capacityWh))
    }

    @Test
    fun chargeFrom23To68PercentIsOneEventOfAbout34Point8Kwh() = runTest {
        endTrip(12_362f, 23)

        val found = gaps.request(BOARDING, 5_000, reading(12_362f, 68))

        assertEquals(45, found.charge!!.gainedPercent)
        assertEquals(34.83f, found.charge!!.kwh!!, 0.01f)
        assertNull(found.unrecordedKm)
        val event = events.single()
        assertEquals(GapDetector.EVENT_CHARGE, event.type)
        assertEquals(5_000L, event.at)
        assertTrue(event.detail!!, event.detail!!.startsWith("gainedPercent=45 from=23 to=68 kwh=34.83 "))
    }

    @Test
    fun unchangedValuesGiveNoEvent() = runTest {
        endTrip(12_362f, 23)

        gaps.request(BOARDING, 5_000, reading(12_362f, 23))

        assertTrue(events.isEmpty())
        assertEquals(BOARDING, db.energySnapshotDao().getLast()!!.kind)
    }

    @Test
    fun odometerGainWhileTheAppWasOffIsOneUnrecordedDrive() = runTest {
        endTrip(12_362f, 60)

        val found = gaps.request(APP_RESUME, 9_000, reading(12_368f, 60))

        assertEquals(6f, found.unrecordedKm)
        val event = events.single()
        assertEquals(GapDetector.EVENT_UNRECORDED_DRIVE, event.type)
        assertTrue(event.detail!!, event.detail!!.startsWith("km=6.00 from=12362.0 to=12368.0 "))
    }

    @Test
    fun restartFollowedByBoardingCountsTheGapOnce() = runTest {
        endTrip(12_362f, 23)

        gaps.request(APP_RESUME, 9_000, reading(12_368f, 68))
        gaps.request(BOARDING, 9_500, reading(12_368f, 68))
        gaps.checkNow(TRIP_START, 9_900, reading(12_368f, 68))

        assertEquals(listOf(GapDetector.EVENT_CHARGE, GapDetector.EVENT_UNRECORDED_DRIVE), events.map { it.type })
    }

    @Test
    fun firstSnapshotHasNothingToCompareWith() = runTest {
        gaps.request(APP_RESUME, 1_000, reading(12_368f, 68))

        assertTrue(events.isEmpty())
        assertEquals(1, db.energySnapshotDao().count())
    }

    @Test
    fun valuesThatCannotBeTrustedGiveNoEventAndBecomeTheNewReference() = runTest {
        endTrip(12_362f, 60)

        // Odometer went backwards (emulator reset): dropped, and the reset value is the reference from now on
        gaps.request(APP_RESUME, 5_000, reading(100f, 60))
        assertTrue(events.isEmpty())

        gaps.request(BOARDING, 6_000, reading(100.2f, 60))
        assertTrue(events.isEmpty())

        gaps.request(BOARDING, 7_000, reading(108.2f, 60))
        assertEquals(GapDetector.EVENT_UNRECORDED_DRIVE, events.single().type)
    }

    @Test
    fun tripEndWithoutValuesGivesNoEventAtTheNextBoarding() = runTest {
        endTrip(null, null)

        gaps.request(BOARDING, 5_000, reading(12_368f, 68))

        assertTrue(events.isEmpty())
    }

    @Test
    fun chargeWithoutCapacityIsRecordedWithoutKwh() = runTest {
        endTrip(12_362f, 23, capacityWh = null)

        val found = gaps.request(BOARDING, 5_000, reading(12_362f, 68, capacityWh = null))

        assertEquals(45, found.charge!!.gainedPercent)
        assertNull(found.charge!!.kwh)
        assertTrue(events.single().detail!!, events.single().detail!!.contains("kwh=unknown"))
        assertNull(db.energySnapshotDao().getLast()!!.batteryCapacityWh)
    }

    @Test
    fun capacityReadEarlierIsUsedWhenItIsMissingNow() = runTest {
        endTrip(12_362f, 23)

        val found = gaps.request(BOARDING, 5_000, reading(12_362f, 68, capacityWh = null))

        assertEquals(34.83f, found.charge!!.kwh!!, 0.01f)
    }

    @Test
    fun checkWaitsForTheFirstOdometerAndBatteryValues() = runTest {
        endTrip(12_362f, 23)

        // Right after a restart: nothing read yet
        assertEquals(GapDetector.Found.NOTHING, gaps.request(APP_RESUME, 9_000, reading(null, null, null)))
        assertEquals(GapDetector.Found.NOTHING, gaps.onReading(reading(12_368f, null)))
        assertTrue(events.isEmpty())
        assertEquals(KIND_TRIP_END, db.energySnapshotDao().getLast()!!.kind)

        gaps.onReading(reading(12_368f, 68))

        assertEquals(listOf(GapDetector.EVENT_CHARGE, GapDetector.EVENT_UNRECORDED_DRIVE), events.map { it.type })
        assertEquals(listOf(9_000L, 9_000L), events.map { it.at })
        val snapshot = db.energySnapshotDao().getLast()!!
        assertEquals(APP_RESUME, snapshot.kind)
        assertEquals(9_000L, snapshot.takenAt)

        // Later readings run nothing more
        gaps.onReading(reading(12_390f, 90))
        assertEquals(2, events.size)
    }

    @Test
    fun departureChecksWithWhatIsThereAndDropsTheWaitingCheck() = runTest {
        endTrip(12_362f, 23)
        gaps.request(BOARDING, 5_000, reading(12_368f, null))

        val found = gaps.checkNow(TRIP_START, 6_000, reading(12_368f, null))

        assertEquals(6f, found.unrecordedKm)
        assertNull(found.charge)
        // The trip is running now: values arriving later must not be compared
        gaps.onReading(reading(12_400f, 68))
        assertEquals(1, events.size)
    }

    private companion object {
        const val CAPACITY_WH = 77_400f
        const val BOARDING = EnergySnapshotEntity.KIND_BOARDING
        const val APP_RESUME = EnergySnapshotEntity.KIND_APP_RESUME
        const val TRIP_START = EnergySnapshotEntity.KIND_TRIP_START
        const val KIND_TRIP_END = EnergySnapshotEntity.KIND_TRIP_END
    }
}
