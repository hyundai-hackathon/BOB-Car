package com.bobcar.vehicle.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bobcar.vehicle.server.HttpResponse
import com.bobcar.vehicle.server.HttpTransport
import com.bobcar.vehicle.server.IdTokenProvider
import com.bobcar.vehicle.server.RefreshTokenStore
import com.bobcar.vehicle.server.ServerApi
import com.bobcar.vehicle.server.ServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ServerSyncTest {

    private lateinit var db: BobCarDatabase
    private val now = 1_800_000_000_000L
    private val day = LocalMockData.dayWindow(now)

    private val config = ServerConfig("http://10.0.2.2:5001/demo-bobcar/asia-northeast3", "http://10.0.2.2:9099", "emulator", "veh-001", debug = true)

    /** Answers GETs by path; auth always gives a token. null answer = server unreachable. */
    private class FakeServer(private val answers: Map<String, String?>) : HttpTransport {
        val paths = mutableListOf<String>()
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (url.contains("googleapis.com")) return HttpResponse(200, """{"idToken":"id","refreshToken":"rt","expiresIn":"3600"}""")
            val path = url.substringAfter("/api/v1").substringBefore("?")
            paths += path
            val answer = answers[path] ?: throw IOException("down")
            return HttpResponse(200, answer)
        }
    }

    private class MemoryStore : RefreshTokenStore {
        private var token: String? = null
        override fun load() = token
        override fun save(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private fun sync(server: FakeServer) = ServerSync(db, ServerApi(config, IdTokenProvider(config, MemoryStore(), server), server))

    private val drivers = """{"items":[{"driverId":"drv-001","name":"김민준"},{"driverId":"drv-007","name":"윤도윤"}]}"""
    private val reservations = """{"items":[{"driverId":"drv-002","startAt":${day.first},"endAt":${day.second}}]}"""
    private fun vehicle(endedAt: Long) = """{"vehicleId":"veh-001","lastOdometerKm":12362,"lastSocPercent":68,"lastTripEndedAt":$endedAt,"activeTripId":null}"""

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        LocalMockData.seed(db, now)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun serverDriversAndTodaysReservationReplaceTheMock() = runTest {
        val outcome = sync(FakeServer(mapOf("/drivers" to drivers, "/reservations" to reservations, "/vehicles/veh-001" to "{}"))).refresh(now)

        assertEquals(2, outcome.drivers)
        assertEquals(1, outcome.reservations)
        assertEquals("윤도윤", db.driverDao().getActiveById("drv-007")?.name)
        assertEquals("drv-002", db.reservationDao().getAt(now)?.driverId)
    }

    @Test
    fun newerServerBaselineIsTakenButTheRunningTripStaysLocal() = runTest {
        db.vehicleBaselineDao().upsert(VehicleBaselineEntity(lastOdometerKm = 12_340f, lastSocPercent = 80, lastTripEndedAt = 1_000, activeTripId = 5, updatedAt = 1_000))

        val outcome = sync(FakeServer(mapOf("/vehicles/veh-001" to vehicle(endedAt = 9_000)))).refresh(now)

        assertEquals(true, outcome.baselineTaken)
        val baseline = db.vehicleBaselineDao().get()!!
        assertEquals(12_362f, baseline.lastOdometerKm)
        assertEquals(68, baseline.lastSocPercent)
        assertEquals(9_000L, baseline.lastTripEndedAt)
        assertEquals(5L, baseline.activeTripId)
    }

    @Test
    fun olderServerBaselineDoesNotReplaceTheLocalOne() = runTest {
        db.vehicleBaselineDao().upsert(VehicleBaselineEntity(lastOdometerKm = 12_400f, lastSocPercent = 50, lastTripEndedAt = 20_000, updatedAt = 20_000))

        val outcome = sync(FakeServer(mapOf("/vehicles/veh-001" to vehicle(endedAt = 9_000)))).refresh(now)

        assertEquals(false, outcome.baselineTaken)
        assertEquals(12_400f, db.vehicleBaselineDao().get()!!.lastOdometerKm)
    }

    @Test
    fun emptyAppDataTakesTheServerBaseline() = runTest {
        sync(FakeServer(mapOf("/vehicles/veh-001" to vehicle(endedAt = 9_000)))).refresh(now)

        assertEquals(12_362f, db.vehicleBaselineDao().get()!!.lastOdometerKm)
    }

    @Test
    fun serverOffLeavesEverythingLocal() = runTest {
        val outcome = sync(FakeServer(emptyMap())).refresh(now)

        assertNull(outcome.drivers)
        assertNull(outcome.reservations)
        assertNull(outcome.baselineTaken)
        assertEquals(LocalMockData.RESERVED_DRIVER_ID, db.reservationDao().getAt(now)?.driverId)
        assertEquals(6, db.driverDao().count())
    }

    @Test
    fun aFakeReservationIsKeptUntilRestart() = runTest {
        val server = FakeServer(mapOf("/reservations" to reservations))
        val sync = sync(server).apply { keepLocalReservations = true }

        assertNull(sync.refresh(now).reservations)
        assertEquals(LocalMockData.RESERVED_DRIVER_ID, db.reservationDao().getAt(now)?.driverId)
        assertFalse("/reservations" in server.paths)
    }
}
