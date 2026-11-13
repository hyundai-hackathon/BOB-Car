package com.bobcar.vehicle.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.SignalConfig
import com.bobcar.vehicle.trip.EndChoice
import com.bobcar.vehicle.trip.Purpose
import com.bobcar.vehicle.trip.PurposeSource

/**
 * Trip-end card (Figma V8 운행종료): the end countdown with [계속 운행], this trip's distance, time and
 * toll, and the driver and purpose it will be saved with. Shown only during the end countdown, which
 * exists only with the gear settled in P (CLAUDE.md rule 3); leaving P cancels the countdown and the card.
 * [변경] opens the driver list (V4) and the countdown waits until it closes.
 * Untouched, the trip is saved with what the card shows when the countdown runs out.
 */
@Composable
fun TripEndCard(
    state: DrivingState.EndCountdown,
    trip: TripEntity?,
    choice: EndChoice?,
    drivers: List<DriverEntity>,
    odometerKm: Float?,
    onContinue: () -> Unit,
    onPurpose: (Purpose) -> Unit,
    onOpenDrivers: () -> Unit,
    onPickDriver: (String) -> Unit,
    onCloseDrivers: () -> Unit,
    /** Wall clock for the trip time; read on every countdown tick. */
    nowWallMillis: () -> Long = System::currentTimeMillis,
) {
    Box(modifier = Modifier.fillMaxSize().bobBackground()) {
        if (state.paused) {
            DriverList(drivers = drivers, current = choice?.driverId, default = null, onPick = onPickDriver, onBack = onCloseDrivers)
            return@Box
        }
        val seconds = remainingSeconds(state.deadline)
        val distance = trip?.startOdometerKm?.let { start -> odometerKm?.let { (it - start).takeIf { d -> d >= 0f } } }
        val driverName = drivers.firstOrNull { it.id == choice?.driverId }?.name

        Column(modifier = Modifier.fillMaxSize().padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 104.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                    CountdownTitle(seconds)
                    Spacer(Modifier.height(18.dp))
                    CountdownBar(seconds)
                }
                Spacer(Modifier.width(48.dp))
                BobPrimaryButton("계속 운행", onContinue, minWidth = 300.dp, minHeight = 118.dp, textStyle = BobType.ButtonSmall)
            }
            Spacer(Modifier.height(80.dp))
            Row {
                EndStat("주행 거리", TripFormat.km(distance))
                EndStat("운행 시간", trip?.let { TripFormat.duration(nowWallMillis() - it.startedAt) } ?: "—")
                EndStat("통행료", TripFormat.toll(trip?.expectedTollFare))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowLabel("운전자")
                Text(driverName ?: "미확인", style = BobType.ListName)
                Spacer(Modifier.width(24.dp))
                BobSecondaryButton("변경", onOpenDrivers, minWidth = 136.dp, minHeight = 92.dp, textStyle = BobType.ButtonSmall)
            }
            Spacer(Modifier.height(44.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowLabel("용도")
                PurposeSelector(choice?.purpose, onPurpose)
            }
            Spacer(Modifier.height(40.dp))
            Text("시간이 지나면 위 내용으로 저장됩니다. 관리자 웹에서 나중에 고칠 수 있습니다.", style = BobType.Body)
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun CountdownTitle(seconds: Long) {
    Text(
        buildAnnotatedString {
            append("운행 종료까지 ")
            withStyle(SpanStyle(color = BobColors.Accent)) { append(seconds.toString()) }
            append("초")
        },
        style = BobType.Title,
        maxLines = 1,
    )
}

/** Accent line under the title that shrinks with the countdown. */
@Composable
private fun CountdownBar(seconds: Long) {
    val total = SignalConfig.END_COUNTDOWN_MS / 1000f
    val fraction by animateFloatAsState(
        targetValue = (seconds / total).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1_000, easing = LinearEasing),
        label = "countdown",
    )
    Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(BobColors.Accent),
        )
    }
}

