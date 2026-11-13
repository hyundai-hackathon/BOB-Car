package com.bobcar.vehicle.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobcar.vehicle.data.TripEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear

/**
 * Waiting screen while a trip runs. Display only: no buttons or input of any kind, so it is safe
 * while driving (CLAUDE.md rule 3). The end countdown in P shows [TripEndCard] instead.
 */
@Composable
fun TripScreen(state: DrivingState, trip: TripEntity?, odometerKm: Float?) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("운행 중", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            if (trip == null) {
                Text("운행 기록 준비 중", style = MaterialTheme.typography.titleLarge)
                return@Column
            }
            val soFar = trip.startOdometerKm?.let { start -> odometerKm?.let { (it - start).takeIf { d -> d >= 0f } } }
            Item("출발", "${TripFormat.clock(trip.startedAt)} · ${TripFormat.km(trip.startOdometerKm)}")
            Item("주행 거리", TripFormat.km(soFar))
            Item("목적지", TripFormat.destination(trip.destinationName))
            Item("예상 통행료", TripFormat.toll(trip.expectedTollFare))
        }
    }
}

@Composable
private fun Item(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.width(200.dp), style = MaterialTheme.typography.headlineSmall)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    }
}

/** Trip screen while driving (including P after [계속 운행]); the end countdown has its own card. */
fun showsTripScreen(state: DrivingState): Boolean = state is DrivingState.Driving

@Preview(widthDp = 1280, heightDp = 720)
@Composable
private fun TripScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        TripScreen(
            state = DrivingState.Driving(0, Gear.DRIVE),
            trip = TripEntity(
                status = TripEntity.STATUS_ACTIVE,
                startedAt = 1_731_200_000_000,
                startOdometerKm = 12_340f,
                destinationName = "인천국제공항 제1여객터미널",
                expectedTollFare = 4_700,
            ),
            odometerKm = 12_352f,
        )
    }
}
