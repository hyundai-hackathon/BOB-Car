package com.bobcar.vehicle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear

/*
 * V6 운행중 (Figma "BOB-Car 화면 디자인"): shown while a trip runs and the screen is visible (P after
 * [계속 운행]; in D/R the vehicle OS covers apps). Layout measured from the frame (1 dp per px).
 */

/**
 * Waiting screen while a trip runs. Display only: no buttons or input of any kind, so it is safe
 * while driving (CLAUDE.md rule 3). The end countdown in P shows [TripEndCard] instead.
 */
@Composable
fun TripScreen(state: DrivingState, trip: TripEntity?, odometerKm: Float?, drivers: List<DriverEntity> = emptyList()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bobBackground()
            .padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 110.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(14.dp))
            Text("운행 중", style = TripType.Title)
            Spacer(Modifier.weight(1f))
            if (trip != null) {
                Icon(PersonIcon, contentDescription = null, tint = BobColors.TextSecondary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text(driverName(trip, drivers), style = TripType.Driver)
            }
        }
        if (trip == null) {
            Spacer(Modifier.height(93.dp))
            Text("운행 기록을 준비하고 있습니다", style = TripType.Detail)
            return@Column
        }
        Spacer(Modifier.height(93.dp))
        val soFar = trip.startOdometerKm?.let { start -> odometerKm?.let { (it - start).takeIf { d -> d >= 0f } } }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Item("출발", TripFormat.clock(trip.startedAt), Modifier.weight(1f), detail = "누적 ${odometerLabel(odometerKm)} km")
            Item("주행 거리", TripFormat.km(soFar), Modifier.weight(1f))
        }
        Spacer(Modifier.height(93.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Item("목적지", TripFormat.destination(trip.destinationName), Modifier.weight(1f), valueStyle = TripType.Place)
            Item("예상 통행료", TripFormat.toll(trip.expectedTollFare), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Item(
    label: String,
    value: String,
    modifier: Modifier,
    detail: String? = null,
    valueStyle: TextStyle = TripType.Value,
) {
    Column(modifier = modifier) {
        Text(label, style = TripType.Label)
        Spacer(Modifier.height(42.dp))
        Text(value, style = valueStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Spacer(Modifier.height(26.dp))
            Text(detail, style = TripType.Detail)
        }
    }
}

/** Accent dot with a soft glow, before "운행 중". */
@Composable
private fun LiveDot() {
    Canvas(modifier = Modifier.size(28.dp)) {
        drawCircle(BobColors.Accent.copy(alpha = 0.18f), radius = size.minDimension / 2)
        drawCircle(BobColors.Accent, radius = 9.dp.toPx())
    }
}

private fun driverName(trip: TripEntity, drivers: List<DriverEntity>): String =
    trip.driverId?.let { id -> drivers.firstOrNull { it.id == id }?.name ?: id } ?: "운전자 미확인"

/** Trip screen while driving (including P after [계속 운행]); the end countdown has its own card. */
fun showsTripScreen(state: DrivingState): Boolean = state is DrivingState.Driving

private object TripType {
    val Title = TextStyle(fontSize = 80.sp, lineHeight = 104.sp, color = BobColors.TextPrimary)
    val Driver = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, color = BobColors.TextSecondary)
    val Label = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, color = BobColors.TextSecondary)
    val Value = TextStyle(fontSize = 72.sp, lineHeight = 84.sp, fontWeight = FontWeight.SemiBold, color = BobColors.TextPrimary)
    val Place = TextStyle(fontSize = 72.sp, lineHeight = 84.sp, color = BobColors.TextPrimary)
    val Detail = TextStyle(fontSize = 30.sp, lineHeight = 40.sp, color = BobColors.TextSecondary)
}

/** Outline person (driver name). */
private val PersonIcon: ImageVector by lazy {
    outlineIcon("Person") {
        moveTo(8.5f, 8f); arcToRelative(3.5f, 3.5f, 0f, true, true, 7f, 0f); arcToRelative(3.5f, 3.5f, 0f, true, true, -7f, 0f)
        moveTo(5f, 20f); curveTo(5.5f, 16.2f, 8.4f, 14.5f, 12f, 14.5f); curveTo(15.6f, 14.5f, 18.5f, 16.2f, 19f, 20f)
    }
}

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun TripScreenPreview() {
    BobTheme {
        TripScreen(
            state = DrivingState.Driving(0, Gear.DRIVE),
            trip = TripEntity(
                status = TripEntity.STATUS_ACTIVE,
                startedAt = 1_731_200_000_000,
                startOdometerKm = 12_464f,
                driverId = "drv-001",
                destinationName = "판교 테크노밸리",
                expectedTollFare = 2_400,
            ),
            odometerKm = 12_482.4f,
            drivers = listOf(DriverEntity("drv-001", "김민준")),
        )
    }
}
