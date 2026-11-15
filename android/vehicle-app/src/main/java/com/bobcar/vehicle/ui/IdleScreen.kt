package com.bobcar.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.data.distanceKm
import com.bobcar.vehicle.trip.Purpose
import com.bobcar.vehicle.upload.UploadStatus
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * V1 대기 (Figma "BOB-Car 화면 디자인"): the screen between trips. Display only, no buttons.
 * Layout measured from the frame (1680 × 1210 px = 1 dp per px).
 */

/** Waiting screen: what happens on boarding, whether records reached the server, and the car's state. */
@Composable
fun IdleScreen(
    vehicleLabel: String,
    status: IdleStatus,
    lastTrip: TripEntity?,
    drivers: List<DriverEntity>,
    socPercent: Int?,
    odometerKm: Float?,
    fakeMode: Boolean,
    modifier: Modifier = Modifier,
    nowWallMillis: () -> Long = System::currentTimeMillis,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .bobBackground()
            .padding(horizontal = BobDimens.PageMargin, vertical = 72.dp),
    ) {
        Header(vehicleLabel, fakeMode)
        Spacer(Modifier.height(130.dp))
        Text("탑승하면 운행 기록이\n자동으로 시작됩니다", style = IdleType.Headline)
        Spacer(Modifier.height(40.dp))
        StatusLine(status)
        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().height(293.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            val (title, detail) = lastTripLines(lastTrip, drivers, nowWallMillis())
            InfoCard("마지막 운행", Modifier.weight(2f)) {
                Text(title, style = IdleType.TripTitle, maxLines = 1)
                if (detail != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(detail, style = BobType.Body.copy(fontSize = 30.sp, lineHeight = 44.sp), maxLines = 2)
                }
            }
            InfoCard("배터리", Modifier.weight(1f)) { StatValue(socPercent?.toString() ?: "—", "%") }
            InfoCard("누적 거리", Modifier.weight(1f)) { StatValue(odometerLabel(odometerKm), "km") }
        }
    }
}

@Composable
private fun Header(vehicleLabel: String, fakeMode: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(CarIcon, contentDescription = null, tint = BobColors.Accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Text("BOB-Car", style = IdleType.Header.copy(color = BobColors.TextPrimary, fontWeight = FontWeight.Medium))
        if (fakeMode) {
            // Debug builds only: fake signals are on (the V9 diagnostic frame shows the same badge)
            Spacer(Modifier.width(20.dp))
            Text(
                "가짜 신호 모드",
                style = BobType.Label.copy(color = FakeModeRed),
                modifier = Modifier
                    .border(BorderStroke(1.dp, FakeModeRed), RoundedCornerShape(BobDimens.ChipRadius))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(vehicleLabel, style = IdleType.Header)
    }
}

@Composable
private fun StatusLine(status: IdleStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon = when (status.kind) {
            IdleStatus.Kind.ALL_SENT -> CheckCircleIcon
            IdleStatus.Kind.WAITING -> ClockIcon
            IdleStatus.Kind.LOCAL_ONLY -> InfoIcon
        }
        Icon(icon, contentDescription = null, tint = BobColors.Accent, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Text(status.text, style = IdleType.Status)
    }
}

@Composable
private fun InfoCard(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(BobDimens.CardRadius))
            .background(BobColors.BgTop)
            .padding(horizontal = 40.dp, vertical = 38.dp),
    ) {
        Column {
            Text(label, style = BobType.Label.copy(color = BobColors.Accent, letterSpacing = 2.sp))
            Spacer(Modifier.height(28.dp))
            content()
        }
    }
}

@Composable
private fun StatValue(value: String, unit: String) {
    Text(
        buildAnnotatedString {
            append(value)
            withStyle(SpanStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, color = BobColors.TextSecondary)) {
                append(" $unit")
            }
        },
        style = IdleType.Stat,
        maxLines = 1,
    )
}

/** What the status line under the headline says about the upload queue. */
data class IdleStatus(val kind: Kind, val text: String) {
    enum class Kind { ALL_SENT, WAITING, LOCAL_ONLY }

    companion object {
        /**
         * [sendablePending]: unsent records that may go to the server (drivers who agreed). Records of drivers
         * who have not agreed stay on the vehicle on purpose (CLAUDE.md rule 6) and are not "waiting".
         */
        fun of(upload: UploadStatus.State, sendablePending: Int): IdleStatus {
            if (!upload.serverConfigured) return IdleStatus(Kind.LOCAL_ONLY, "기록은 차량에 저장됩니다")
            if (sendablePending == 0) return IdleStatus(Kind.ALL_SENT, "모든 기록이 서버에 전송되었습니다")
            val failure = upload.lastFailureAt
            val success = upload.lastSuccessAt
            val failedLast = failure != null && (success == null || failure > success)
            val text = if (failedLast) "전송 대기 ${sendablePending}건 · 연결되면 자동으로 보냅니다" else "전송 대기 ${sendablePending}건"
            return IdleStatus(Kind.WAITING, text)
        }
    }
}

