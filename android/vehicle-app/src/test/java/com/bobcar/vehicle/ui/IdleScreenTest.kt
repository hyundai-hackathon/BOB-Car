package com.bobcar.vehicle.ui

import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.upload.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class IdleScreenTest {

    private val drivers = listOf(DriverEntity("drv-001", "김민준"), DriverEntity("drv-002", "이서연"))

    /** Local wall time of 2026-11-15 [hour]:[minute] (device time zone, like the screen). */
    private fun at(hour: Int, minute: Int, day: Int = 15): Long = Calendar.getInstance().run {
        clear()
        set(2026, Calendar.NOVEMBER, day, hour, minute)
        timeInMillis
    }

    private fun trip(
        purpose: String? = "BUSINESS",
        destination: String? = "판교 테크노밸리",
        toll: Int? = 2_400,
        driverId: String? = "drv-001",
        day: Int = 15,
    ) = TripEntity(
        status = TripEntity.STATUS_ENDED,
        startedAt = at(9, 12, day),
        endedAt = at(9, 44, day),
        startOdometerKm = 12_464f,
        endOdometerKm = 12_482.4f,
        driverId = driverId,
        purpose = purpose,
        destinationName = destination,
        expectedTollFare = toll,
    )

    @Test
    fun `last trip reads like the design`() {
        val (title, detail) = lastTripLines(trip(), drivers, at(10, 0))

        assertEquals("판교 테크노밸리 · 18.4 km", title)
        assertEquals("오늘 09:12 – 09:44 · 김민준 · 업무 · 통행료 2,400원", detail)
    }

    @Test
    fun `personal trip shows no destination and free route shows no toll`() {
        val (title, detail) = lastTripLines(trip(purpose = "PERSONAL", destination = null, toll = 0), drivers, at(10, 0))

        assertEquals("개인 운행 · 18.4 km", title)
        assertEquals("오늘 09:12 – 09:44 · 김민준 · 개인", detail)
    }

    @Test
    fun `no destination, no driver and yesterday`() {
        val (title, detail) = lastTripLines(trip(destination = null, toll = null, driverId = null, day = 14), drivers, at(10, 0))

        assertEquals("목적지 없음 · 18.4 km", title)
        assertEquals("어제 09:12 – 09:44 · 운전자 미확인 · 업무", detail)
    }

    @Test
    fun `no trip yet`() {
        val (title, detail) = lastTripLines(null, drivers, at(10, 0))

        assertEquals("아직 운행 기록이 없습니다", title)
        assertNull(detail)
    }

    @Test
    fun `older days show the date`() {
        assertEquals("11월 12일", dayLabel(at(9, 0, day = 12), at(10, 0)))
    }

    @Test
    fun `odometer shows whole kilometres without rounding up`() {
        assertEquals("12,482", odometerLabel(12_482.9f))
        assertEquals("—", odometerLabel(null))
    }

    @Test
    fun `status follows the server setting and the sendable queue`() {
        val sent = UploadStatus.State(serverConfigured = true, lastSuccessAt = 2_000, lastSentCount = 3)

        assertEquals(IdleStatus.Kind.LOCAL_ONLY, IdleStatus.of(UploadStatus.State(serverConfigured = false), 5).kind)
        assertEquals(IdleStatus(IdleStatus.Kind.ALL_SENT, "모든 기록이 서버에 전송되었습니다"), IdleStatus.of(sent, 0))
        assertEquals("전송 대기 2건", IdleStatus.of(sent, 2).text)
        assertEquals(
            "전송 대기 2건 · 연결되면 자동으로 보냅니다",
            IdleStatus.of(sent.copy(lastFailureAt = 3_000, lastError = "IOException"), 2).text,
        )
    }
}
