package com.bobcar.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobcar.vehicle.checkin.Briefing
import com.bobcar.vehicle.checkin.CheckinState
import com.bobcar.vehicle.checkin.DefaultDriver
import com.bobcar.vehicle.checkin.DefaultSource
import com.bobcar.vehicle.checkin.Unrecorded
import com.bobcar.vehicle.data.DriverEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear
import java.util.Locale

/**
 * Driver card shown when the driver sits down (Figma V3 기본값 / V4 목록 / V5 확인완료),
 * with the [briefing] row at the bottom of V3 / V5.
 * One tap confirms the suggested driver, [다른 사람] opens the driver list. Only shown in P
 * (CLAUDE.md rule 3, see [showsDriverCard]); closes when the brake is pressed in P or the car leaves P
 * (unconfirmed → recorded as provisional).
 */
@Composable
fun DriverCard(checkin: CheckinState, drivers: List<DriverEntity>, briefing: Briefing, onConfirm: (String) -> Unit) {
    val nameOf = { id: String? -> drivers.firstOrNull { it.id == id }?.name }
    val suggested = checkin.default?.let { d -> nameOf(d.driverId)?.let { d to it } }
    val confirmedName = nameOf(checkin.confirmedDriverId)
    // The list opens by itself when there is nothing to suggest; reset for every new boarding
    var choosing by remember(checkin.boardedAt) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().bobBackground()) {
        when {
            choosing || (suggested == null && confirmedName == null) -> DriverList(
                drivers = drivers,
                current = checkin.driverId,
                default = checkin.default,
                onPick = {
                    choosing = false
                    onConfirm(it)
                },
                onBack = if (suggested != null || confirmedName != null) ({ choosing = false }) else null,
            )

            confirmedName != null -> {
                Confirmed(confirmedName, onOther = { choosing = true })
                BriefingRow(briefing, Modifier.align(Alignment.BottomStart))
            }

            else -> {
                val (default, name) = suggested!!
                Suggested(default, name, onConfirm = { onConfirm(default.driverId) }, onOther = { choosing = true })
                BriefingRow(briefing, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/** Bottom row of V3 / V5: 배터리 · 누적 거리 · 미기록 운행. */
@Composable
private fun BriefingRow(briefing: Briefing, modifier: Modifier) {
    Row(modifier = modifier.padding(start = BobDimens.PageMargin, bottom = 80.dp)) {
        Stat("배터리", briefing.socPercent?.let { "$it %" } ?: "—")
        Stat("누적 거리", briefing.odometerKm?.let { String.format(Locale.KOREA, "%,d km", it.toLong()) } ?: "—")
        when (briefing.unrecorded) {
            is Unrecorded.Found -> Stat("미기록 운행", "1건", BobColors.AccentSoft)
            Unrecorded.None -> Stat("미기록 운행", "없음")
            Unrecorded.Unknown -> Stat("미기록 운행", "확인 불가", BobColors.TextSecondary)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color = BobColors.TextPrimary) {
    Column(modifier = Modifier.width(510.dp)) {
        Text(label, style = BobType.Label)
        Spacer(Modifier.height(30.dp))
        Text(value, style = BobType.Stat, color = valueColor)
    }
}

/** V3: suggested driver with [확인] / [다른 사람]. */
@Composable
private fun Suggested(default: DefaultDriver, name: String, onConfirm: () -> Unit, onOther: () -> Unit) {
    Column(modifier = Modifier.padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 144.dp)) {
        SourceChip(default.source)
        Spacer(Modifier.height(26.dp))
        Text("$name 님이\n운전하시나요?", style = BobType.Hero, maxLines = 2)
        Spacer(Modifier.height(58.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BobPrimaryButton("확인", onConfirm, icon = Icons.Filled.Check, minWidth = 308.dp)
            BobSecondaryButton("다른 사람", onOther, minWidth = 284.dp)
        }
        Spacer(Modifier.height(30.dp))
        Text("확인하지 않고 출발하면 $name 님으로 잠정 기록됩니다", style = BobType.Body)
    }
}

/** V5: driver confirmed. */
@Composable
private fun Confirmed(name: String, onOther: () -> Unit) {
    Column(modifier = Modifier.padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 150.dp)) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = BobColors.Accent, modifier = Modifier.size(76.dp))
        Spacer(Modifier.height(24.dp))
        Text("$name 님", style = BobType.HeroName, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        Text("운전자로 확인했습니다", style = BobType.Subtitle)
        Spacer(Modifier.height(64.dp))
        BobSecondaryButton("다른 사람", onOther, minWidth = 250.dp, minHeight = 102.dp, textStyle = BobType.ButtonSmall)
    }
}

/** V4: pick the driver from the list, two columns. */
@Composable
internal fun DriverList(
    drivers: List<DriverEntity>,
    current: String?,
    default: DefaultDriver?,
    onPick: (String) -> Unit,
    onBack: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize().padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 112.dp)) {
        Text("누가 운전하시나요?", style = BobType.Title)
        Spacer(Modifier.height(52.dp))
        if (drivers.isEmpty()) {
            Text("등록된 운전자가 없습니다", style = BobType.Subtitle)
        }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            drivers.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.forEach { driver ->
                        DriverItem(
                            name = driver.name,
                            note = default?.takeIf { it.driverId == driver.id }?.let { sourceLabel(it.source) },
                            selected = driver.id == current,
                            onClick = { onPick(driver.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (onBack != null) {
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(BobDimens.ControlRadius))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = BobColors.Accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("뒤로", style = BobType.ButtonSmall, color = BobColors.Accent)
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

@Composable
private fun DriverItem(name: String, note: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(BobDimens.CardRadius)
    Row(
        modifier = modifier
            .heightIn(min = 148.dp)
            .clip(shape)
            .background(if (selected) BobColors.SelectedFill else BobColors.Card)
            .border(BorderStroke(1.dp, if (selected) BobColors.Accent else BobColors.Outline), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 48.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AccountCircle,
            contentDescription = null,
            tint = if (selected) BobColors.Accent else BobColors.Icon,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(36.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(name, style = BobType.ListName)
            if (note != null) {
                Text(note, style = BobType.Label, color = if (selected) BobColors.SelectedSubtext else BobColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun SourceChip(source: DefaultSource) {
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(BobDimens.ChipRadius))
            .background(BobColors.ChipFill)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sourceIcon(source), contentDescription = null, tint = BobColors.ChipText, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(sourceLabel(source), style = BobType.Label, color = BobColors.ChipText)
    }
}

private fun sourceLabel(source: DefaultSource): String = when (source) {
    DefaultSource.PROFILE -> "차량 프로필"
    DefaultSource.RESERVATION -> "지금 시간 예약자"
    DefaultSource.LAST_DRIVER -> "직전 운전자"
}

private fun sourceIcon(source: DefaultSource): ImageVector = when (source) {
    DefaultSource.PROFILE -> Icons.Outlined.Person
    DefaultSource.RESERVATION -> Icons.Outlined.DateRange
    DefaultSource.LAST_DRIVER -> Icons.Outlined.Refresh
}

/** The card is up only while checking in, before it closed, and with the gear settled in P. */
fun showsDriverCard(state: DrivingState, checkin: CheckinState?, gear: Gear?): Boolean =
    state is DrivingState.CheckingIn && !state.checkinClosed &&
        checkin != null && !checkin.closed &&
        gear == Gear.PARK

private val previewDrivers = listOf(
    DriverEntity("drv-001", "김민준"),
    DriverEntity("drv-002", "이서연"),
    DriverEntity("drv-003", "박지호"),
)

private val previewBriefing = Briefing.of(socPercent = 78, odometerKm = 12_482f, lastRecordedOdometerKm = 12_476f)

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun DriverCardPreview() {
    BobTheme {
        DriverCard(CheckinState(0, DefaultDriver("drv-001", DefaultSource.RESERVATION)), previewDrivers, previewBriefing, onConfirm = {})
    }
}

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun DriverCardConfirmedPreview() {
    BobTheme {
        DriverCard(
            CheckinState(0, DefaultDriver("drv-001", DefaultSource.RESERVATION), confirmedDriverId = "drv-001"),
            previewDrivers,
            previewBriefing,
            onConfirm = {},
        )
    }
}

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun DriverCardListPreview() {
    BobTheme {
        DriverCard(CheckinState(0, null), previewDrivers, previewBriefing, onConfirm = {})
    }
}
