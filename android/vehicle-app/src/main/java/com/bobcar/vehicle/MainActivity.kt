package com.bobcar.vehicle

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bobcar.vehicle.checkin.Briefing
import com.bobcar.vehicle.checkin.showsConsentCard
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.service.CollectionService
import com.bobcar.vehicle.service.VehicleRuntime
import com.bobcar.vehicle.ui.ConsentCard
import com.bobcar.vehicle.ui.DiagnosticScreen
import com.bobcar.vehicle.ui.DriverCard
import com.bobcar.vehicle.ui.TripEndCard
import com.bobcar.vehicle.ui.TripScreen
import com.bobcar.vehicle.ui.showsDriverCard
import com.bobcar.vehicle.ui.showsTripScreen
import com.bobcar.vehicle.upload.UploadStatus

/**
 * Shows what the collection service sees. Collection itself runs in [CollectionService],
 * so closing this screen does not stop it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created (version ${BuildConfig.VERSION_NAME})")
        CollectionService.start(this)

        val signals = VehicleRuntime.signals
        val monitor = VehicleRuntime.monitor
        val db = BobCarDatabase.get(this)
        val storedEventCountFlow = db.vehicleEventDao().countFlow()
        val pendingUploadCountFlow = db.uploadQueueDao().pendingCountFlow()
        val activeTripFlow = db.tripDao().activeFlow()
        val lastTripFlow = db.tripDao().lastEndedFlow()
        val driversFlow = db.driverDao().activeFlow()
        val baselineFlow = db.vehicleBaselineDao().flow()
        val consentsFlow = db.driverConsentDao().allFlow()

        setContent {
            val snapshot by signals.snapshot.collectAsState()
            val state by monitor.state.collectAsState()
            val recentEvents by monitor.recentEvents.collectAsState()
            val fakeMode by signals.fakeModeFlow.collectAsState()
            val storedEventCount by storedEventCountFlow.collectAsState(initial = 0)
            val pendingUploadCount by pendingUploadCountFlow.collectAsState(initial = 0)
            val upload by UploadStatus.state.collectAsState()
            val activeTrip by activeTripFlow.collectAsState(initial = null)
            val lastTrip by lastTripFlow.collectAsState(initial = null)
            val checkin by VehicleRuntime.checkin.state.collectAsState()
            val drivers by driversFlow.collectAsState(initial = emptyList())
            val baseline by baselineFlow.collectAsState(initial = null)
            // null until loaded, so a driver who already agreed never sees the card flash
            val consents by consentsFlow.collectAsState(initial = null)
            val endChoice by VehicleRuntime.tripEnd.state.collectAsState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                val currentCheckin = checkin
                val consentDriverId = currentCheckin?.confirmedDriverId
                val drivingState = state
                if (drivingState is DrivingState.EndCountdown) {
                    // Gear settled in P during a trip: confirm driver and purpose before the trip is saved
                    TripEndCard(
                        state = drivingState,
                        trip = activeTrip,
                        choice = endChoice,
                        drivers = drivers,
                        odometerKm = snapshot.odometerKm,
                        onContinue = VehicleRuntime::continueTrip,
                        onPurpose = VehicleRuntime::choosePurpose,
                        onOpenDrivers = VehicleRuntime::openEndDriverList,
                        onPickDriver = VehicleRuntime::pickEndDriver,
                        onCloseDrivers = VehicleRuntime::closeEndDriverList,
                    )
                } else if (showsTripScreen(state)) {
                    TripScreen(state, activeTrip, snapshot.odometerKm)
                } else if (consentDriverId != null && showsConsentCard(state, currentCheckin, snapshot.gear, consents)) {
                    // Before V5: the confirmed driver answers the consent card first (first check-in only)
                    ConsentCard(
                        driverName = drivers.firstOrNull { it.id == consentDriverId }?.name ?: consentDriverId,
                        onAgree = { VehicleRuntime.answerConsent(consentDriverId, agreed = true) },
                        onLater = { VehicleRuntime.answerConsent(consentDriverId, agreed = false) },
                    )
                } else if (currentCheckin != null && showsDriverCard(state, currentCheckin, snapshot.gear)) {
                    val briefing = Briefing.of(snapshot.socPercent, snapshot.odometerKm, baseline?.lastOdometerKm)
                    DriverCard(currentCheckin, drivers, briefing, onConfirm = VehicleRuntime::confirmDriver)
                } else {
                    DiagnosticScreen(
                        snapshot, state, recentEvents, fakeMode, storedEventCount, pendingUploadCount, upload, lastTrip,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "MainActivity destroyed (collection keeps running)")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BOBCar"
    }
}