/**
 * The last trip card: "판교 테크노밸리 · 18.4 km" and "오늘 09:12 – 09:44 · 김민준 · 업무 · 통행료 2,400원".
 * Personal trips have no destination (CLAUDE.md rule 6), so they read "개인 운행".
 */
internal fun lastTripLines(trip: TripEntity?, drivers: List<DriverEntity>, nowWallMillis: Long): Pair<String, String?> {
    if (trip == null) return "아직 운행 기록이 없습니다" to null
    val purpose = trip.purpose?.let { name -> Purpose.entries.firstOrNull { it.name == name } }
    val place = when {
        purpose == Purpose.PERSONAL -> "개인 운행"
        else -> TripFormat.destination(trip.destinationName)
    }
    val title = "$place · ${TripFormat.km(trip.distanceKm())}"
    val time = buildString {
        append(dayLabel(trip.startedAt, nowWallMillis))
        append(' ')
        append(TripFormat.clock(trip.startedAt))
        trip.endedAt?.let { append(" – ").append(TripFormat.clock(it)) }
    }
    val driver = trip.driverId?.let { id -> drivers.firstOrNull { it.id == id }?.name ?: id } ?: "운전자 미확인"
    val toll = trip.expectedTollFare?.takeIf { it > 0 }?.let { "통행료 " + NumberFormat.getNumberInstance(Locale.KOREA).format(it) + "원" }
    val detail = listOfNotNull(time, driver, purpose?.let(::purposeLabel), toll).joinToString(" · ")
    return title to detail
}

/** "오늘", "어제", or "11월 12일" (device time zone). */
internal fun dayLabel(wallMillis: Long, nowWallMillis: Long): String {
    fun dayIndex(ms: Long): Int = Calendar.getInstance().run {
        timeInMillis = ms
        get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
    }
    return when (dayIndex(nowWallMillis) - dayIndex(wallMillis)) {
        0 -> "오늘"
        1 -> "어제"
        else -> SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(wallMillis))
    }
}

/** Whole kilometres with grouping ("12,482"); the diagnostic screen shows the decimals. */
internal fun odometerLabel(km: Float?): String =
    km?.let { NumberFormat.getIntegerInstance(Locale.KOREA).apply { roundingMode = java.math.RoundingMode.DOWN }.format(it.toDouble()) } ?: "—"

private object IdleType {
    val Header = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, color = BobColors.TextSecondary)
    val Headline = TextStyle(fontSize = 84.sp, lineHeight = 100.sp, color = BobColors.TextPrimary)
    val Status = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, color = BobColors.TextSubtle)
    val TripTitle = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, color = BobColors.TextPrimary)
    val Stat = TextStyle(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.SemiBold, color = BobColors.TextPrimary)
}

private val FakeModeRed = androidx.compose.ui.graphics.Color(0xFFE8746C)

/** Outline car (header). */
private val CarIcon: ImageVector by lazy {
    outlineIcon("Car") {
        moveTo(3f, 16f); lineTo(3f, 12.5f); lineTo(5.5f, 8.5f); lineTo(15.5f, 8.5f); lineTo(19f, 12f)
        lineTo(21f, 12.6f); lineTo(21f, 16f); lineTo(18.5f, 16f)
        moveTo(14.5f, 16f); lineTo(9.5f, 16f)
        moveTo(5.5f, 16f); lineTo(3f, 16f)
        moveTo(5.5f, 16f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f); arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
        moveTo(14.5f, 16f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f); arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
        moveTo(3.5f, 12.5f); lineTo(19f, 12.5f)
    }
}

/** Outline check in a circle (all records sent). */
private val CheckCircleIcon: ImageVector by lazy {
    outlineIcon("CheckCircle") {
        circle()
        moveTo(8f, 12.3f); lineTo(10.8f, 15f); lineTo(16f, 9.5f)
    }
}

/** Outline clock (records waiting to be sent). */
private val ClockIcon: ImageVector by lazy {
    outlineIcon("Clock") {
        circle()
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
}

/** Outline info (no server in this build: records stay on the vehicle). */
private val InfoIcon: ImageVector by lazy {
    outlineIcon("Info") {
        circle()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.8f); lineTo(12f, 8f)
    }
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.circle() {
    moveTo(3f, 12f)
    arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
    arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
}

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun IdleScreenPreview() {
    BobTheme {
        IdleScreen(
            vehicleLabel = "아이오닉 5 · 12가 3456",
            status = IdleStatus(IdleStatus.Kind.ALL_SENT, "모든 기록이 서버에 전송되었습니다"),
            lastTrip = TripEntity(
                status = TripEntity.STATUS_ENDED,
                startedAt = 0,
                endedAt = 32 * 60_000L,
                startOdometerKm = 12_464f,
                endOdometerKm = 12_482.4f,
                driverId = "drv-001",
                purpose = "BUSINESS",
                destinationName = "판교 테크노밸리",
                expectedTollFare = 2_400,
            ),
            drivers = listOf(DriverEntity("drv-001", "김민준")),
            socPercent = 78,
            odometerKm = 12_482.4f,
            fakeMode = false,
            nowWallMillis = { 40 * 60_000L },
        )
    }
}
