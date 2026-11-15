package com.bobcar.mobile.ui

import com.bobcar.mobile.data.Check
import com.bobcar.mobile.data.Purpose
import com.bobcar.mobile.data.PhoneTrip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Wording and number formats of the phone screens, kept in one place (Korea time by default). */
class TripText(private val zone: ZoneId = ZoneId.of("Asia/Seoul"), private val now: () -> Long = System::currentTimeMillis) {

    private fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun today(): LocalDate = date(now())

    /** "09:12" */
    fun time(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
    }

    /** "09:12 – 09:44", or "09:12 –" while driving */
    fun timeRange(trip: PhoneTrip): String = "${time(trip.startedAt)} – ${trip.endedAt?.let(::time) ?: ""}".trimEnd()

    /** M1 section label: "오늘 · 11월 13일", "어제 · 11월 12일", "11월 10일 (월)" */
    fun dayLabel(at: Long): String {
        val d = date(at)
        val md = "${d.monthValue}월 ${d.dayOfMonth}일"
        return when (d) {
            today() -> "오늘 · $md"
            today().minusDays(1) -> "어제 · $md"
            else -> "$md (${d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})"
        }
    }

    /** "오늘", "어제", or "11월 10일" before a time (M2 cards, M4 last charge) */
    fun dayWord(at: Long): String {
        val d = date(at)
        return when (d) {
            today() -> "오늘"
            today().minusDays(1) -> "어제"
            else -> "${d.monthValue}월 ${d.dayOfMonth}일"
        }
    }

    /** M2 card: "어제 18:02 – 18:21" */
    fun whenRange(trip: PhoneTrip): String = "${dayWord(trip.startedAt)} ${timeRange(trip)}"

    /** M3 subtitle: "11월 13일 · 09:12 – 09:44" */
    fun detailDate(trip: PhoneTrip): String {
        val d = date(trip.startedAt)
        return "${d.monthValue}월 ${d.dayOfMonth}일 · ${timeRange(trip)}"
    }

    /** "18.4 km" (one decimal under 100 km, whole numbers above) */
    fun km(value: Double?): String = value?.let { "${kmNumber(it)} km" } ?: "—"

    /** Odometer: "12,482 km" */
    fun odometer(value: Double?): String = value?.let { String.format(Locale.ROOT, "%,d km", it.roundToInt()) } ?: "—"

    /** "32분", "1시간 5분" */
    fun duration(trip: PhoneTrip): String {
        val end = trip.endedAt ?: return "—"
        val minutes = ((end - trip.startedAt) / 60_000).coerceAtLeast(0)
        return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
    }

    /** "2,400원", "0원" */
    fun won(value: Int?): String = String.format(Locale.ROOT, "%,d원", value ?: 0)

    /** M1 row title: the destination, or what the trip was */
    fun title(trip: PhoneTrip): String = when {
        trip.unrecorded || trip.check == Check.UNRECORDED -> "미기록 운행"
        trip.active -> "운행 중"
        trip.purpose == Purpose.PERSONAL -> "개인 운행"
        else -> trip.destination ?: "목적지 없음"
    }

    /** M1 row meta: "09:12 – 09:44 · 김민준", "· 운전자 미확인", "· 잠정" */
    fun meta(trip: PhoneTrip): String {
        val who = when {
            trip.driverName == null -> "운전자 미확인"
            trip.driverProvisional -> "${trip.driverName} · 잠정"
            else -> trip.driverName
        }
        return "${timeRange(trip)} · $who"
    }

    /** M2 card kicker */
    fun kicker(trip: PhoneTrip): String = when (trip.check) {
        Check.UNRECORDED -> "미기록 운행 · 운전자 미확인"
        Check.DRIVER, Check.PURPOSE -> when {
            trip.assignedByName != null -> "잠정 기록 · ${trip.assignedByName} 님이 지정"
            trip.closedByServer -> "잠정 기록 · 응답 없이 자동 종료"
            trip.check == Check.DRIVER -> "잠정 기록 · 확인 없이 출발"
            else -> "잠정 기록 · 용도 미확정"
        }
        null -> ""
    }

    /** M2 provisional card: "이서연 님 · 개인 용도로 잠정 기록했습니다" */
    fun provisionalLine(trip: PhoneTrip): String =
        "${trip.driverName ?: "운전자 미확인"} 님 · ${(trip.purpose ?: Purpose.BUSINESS).label} 용도로 잠정 기록했습니다"

    /** M3 driver line: "김민준 · 차에서 확인" */
    fun driverLine(trip: PhoneTrip): String {
        val name = trip.driverName ?: "운전자 미확인"
        val how = when {
            trip.driverName == null -> null
            trip.driverProvisional -> "잠정"
            trip.assignedByName != null -> "${trip.assignedByName} 님이 지정"
            else -> "차에서 확인"
        }
        return if (how == null) name else "$name · $how"
    }

    /** M4 last charge: "마지막 충전 어제 22:14 · 41% → 80%" */
    fun lastCharge(at: Long, from: Int?, to: Int?): String {
        val range = if (from != null && to != null) " · $from% → $to%" else ""
        return "마지막 충전 ${dayWord(at)} ${time(at)}$range"
    }

    /** M4 connection: "오늘 09:45 수신" */
    fun lastUpload(at: Long): String = "${dayWord(at)} ${time(at)} 수신"

    companion object {
        fun kmNumber(km: Double): String = if (km >= 100) String.format(Locale.ROOT, "%,d", km.roundToInt()) else String.format(Locale.ROOT, "%.1f", km)
    }
}
