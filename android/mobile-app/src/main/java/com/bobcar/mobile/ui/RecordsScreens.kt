package com.bobcar.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobcar.mobile.R
import com.bobcar.mobile.data.Check
import com.bobcar.mobile.data.Driver
import com.bobcar.mobile.data.Me
import com.bobcar.mobile.data.PhoneTrip
import com.bobcar.mobile.data.Purpose
import com.bobcar.mobile.data.TripChange
import com.bobcar.mobile.data.VehicleStatus

/*
 * M1 운행 기록 and M2 확인 (Figma Mobile M1, M2). Only the driver's own trips and the unrecorded drives they
 * may have driven come from the server; other people's trips never reach the phone.
 */

val PURPOSE_OPTIONS = Purpose.entries.map { it to it.label }

// ---------------------------------------------------------------- M1

@Composable
fun RecordsScreen(
    trips: List<PhoneTrip>?,
    vehicle: VehicleStatus?,
    text: TripText,
    onOpenChecks: () -> Unit,
    onOpen: (PhoneTrip) -> Unit,
    onRefresh: () -> Unit,
) {
    val toCheck = trips.orEmpty().count { it.check != null }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(sub = vehicle?.let { listOfNotNull(it.model, it.plate).joinToString(" · ") }, title = "운행 기록", trailing = { RefreshButton(onRefresh) })
        if (toCheck > 0) CheckBanner("확인이 필요한 운행 ${toCheck}건", onOpenChecks)
        when {
            trips == null -> Unit
            trips.isEmpty() -> Empty(R.drawable.ic_ph_list_bullets, "아직 운행 기록이 없어요", "차에서 운행을 마치면 여기에 나와요")
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = MDimens.Gutter, end = MDimens.Gutter, top = 20.dp, bottom = 16.dp)) {
                trips.groupBy { text.dayLabel(it.startedAt) }.forEach { (day, dayTrips) ->
                    item(key = "day-$day") { Text(day, style = MType.Section, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(dayTrips, key = { it.tripDocId }) { trip -> TripRow(trip, text, onClick = { onOpen(trip) }.takeUnless { trip.active }) }
                }
            }
        }
    }
}

/** M1 banner: warning icon and text on the left, caret on the right, accent outline. */
@Composable
private fun CheckBanner(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MDimens.RadiusMd)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 20.dp)
            .heightIn(min = 56.dp)
            .clip(shape)
            .border(1.dp, MColors.Accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(R.drawable.ic_ph_warning_circle, MColors.Accent, size = 20.dp)
        Text(label, style = MType.Button, color = MColors.Accent, modifier = Modifier.weight(1f))
        PhIcon(R.drawable.ic_ph_caret_right, MColors.Accent, size = 18.dp)
    }
}

@Composable
private fun TripRow(trip: PhoneTrip, text: TripText, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(MDimens.RadiusMd))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text.title(trip), style = MType.RowTitle, maxLines = 1)
            Text(text.meta(trip), style = MType.Sub, color = if (trip.driverName == null) MColors.Accent300 else MColors.Neutral400, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.km(trip.distanceKm), style = MType.Body)
            PurposeTag(trip)
        }
    }
}

/** 업무 (accent), 출퇴근 / 개인 (neutral), 미확정 or 운행 중 (outline). */
@Composable
fun PurposeTag(trip: PhoneTrip) = when {
    trip.active -> Tag("운행 중", TagKind.Outline)
    trip.check == Check.UNRECORDED || trip.purpose == null -> Tag("미확정", TagKind.Outline)
    trip.purpose == Purpose.BUSINESS -> Tag(trip.purpose.label, TagKind.Accent)
    else -> Tag(trip.purpose.label, TagKind.Neutral)
}

@Composable
fun RefreshButton(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(MDimens.RadiusMd)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        PhIcon(R.drawable.ic_ph_arrow_clockwise, MColors.Neutral400, size = 22.dp)
    }
}

@Composable
fun Empty(icon: Int, title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = MDimens.Gutter, end = MDimens.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        PhIcon(icon, MColors.Neutral400, size = 40.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MType.Body)
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MType.Sub)
    }
}

// ---------------------------------------------------------------- M2