@Composable
private fun EndStat(label: String, value: String) {
    Column(modifier = Modifier.width(510.dp)) {
        Text(label, style = BobType.Label)
        Spacer(Modifier.height(30.dp))
        Text(value, style = BobType.Stat)
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(text, style = BobType.Label, modifier = Modifier.width(172.dp))
}

/** 업무 · 출퇴근 · 개인, one connected row. The selected one is highlighted. */
@Composable
private fun PurposeSelector(selected: Purpose?, onPurpose: (Purpose) -> Unit) {
    val shape = RoundedCornerShape(BobDimens.ControlRadius)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(BobColors.Control)
            .border(BorderStroke(1.dp, BobColors.Outline), shape),
    ) {
        Purpose.entries.forEachIndexed { index, purpose ->
            if (index > 0) Box(Modifier.width(1.dp).height(112.dp).background(BobColors.Outline))
            PurposeSegment(purpose, purpose == selected) { onPurpose(purpose) }
        }
    }
}

@Composable
private fun PurposeSegment(purpose: Purpose, selected: Boolean, onClick: () -> Unit) {
    val content = if (selected) BobColors.Accent else BobColors.TextPrimary
    Row(
        modifier = Modifier
            .width(248.dp)
            .heightIn(min = 112.dp)
            .background(if (selected) BobColors.SelectedFill else Color.Transparent)
            .then(if (selected) Modifier.border(BorderStroke(1.dp, BobColors.Accent)) else Modifier)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(purposeIcon(purpose), contentDescription = null, tint = content, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Text(purposeLabel(purpose), style = BobType.Button, color = content)
    }
}

internal fun purposeLabel(purpose: Purpose): String = when (purpose) {
    Purpose.BUSINESS -> "업무"
    Purpose.COMMUTE -> "출퇴근"
    Purpose.PERSONAL -> "개인"
}

private fun purposeIcon(purpose: Purpose): ImageVector = when (purpose) {
    Purpose.BUSINESS -> BriefcaseIcon
    Purpose.COMMUTE -> BuildingIcon
    Purpose.PERSONAL -> Icons.Outlined.Home
}

/** Outline briefcase (업무). Drawn here: the core icon set has none. */
private val BriefcaseIcon: ImageVector by lazy {
    outlineIcon("Briefcase") {
        moveTo(4f, 7.5f); lineTo(20f, 7.5f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(9f, 7.5f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7.5f)
        moveTo(4f, 12.5f); lineTo(20f, 12.5f)
        moveTo(12f, 11f); lineTo(12f, 14f)
    }
}

/** Outline office building (출퇴근). Drawn here: the core icon set has none. */
private val BuildingIcon: ImageVector by lazy {
    outlineIcon("Building") {
        moveTo(3f, 20f); lineTo(21f, 20f)
        moveTo(5f, 20f); lineTo(5f, 4.5f); lineTo(13f, 4.5f); lineTo(13f, 20f)
        moveTo(13f, 9.5f); lineTo(19f, 9.5f); lineTo(19f, 20f)
        moveTo(8f, 8f); lineTo(10f, 8f)
        moveTo(8f, 11.5f); lineTo(10f, 11.5f)
        moveTo(8f, 15f); lineTo(10f, 15f)
        moveTo(15.5f, 13f); lineTo(16.5f, 13f)
        moveTo(15.5f, 16f); lineTo(16.5f, 16f)
    }
}

private fun outlineIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()

private val previewDrivers = listOf(DriverEntity("drv-001", "김민준"), DriverEntity("drv-002", "이서연"))

private val previewTrip = TripEntity(
    status = TripEntity.STATUS_ACTIVE,
    startedAt = 0,
    startOdometerKm = 12_464f,
    destinationName = "판교 테크노밸리",
    expectedTollFare = 2_400,
)

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun TripEndCardPreview() {
    BobTheme {
        TripEndCard(
            state = DrivingState.EndCountdown(tripStartedAt = 0, deadline = Long.MAX_VALUE),
            trip = previewTrip,
            choice = EndChoice(1, "drv-001", true, Purpose.BUSINESS, PurposeSource.DEFAULT),
            drivers = previewDrivers,
            odometerKm = 12_482.4f,
            onContinue = {}, onPurpose = {}, onOpenDrivers = {}, onPickDriver = {}, onCloseDrivers = {},
            nowWallMillis = { 32 * 60_000L },
        )
    }
}
