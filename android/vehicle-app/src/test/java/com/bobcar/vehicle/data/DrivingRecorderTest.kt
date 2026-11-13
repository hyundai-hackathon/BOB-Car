package com.bobcar.vehicle.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DrivingRecorderTest {

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

    private fun trip(status: String, purpose: String? = null) = TripEntity(
        id = 7,
        status = status,
        startedAt = 1_000,
        endedAt = if (status == TripEntity.STATUS_ENDED) 9_000 else null,
        startOdometerKm = 12_340f,
        endOdometerKm = if (status == TripEntity.STATUS_ENDED) 12_362f else null,
        driverId = "drv-001",
        driverProvisional = false,
        purpose = purpose,
        purposeProvisional = false,
        destinationName = "판교 테크노밸리",
        expectedTollFare = 2_400,
        endReason = if (status == TripEntity.STATUS_ENDED) "COUNTDOWN" else null,
    )

    @Test
    fun endedBusinessTripCarriesItsDestinationAndBattery() {
        val payload = DrivingRecorder.tripPayload(trip(TripEntity.STATUS_ENDED, "BUSINESS"), endSocPercent = 68)

        assertEquals(7L, payload.getLong("tripId"))
        assertEquals("ENDED", payload.getString("status"))
        assertEquals(12_362.0, payload.getDouble("endOdometerKm"), 0.0)
        assertEquals(68, payload.getInt("endSocPercent"))
        assertEquals("판교 테크노밸리", payload.getString("destinationName"))
        assertEquals(2_400, payload.getInt("expectedTollFare"))
        assertEquals("COUNTDOWN", payload.getString("endReason"))
    }

    @Test
    fun runningTripCarriesNoDestinationYetAndNoBattery() {
        val payload = DrivingRecorder.tripPayload(trip(TripEntity.STATUS_ACTIVE), endSocPercent = 68)

        assertTrue(payload.isNull("destinationName"))
        assertTrue(payload.isNull("endSocPercent"))
        assertEquals(2_400, payload.getInt("expectedTollFare"))
    }

    @Test
    fun personalTripNeverCarriesItsDestination() {
        val payload = DrivingRecorder.tripPayload(trip(TripEntity.STATUS_ENDED, "PERSONAL"), endSocPercent = 50)

        assertTrue(payload.isNull("destinationName"))
    }

    @Test
    fun consentIsQueuedAgainOnlyForADriverWhoAgreedHere() = runTest {
        db.driverConsentDao().upsert(DriverConsentEntity("drv-001", DriverConsentEntity.STATUS_AGREED, 5_000, 1))
        db.driverConsentDao().upsert(DriverConsentEntity("drv-002", DriverConsentEntity.STATUS_DEFERRED, 5_000, 1))

        DrivingRecorder.requeueConsent(db, "drv-001")
        DrivingRecorder.requeueConsent(db, "drv-002")
        DrivingRecorder.requeueConsent(db, "drv-003")

        val queued = db.uploadQueueDao().getPending(10).single()
        assertEquals("consent", queued.kind)
        assertEquals("drv-001", queued.driverId)
        val payload = JSONObject(queued.payloadJson)
        assertEquals("ConsentAgreed", payload.getString("type"))
        assertEquals(5_000L, payload.getLong("occurredAt"))
        assertEquals("driver=drv-001 version=1", payload.getString("detail"))
    }
}
