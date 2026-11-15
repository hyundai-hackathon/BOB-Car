package com.bobcar.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobcar.mobile.R
import com.bobcar.mobile.data.Driver
import com.bobcar.mobile.data.Me
import com.bobcar.mobile.data.PhoneTrip
import com.bobcar.mobile.data.Purpose
import com.bobcar.mobile.data.TripChange
import com.bobcar.mobile.data.VehicleStatus

/*
 * M3 운행 상세 · 수정 and M4 차량 (Figma Mobile M3, M4).
 */

private const val MEMO_MAX = 200

// ---------------------------------------------------------------- M3

/**
 * One trip: distance, time, toll, odometer, driver [변경], purpose, memo, [저장].
 * Saving also confirms the driver and the purpose (this screen is the driver saying "this is right").
 */
@Composable
fun TripDetailScreen(
    trip: PhoneTrip,
    me: Me,
    drivers: List<Driver>,
    busy: Boolean,
    backLabel: String,
    text: TripText,
    onBack: () -> Unit,
    onSave: (TripChange) -> Unit,
    onNeedDrivers: () -> Unit,
) {
    var purpose by rememberSaveable(trip.tripDocId) { mutableStateOf(trip.purpose ?: Purpose.BUSINESS) }
    var memo by rememberSaveable(trip.tripDocId) { mutableStateOf(trip.memo ?: "") }
    var newDriver by remember(trip.tripDocId) { mutableStateOf<Driver?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        MButton(backLabel, onBack, Modifier.padding(start = 12.dp, top = 4.dp), kind = ButtonKind.Ghost, icon = R.drawable.ic_ph_arrow_left)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text.detailDate(trip), style = MType.Sub)
                Text(if (purpose == Purpose.PERSONAL) "개인 운행" else text.title(trip), style = MType.H2)
            }
            Row(Modifier.fillMaxWidth().padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat("주행 거리", text.km(trip.distanceKm), Modifier.weight(1f))
                Stat("시간", text.duration(trip), Modifier.weight(1f))
                Stat("통행료", text.won(trip.expectedTollFare), Modifier.weight(1f))
            }
            Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 32.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Field("누적 거리") { Text("${text.odometer(trip.startKm)} → ${text.odometer(trip.endKm)}", style = MType.Body) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Field("운전자", Modifier.weight(1f)) {
                        Text(newDriver?.let { "${it.name} · 확인 요청 예정" } ?: text.driverLine(trip), style = MType.Body)
                    }
                    MButton("변경", { onNeedDrivers(); picking = true }, kind = ButtonKind.Secondary, enabled = !busy)
                }
                if (newDriver != null && newDriver?.id != me.driverId) {
                    Text("저장하면 ${newDriver?.name} 님이 확인할 때까지 잠정으로 남고, 내 기록에서 빠져요", style = MType.Sub)
                }
                Field("용도") {
                    Segmented(PURPOSE_OPTIONS, purpose, { purpose = it })
                    if (purpose == Purpose.PERSONAL) Text("개인 운행은 목적지를 저장하지 않아요", style = MType.Sub, modifier = Modifier.padding(top = 4.dp))
                }
                Field("메모") {
                    MemoInput(memo, onChange = { memo = it.take(MEMO_MAX) }, placeholder = "방문 목적, 동승자 등")
                }
            }
        }
        Box(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 16.dp, bottom = 28.dp)) {
            BlockButton("저장", enabled = !busy, onClick = {
                onSave(
                    TripChange(
                        driverId = newDriver?.id?.takeIf { it != trip.driverId },
                        purpose = purpose,
                        memo = memo.trim(),
                        confirm = newDriver == null || newDriver?.id == me.driverId,
                    ),
                )
            })
        }
    }
    if (picking) {
        DriverPickerDialog(drivers.filter { it.id != trip.driverId }, onPick = { newDriver = it; picking = false }, onDismiss = { picking = false })
    }
}

/** .input: surface, divider border, 8 dp corners, 48 dp tall, 15 sp. */
@Composable
private fun MemoInput(value: String, onChange: (String) -> Unit, placeholder: String) {
    val shape = RoundedCornerShape(MDimens.RadiusMd)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = MType.Body.copy(fontSize = 15.sp),
        cursorBrush = SolidColor(MColors.Accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(MColors.Surface).border(1.dp, MColors.Divider, shape),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 13.dp)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 15.sp, color = MColors.Neutral400)
                inner()
            }
        },
    )
}

// ---------------------------------------------------------------- M4

@Composable
fun VehicleScreen(vehicle: VehicleStatus?, text: TripText, onRefresh: () -> Unit) {
    if (vehicle == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(sub = null, title = "차량", trailing = { RefreshButton(onRefresh) })
            Empty(R.drawable.ic_ph_car_profile, "차량 정보를 불러오지 못했어요", "잠시 뒤 다시 시도해 주세요")
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            sub = listOfNotNull(vehicle.plate, if (vehicle.driving) "운행 중" else "주차 중").joinToString(" · "),
            title = vehicle.model ?: vehicle.name ?: vehicle.vehicleId,
            trailing = { RefreshButton(onRefresh) },
        )
        Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("배터리", style = MType.Label)
            Text(
                buildAnnotatedString {
                    append(vehicle.socPercent?.toString() ?: "—")
                    withStyle(SpanStyle(fontSize = 24.sp, color = MColors.Neutral400)) { append(" %") }
                },
                fontSize = 64.sp,
                lineHeight = 64.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = MColors.Text,
            )
            BatteryBar(vehicle.socPercent)
            Text(
                vehicle.lastCharge?.let { text.lastCharge(it.at, it.fromPercent, it.toPercent) } ?: "충전 기록 없음",
                style = MType.Sub,
            )
        }
        Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            TwoColumns {
                Stat("누적 거리", text.odometer(vehicle.odometerKm), Modifier.weight(1f))
                Stat("이번 달 주행", text.km(vehicle.monthKm), Modifier.weight(1f))
            }
            TwoColumns {
                Stat("업무 사용 비율", vehicle.businessRatioPercent?.let { "${it.toInt()} %" } ?: "—", Modifier.weight(1f))
                Stat("직전 운전자", vehicle.lastDriverName ?: "—", Modifier.weight(1f))
            }
        }
        Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("차량 앱 연결", style = MType.Section)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val at = vehicle.lastUploadAt
                PhIcon(if (at != null) R.drawable.ic_ph_check_circle else R.drawable.ic_ph_cloud_slash, if (at != null) MColors.Accent else MColors.Neutral400, size = 20.dp)
                Text(at?.let(text::lastUpload) ?: "아직 받은 기록이 없어요", fontSize = 15.sp, color = MColors.Text)
            }
        }
    }
}

/** 4 dp track; the filled part glows in accent. */
@Composable
private fun BatteryBar(percent: Int?) {
    val fraction = ((percent ?: 0).coerceIn(0, 100)) / 100f
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MColors.Neutral800)) {
        Box(
            Modifier.fillMaxWidth(fraction).height(4.dp)
                .shadow(8.dp, RoundedCornerShape(2.dp), ambientColor = MColors.Accent, spotColor = MColors.Accent)
                .background(MColors.Accent, RoundedCornerShape(2.dp)),
        )
    }
}
