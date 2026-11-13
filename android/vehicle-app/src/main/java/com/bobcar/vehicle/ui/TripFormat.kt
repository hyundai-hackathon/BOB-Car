package com.bobcar.vehicle.ui

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display formats shared by the screens. */
internal object TripFormat {
    fun clock(wallMillis: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(wallMillis))

    fun km(value: Float?): String = value?.let { "%,.1f km".format(Locale.KOREA, it) } ?: "—"

    /** null = no guidance; 0 = guidance on a free route. */
    fun toll(fare: Int?): String = when (fare) {
        null -> "—"
        0 -> "없음"
        else -> NumberFormat.getNumberInstance(Locale.KOREA).format(fare) + "원"
    }

    fun destination(name: String?): String = name ?: "목적지 없음"

    /** Trip time in whole minutes: "32분", "1시간 5분". */
    fun duration(millis: Long): String {
        val minutes = (millis.coerceAtLeast(0) / 60_000).toInt()
        return if (minutes < 60) "${minutes}분" else "${minutes / 60}시간 ${minutes % 60}분"
    }
}
