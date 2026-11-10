package com.bobcar.vehicle.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobcar.vehicle.R
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.data.distanceKm
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear
import com.bobcar.vehicle.signal.VehicleSnapshot
import com.bobcar.vehicle.upload.UploadStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only diagnostic screen: the six signals, the driving state and recent driving events.
 * No input, so it is safe to show while driving.
 */
@Composable
fun DiagnosticScreen(
    snapshot: VehicleSnapshot,
    state: DrivingState,
    recentEvents: List<String>,
    fakeMode: Boolean,
    storedEventCount: Int,
    pendingUploadCount: Int,
    upload: UploadStatus.State,
    lastTrip: TripEntity?,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            if (fakeMode) {
                Text("가짜 신호 모드", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(modifier = Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("신호", style = MaterialTheme.typography.titleLarge)
                    SignalRow("운전석", snapshot.driverSeatOccupied?.let { if (it) "앉음" else "비어 있음" })
                    SignalRow("기어", snapshot.gear?.let(::gearLabel))
                    SignalRow("브레이크", snapshot.brakePressed?.let { if (it) "밟음" else "뗌" })
                    SignalRow("운전석 문", snapshot.driverDoorOpen?.let { if (it) "열림" else "닫힘" })
                    SignalRow("누적 거리", snapshot.odometerKm?.let { "%,.1f km".format(it) })
                    SignalRow("배터리", snapshot.socPercent?.let { soc ->
                        val kwh = snapshot.batteryEnergyWh?.let { " (%.1f kWh)".format(it / 1000f) } ?: ""
                        "$soc%$kwh"
                    })
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("운행 상태", style = MaterialTheme.typography.titleLarge)
                    Text(stateLabel(state), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Text("기록", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "저장된 이벤트 ${storedEventCount}건 · 미전송 ${pendingUploadCount}건",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(uploadLabel(upload), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("마지막 운행", style = MaterialTheme.typography.titleMedium)
                    Text(lastTripLabel(lastTrip), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Text("최근 이벤트", style = MaterialTheme.typography.titleMedium)
                    recentEvents.forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalRow(label: String, value: String?) {
    Row {
        Text(label, modifier = Modifier.width(140.dp), style = MaterialTheme.typography.bodyLarge)
        Text(value ?: "—", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun stateLabel(state: DrivingState): String = when (state) {
    DrivingState.Idle -> "대기"
    is DrivingState.CheckingIn -> if (state.checkinClosed) "체크인 마감" else "체크인 중"
    is DrivingState.Driving -> if (state.gear == Gear.PARK) "운행 중 (P, 계속 운행)" else "운행 중"
    is DrivingState.EndCountdown -> "운행 종료까지 ${remainingSeconds(state.deadline)}초"
    is DrivingState.Parked -> "운행 종료 (운전석 탑승 중)"
}

/** Remaining whole seconds until [deadline] (elapsedRealtime), refreshed on screen. */
@Composable
internal fun remainingSeconds(deadline: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return ((deadline - now).coerceAtLeast(0) + 999) / 1000
}

private fun lastTripLabel(trip: TripEntity?): String {
    if (trip == null) return "없음"
    val time = "${TripFormat.clock(trip.startedAt)}–${trip.endedAt?.let(TripFormat::clock) ?: ""}"
    val odo = "${TripFormat.km(trip.startOdometerKm)} → ${TripFormat.km(trip.endOdometerKm)}"
    return "$time · $odo (${TripFormat.km(trip.distanceKm())})\n" +
        "${TripFormat.destination(trip.destinationName)} · 통행료 ${TripFormat.toll(trip.expectedTollFare)} · 종료 ${trip.endReason}"
}

private fun uploadLabel(upload: UploadStatus.State): String {
    if (!upload.serverConfigured) return "서버: 미설정 (기록은 큐에 보관)"
    val success = upload.lastSuccessAt
    val failure = upload.lastFailureAt
    return when {
        failure != null && (success == null || failure > success) ->
            "서버: 전송 실패 ${clock(failure)} (${upload.lastError}) · 다시 시도 예정"
        success != null -> "서버: 마지막 전송 ${clock(success)} (${upload.lastSentCount}건)"
        else -> "서버: 전송 대기"
    }
}

private fun clock(wallMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(wallMillis))

private fun gearLabel(gear: Gear): String = when (gear) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun DiagnosticScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        DiagnosticScreen(
            snapshot = VehicleSnapshot(
                driverSeatOccupied = true, gear = Gear.DRIVE, brakePressed = false, driverDoorOpen = false,
                odometerKm = 12340f, socPercent = 60, batteryCapacityWh = 77400f,
            ),
            state = DrivingState.Driving(0, Gear.DRIVE),
            recentEvents = listOf("10:00:01 Boarded", "10:00:05 CheckinClosed (BRAKE_IN_PARK)", "10:00:08 TripStarted"),
            fakeMode = false,
            storedEventCount = 12,
            pendingUploadCount = 12,
            upload = UploadStatus.State(serverConfigured = false),
            lastTrip = null,
        )
    }
}