@Composable
fun CheckScreen(
    trips: List<PhoneTrip>,
    me: Me,
    drivers: List<Driver>,
    busy: Boolean,
    text: TripText,
    onSave: (PhoneTrip, TripChange) -> Unit,
    onEdit: (PhoneTrip) -> Unit,
    onNeedDrivers: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(sub = if (trips.isEmpty()) "모두 확인했어요" else "${trips.size}건 남음", title = "확인이 필요합니다")
        if (trips.isEmpty()) {
            Empty(R.drawable.ic_ph_check_circle, "확인할 운행이 없어요", "차에서 확인하지 못한 운행이 생기면 여기에 나와요")
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(MDimens.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(trips, key = { it.tripDocId }) { trip ->
                if (trip.check == Check.UNRECORDED) UnrecordedCard(trip, me, drivers, busy, text, onSave, onNeedDrivers)
                else ProvisionalCard(trip, busy, text, onConfirm = { onSave(trip, TripChange(confirm = true)) }, onEdit = { onEdit(trip) })
            }
        }
    }
}

@Composable
private fun CardHead(trip: PhoneTrip, text: TripText) {
    Text(text.kicker(trip), style = MType.Kicker)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(text.whenRange(trip), style = MType.CardTime)
        Text(text.km(trip.distanceKm), style = MType.Body, color = MColors.Neutral300)
    }
}

/** Whose drive was this? [나] / the other candidate / [다른 사람], then the purpose, then [저장]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnrecordedCard(
    trip: PhoneTrip,
    me: Me,
    drivers: List<Driver>,
    busy: Boolean,
    text: TripText,
    onSave: (PhoneTrip, TripChange) -> Unit,
    onNeedDrivers: () -> Unit,
) {
    var driverId by rememberSaveable(trip.tripDocId) { mutableStateOf<String?>(null) }
    var purpose by rememberSaveable(trip.tripDocId) { mutableStateOf(Purpose.BUSINESS) }
    var picking by remember { mutableStateOf(false) }
    val others = trip.candidates.filter { it.id != me.driverId }
    val pickedOther = driverId?.takeIf { id -> id != me.driverId && others.none { it.id == id } }
    MCard {
        CardHead(trip, text)
        Field("운전자") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("나(${me.name})", driverId == me.driverId) { driverId = me.driverId }
                others.forEach { c -> Choice(c.name, driverId == c.id) { driverId = c.id } }
                Choice(pickedOther?.let { id -> drivers.firstOrNull { it.id == id }?.name } ?: "다른 사람", pickedOther != null) {
                    onNeedDrivers()
                    picking = true
                }
            }
        }
        if (driverId != null && driverId != me.driverId) {
            Text("다른 사람을 고르면 그 사람이 확인할 때까지 잠정으로 남아요", style = MType.Sub)
        }
        Field("용도") { Segmented(PURPOSE_OPTIONS, purpose, { purpose = it }) }
        BlockButton("저장", onClick = { driverId?.let { onSave(trip, TripChange(driverId = it, purpose = purpose)) } }, enabled = driverId != null && !busy, minHeight = 48.dp)
    }
    if (picking) {
        DriverPickerDialog(drivers.filter { it.id != me.driverId }, onPick = { driverId = it.id; picking = false }, onDismiss = { picking = false })
    }
}

/** A choice button: chosen = accent outline (primary), others secondary. */
@Composable
private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) =
    MButton(label, onClick, kind = if (chosen) ButtonKind.Primary else ButtonKind.Secondary)

/** "이서연 님 · 개인 용도로 잠정 기록했습니다" with [맞습니다] / [수정]. */
@Composable
private fun ProvisionalCard(trip: PhoneTrip, busy: Boolean, text: TripText, onConfirm: () -> Unit, onEdit: () -> Unit) {
    MCard {
        CardHead(trip, text)
        Text(text.provisionalLine(trip), style = MType.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("맞습니다", onConfirm, Modifier.weight(1f), enabled = !busy)
            MButton("수정", onEdit, Modifier.weight(1f), kind = ButtonKind.Secondary, enabled = !busy)
        }
    }
}

// ---------------------------------------------------------------- driver list (M2 "다른 사람", M3 "변경")

@Composable
fun DriverPickerDialog(drivers: List<Driver>, onPick: (Driver) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(MDimens.RadiusLg)).background(MColors.Surface).padding(vertical = 18.dp),
        ) {
            Text("운전자 선택", style = MType.Kicker, modifier = Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(8.dp))
            if (drivers.isEmpty()) {
                Text("직원 목록을 불러오는 중", style = MType.Sub, modifier = Modifier.padding(18.dp))
            }
            drivers.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(d) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.name, style = MType.RowTitle, modifier = Modifier.weight(1f))
                    d.department?.let { Text(it, style = MType.Sub) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "취소",
                fontSize = 15.sp,
                color = MColors.Accent,
                modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(MDimens.RadiusMd)).clickable(onClick = onDismiss).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}
