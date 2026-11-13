package com.bobcar.vehicle.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import com.bobcar.vehicle.trip.Purpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripBookTest {

    private lateinit var db: BobCarDatabase
    private lateinit var book: TripBook

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        book = TripBook(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun reading(km: Float?, soc: Int? = 60) = TripBook.Reading(km, soc)

    private fun outcome(purpose: Purpose, answered: Boolean = true, driverId: String? = "drv-001") =
        TripBook.EndOutcome(driverId, driverProvisional = !answered, purpose = purpose, purposeProvisional = !answered)

    @Test
    fun endedTripIsSavedWithTheCardAnswer() = runTest {
        book.onRouteStarted(TripBook.Route("판교 테크노밸리", 2_400))
        book.onTripStarted(1_000, merged = false, reading(100f), TripBook.DriverChoice("drv-002", provisional = true))

        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(118f), outcome(Purpose.BUSINESS))!!

        assertEquals("BUSINESS", ended.purpose)
        assertFalse(ended.purposeProvisional)
        assertEquals("drv-001", ended.driverId)
        assertFalse(ended.driverProvisional)
        assertEquals("판교 테크노밸리", ended.destinationName)
        assertEquals(2_400, ended.expectedTollFare)
    }

    @Test
    fun personalTripLosesItsDestination() = runTest {
        book.onRouteStarted(TripBook.Route("동네 마트", 0))
        book.onTripStarted(1_000, merged = false, reading(100f))

        val ended = book.onTripEnded(2_000, "SEAT_EMPTY", reading(103f), outcome(Purpose.PERSONAL))!!

        assertEquals("PERSONAL", ended.purpose)
        assertNull(ended.destinationName)
        assertNull(db.tripDao().getById(ended.id)!!.destinationName)
        assertEquals(3f, ended.distanceKm())
    }

    @Test
    fun unansweredCardSavesTheSuggestionAsProvisional() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f), TripBook.DriverChoice("drv-001", provisional = true))

        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(110f), outcome(Purpose.COMMUTE, answered = false))!!

        assertEquals("COMMUTE", ended.purpose)
        assertTrue(ended.purposeProvisional)
        assertTrue(ended.driverProvisional)
    }

    @Test
    fun outcomeWithoutDriverKeepsTheTripsDriver() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f), TripBook.DriverChoice("drv-003", provisional = false))

        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(110f), outcome(Purpose.BUSINESS, answered = false, driverId = null))!!

        assertEquals("drv-003", ended.driverId)
        assertFalse(ended.driverProvisional)
    }

    @Test
    fun previousPurposeComesFromTheLatestEndedTripWithOne() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f), outcome(Purpose.COMMUTE))
        book.onTripStarted(3_000, merged = false, reading(110f))
        book.onTripEnded(4_000, "COUNTDOWN", reading(120f))

        assertEquals("COMMUTE", db.tripDao().getLastEndedWithPurpose()!!.purpose)
    }

    @Test
    fun mergedTripTakesTheRouteSetUpBeforeLeavingAgain() = runTest {
        book.onRouteStarted(TripBook.Route("판교 테크노밸리", 2_400))
        val first = book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(118f))
        assertNull(book.onRouteStarted(TripBook.Route("동네 마트", 0)))

        val merged = book.onTripStarted(2_300, merged = true, reading(118f))

        assertEquals(first.id, merged.id)
        assertEquals("동네 마트", merged.destinationName)
        assertEquals(0, merged.expectedTollFare)
        assertEquals("동네 마트", db.tripDao().getById(first.id)!!.destinationName)
    }

    @Test
    fun mergedTripWithoutNewRouteKeepsItsDestination() = runTest {
        book.onRouteStarted(TripBook.Route("판교 테크노밸리", 2_400))
        book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(118f))

        val merged = book.onTripStarted(2_300, merged = true, reading(118f))

        assertEquals("판교 테크노밸리", merged.destinationName)
        assertEquals(2_400, merged.expectedTollFare)
    }

    @Test
    fun mergedTripDecidesItsPurposeAgain() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f), outcome(Purpose.PERSONAL))

        val merged = book.onTripStarted(2_300, merged = true, reading(110f))
        assertNull(merged.purpose)
        assertTrue(merged.purposeProvisional)
        assertNull(db.tripDao().getLastEndedWithPurpose())

        val ended = book.onTripEnded(3_000, "COUNTDOWN", reading(120f), outcome(Purpose.BUSINESS))!!
        assertEquals("BUSINESS", ended.purpose)
    }

    @Test
    fun tripClosedOnResumeGetsTheGivenOutcome() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f))

        val closed = book.onAppResumed(5_000, drivingNow = false, reading(130f), endOutcome = outcome(Purpose.BUSINESS, answered = false))!!

        assertEquals(TripEntity.END_APP_RESTART, closed.endReason)
        assertEquals("BUSINESS", closed.purpose)
        assertTrue(closed.purposeProvisional)
    }

    @Test
    fun tripFrom12340To12362Is22Km() = runTest {
        val started = book.onTripStarted(1_000, merged = false, reading(12_340f, 80))
        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(12_362f, 72))!!

        assertEquals(started.id, ended.id)
        assertEquals(TripEntity.STATUS_ENDED, ended.status)
        assertEquals(1_000L, ended.startedAt)
        assertEquals(2_000L, ended.endedAt)
        assertEquals(22f, ended.distanceKm())
        assertEquals("COUNTDOWN", ended.endReason)
        assertNull(db.tripDao().getActive())

        val baseline = db.vehicleBaselineDao().get()!!
        assertEquals(12_362f, baseline.lastOdometerKm)
        assertEquals(72, baseline.lastSocPercent)
        assertEquals(2_000L, baseline.lastTripEndedAt)
        assertNull(baseline.activeTripId)
    }

    @Test
    fun endedTripLeavesATripEndSnapshot() = runTest {
        book.onTripStarted(1_000, merged = false, reading(12_340f, 80))
        val ended = book.onTripEnded(2_000, "COUNTDOWN", TripBook.Reading(12_362f, 72, 77_400f))!!

        val snapshot = db.energySnapshotDao().getLast()!!
        assertEquals(EnergySnapshotEntity.KIND_TRIP_END, snapshot.kind)
        assertEquals(2_000L, snapshot.takenAt)
        assertEquals(ended.id, snapshot.tripId)
        assertEquals(12_362f, snapshot.odometerKm)
        assertEquals(72, snapshot.socPercent)
        assertEquals(77_400f, snapshot.batteryCapacityWh)
    }

    @Test
    fun tripEndWithNoRunningTripLeavesNoSnapshot() = runTest {
        assertNull(book.onTripEnded(2_000, "COUNTDOWN", reading(100f)))

        assertNull(db.energySnapshotDao().getLast())
    }

    @Test
    fun runningTripIsRememberedInTheBaseline() = runTest {
        val trip = book.onTripStarted(1_000, merged = false, reading(100f))

        assertEquals(trip.id, db.vehicleBaselineDao().get()!!.activeTripId)
    }

    @Test
    fun routeStartedInParkBeforeLeavingGoesOnTheNextTrip() = runTest {
        assertNull(book.onRouteStarted(TripBook.Route("인천국제공항 제1여객터미널", 4_700)))

        val trip = book.onTripStarted(1_000, merged = false, reading(100f))

        assertEquals("인천국제공항 제1여객터미널", trip.destinationName)
        assertEquals(4_700, trip.expectedTollFare)
    }

    @Test
    fun routeStartedDuringTheTripUpdatesItLatestWins() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f))
        book.onRouteStarted(TripBook.Route("A", 0))
        book.onRouteStarted(TripBook.Route("B", 4_700))

        val ended = book.onTripEnded(2_000, "SEAT_EMPTY", reading(110f))!!
        assertEquals("B", ended.destinationName)
        assertEquals(4_700, ended.expectedTollFare)
    }

    @Test
    fun routeFromAnEndedTripDoesNotLeakIntoTheNextOne() = runTest {
        book.onRouteStarted(TripBook.Route("A", 1_000))
        book.onTripEnded(500, "COUNTDOWN", reading(100f)) // no trip running: just drops the waiting route

        val trip = book.onTripStarted(1_000, merged = false, reading(100f))
        assertNull(trip.destinationName)
        assertNull(trip.expectedTollFare)
    }

    @Test
    fun missingStartOdometerIsFilledByTheFirstValue() = runTest {
        book.onTripStarted(1_000, merged = false, reading(null))

        assertEquals(12_340f, book.onOdometer(12_340f)!!.startOdometerKm)
        assertNull("only the first value fills it", book.onOdometer(12_345f))

        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(12_362f))!!
        assertEquals(22f, ended.distanceKm())
    }

    @Test
    fun odometerGoingBackwardsGivesNoDistance() = runTest {
        book.onTripStarted(1_000, merged = false, reading(12_340f))
        val ended = book.onTripEnded(2_000, "COUNTDOWN", reading(0f))!!

        assertNull(ended.distanceKm())
    }

    @Test
    fun secondStartWhileRunningKeepsTheSameTrip() = runTest {
        val first = book.onTripStarted(1_000, merged = false, reading(100f))
        val second = book.onTripStarted(1_500, merged = false, reading(105f))

        assertEquals(first.id, second.id)
        assertEquals(100f, second.startOdometerKm)
    }

    @Test
    fun mergedStartReopensTheLastTrip() = runTest {
        val first = book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f))

        val merged = book.onTripStarted(2_300, merged = true, reading(110f))
        assertEquals(first.id, merged.id)
        assertEquals(TripEntity.STATUS_ACTIVE, merged.status)
        assertNull(merged.endedAt)

        val ended = book.onTripEnded(3_000, "COUNTDOWN", reading(125f))!!
        assertEquals(1_000L, ended.startedAt)
        assertEquals(25f, ended.distanceKm())
    }

    @Test
    fun mergedStartAfterAnUnrecordedDriveStartsANewTrip() = runTest {
        val first = book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f))

        // 6 km were driven with no record before leaving again
        val second = book.onTripStarted(2_300, merged = true, reading(116f))

        assertTrue(second.id != first.id)
        assertEquals(2_300L, second.startedAt)
        assertEquals(116f, second.startOdometerKm)
        val firstStored = db.tripDao().getById(first.id)!!
        assertEquals(TripEntity.STATUS_ENDED, firstStored.status)
        assertEquals(10f, firstStored.distanceKm())
    }

    @Test
    fun mergedStartWithinHalfAKilometreStillContinuesTheTrip() = runTest {
        val first = book.onTripStarted(1_000, merged = false, reading(100f))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f))

        val merged = book.onTripStarted(2_300, merged = true, reading(110.4f))

        assertEquals(first.id, merged.id)
    }

    @Test
    fun appResumedWhileDrivingContinuesTheStoredTrip() = runTest {
        val trip = book.onTripStarted(1_000, merged = false, reading(100f))

        // New process: a fresh TripBook over the same DB
        val afterRestart = TripBook(db).onAppResumed(5_000, drivingNow = true, reading(103f))!!

        assertEquals(trip.id, afterRestart.id)
        assertEquals(TripEntity.STATUS_ACTIVE, afterRestart.status)
        assertEquals(100f, afterRestart.startOdometerKm)
    }

    @Test
    fun appResumedWhileDrivingWithNoStoredTripStartsOneNow() = runTest {
        val trip = book.onAppResumed(5_000, drivingNow = true, reading(103f))!!

        assertEquals(TripEntity.STATUS_ACTIVE, trip.status)
        assertEquals(5_000L, trip.startedAt)
        assertEquals(103f, trip.startOdometerKm)
    }

    @Test
    fun appResumedNotDrivingClosesATripLeftOpen() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f))

        val closed = TripBook(db).onAppResumed(5_000, drivingNow = false, reading(108f))!!

        assertEquals(TripEntity.STATUS_ENDED, closed.status)
        assertEquals(TripEntity.END_APP_RESTART, closed.endReason)
        assertEquals(8f, closed.distanceKm())
    }

    @Test
    fun tripGetsTheCheckinDriverAndConfirmationClearsProvisional() = runTest {
        val provisional = book.onTripStarted(
            1_000, merged = false, reading(100f), TripBook.DriverChoice("drv-a", provisional = true),
        )
        assertEquals("drv-a", provisional.driverId)
        assertEquals(true, provisional.driverProvisional)

        val confirmed = book.onDriverConfirmed("drv-b")!!
        assertEquals(provisional.id, confirmed.id)
        assertEquals("drv-b", confirmed.driverId)
        assertEquals(false, confirmed.driverProvisional)
    }

    @Test
    fun confirmedDriverStaysConfirmedAndMergedTripKeepsItsDriver() = runTest {
        book.onTripStarted(1_000, merged = false, reading(100f), TripBook.DriverChoice("drv-a", provisional = false))
        book.onTripEnded(2_000, "COUNTDOWN", reading(110f))

        val merged = book.onTripStarted(2_500, merged = true, reading(110f), TripBook.DriverChoice("drv-x", true))

        assertEquals("drv-a", merged.driverId)
        assertEquals(false, merged.driverProvisional)
    }

    @Test
    fun driverConfirmedWithNoRunningTripChangesNothing() = runTest {
        assertNull(book.onDriverConfirmed("drv-a"))
    }

    @Test
    fun tripStartedOnResumeGetsTheGivenDriver() = runTest {
        val trip = book.onAppResumed(5_000, drivingNow = true, reading(103f), TripBook.DriverChoice("drv-a", true))!!

        assertEquals("drv-a", trip.driverId)
        assertEquals(true, trip.driverProvisional)
    }

    @Test
    fun appResumedWithGearUnknownKeepsTheStoredTripOpen() = runTest {
        val trip = book.onTripStarted(1_000, merged = false, reading(100f))

        val afterRestart = TripBook(db).onAppResumed(5_000, drivingNow = null, reading(103f))!!

        assertEquals(trip.id, afterRestart.id)
        assertEquals(TripEntity.STATUS_ACTIVE, afterRestart.status)
        assertNull(afterRestart.endedAt)
    }

    @Test
    fun appResumedWithGearUnknownAndNoStoredTripStartsNothing() = runTest {
        assertNull(book.onAppResumed(5_000, drivingNow = null, reading(100f)))
        assertNull(db.tripDao().getActive())
    }

    @Test
    fun appResumedIdleWithNothingStoredDoesNothing() = runTest {
        assertNull(book.onAppResumed(5_000, drivingNow = false, reading(100f)))
        assertNull(db.tripDao().getActive())
    }
}
