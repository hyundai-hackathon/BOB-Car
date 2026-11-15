package com.bobcar.mobile.ui

import com.bobcar.mobile.data.Check
import com.bobcar.mobile.data.PhoneTrip
import com.bobcar.mobile.data.Purpose
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TripTextTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    /** "Now" is 2026-11-13 (Fri) 10:00 Korea time. */
    private val text = TripText(seoul) { at(13, 10, 0) }

    private fun trip(
        startedAt: Long = at(13, 9, 12),
        endedAt: Long? = at(13, 9, 44),
        status: String = "ENDED",
        purpose: Purpose? = Purpose.BUSINESS,
        destination: String? = "판교 테크노밸리",
        driverName: String? = "김민준",
        driverProvisional: Boolean = false,
        check: Check? = null,
        assignedByName: String? = null,
        closedByServer: Boolean = false,
    ) = PhoneTrip(
        "veh-001_1", "veh-001", status, startedAt, endedAt, 12_482.0, 12_500.4, 18.4, destination, 2_400,
        purpose, false, "drv-001", driverName, driverProvisional, null, closedByServer, assignedByName, emptyList(), check,
    )

    @Test
    fun dayLabelsAndTimes() {
        assertEquals("오늘 · 11월 13일", text.dayLabel(at(13, 9, 12)))
        assertEquals("어제 · 11월 12일", text.dayLabel(at(12, 23, 59)))
        assertEquals("11월 10일 (화)", text.dayLabel(at(10, 8, 0)))
        assertEquals("09:12 – 09:44", text.timeRange(trip()))
        assertEquals("09:12 –", text.timeRange(trip(endedAt = null)))
        assertEquals("어제 18:02 – 18:21", text.whenRange(trip(startedAt = at(12, 18, 2), endedAt = at(12, 18, 21))))
        assertEquals("11월 13일 · 09:12 – 09:44", text.detailDate(trip()))
    }

    @Test
    fun numbers() {
        assertEquals("18.4 km", text.km(18.4))
        assertEquals("6.0 km", text.km(6.0))
        assertEquals("4,852 km", text.km(4_852.2))
        assertEquals("—", text.km(null))
        assertEquals("12,482 km", text.odometer(12_482.3))
        assertEquals("2,400원", text.won(2_400))
        assertEquals("0원", text.won(null))
        assertEquals("32분", text.duration(trip()))
        assertEquals("1시간 5분", text.duration(trip(endedAt = at(13, 10, 17))))
    }

    @Test
    fun rowTitlesNeverShowAPersonalDestination() {
        assertEquals("판교 테크노밸리", text.title(trip()))
        assertEquals("개인 운행", text.title(trip(purpose = Purpose.PERSONAL, destination = null)))
        assertEquals("목적지 없음", text.title(trip(destination = null)))
        assertEquals("미기록 운행", text.title(trip(status = "UNRECORDED", check = Check.UNRECORDED)))
    }

    @Test
    fun whoDroveAndHowItWasRecorded() {
        assertEquals("09:12 – 09:44 · 김민준", text.meta(trip()))
        assertEquals("09:12 – 09:44 · 김민준 · 잠정", text.meta(trip(driverProvisional = true)))
        assertEquals("09:12 – 09:44 · 운전자 미확인", text.meta(trip(driverName = null)))
        assertEquals("김민준 · 차에서 확인", text.driverLine(trip()))
        assertEquals("김민준 · 박지호 님이 지정", text.driverLine(trip(assignedByName = "박지호")))
    }

    @Test
    fun checkCardWording() {
        assertEquals("미기록 운행 · 운전자 미확인", text.kicker(trip(check = Check.UNRECORDED)))
        assertEquals("잠정 기록 · 확인 없이 출발", text.kicker(trip(check = Check.DRIVER, driverProvisional = true)))
        assertEquals("잠정 기록 · 응답 없이 자동 종료", text.kicker(trip(check = Check.PURPOSE, closedByServer = true)))
        assertEquals("잠정 기록 · 박지호 님이 지정", text.kicker(trip(check = Check.DRIVER, assignedByName = "박지호")))
        assertEquals("김민준 님 · 개인 용도로 잠정 기록했습니다", text.provisionalLine(trip(purpose = Purpose.PERSONAL)))
    }

    @Test
    fun vehicleLines() {
        assertEquals("마지막 충전 어제 22:14 · 41% → 80%", text.lastCharge(at(12, 22, 14), 41, 80))
        assertEquals("오늘 09:45 수신", text.lastUpload(at(13, 9, 45)))
    }
}
