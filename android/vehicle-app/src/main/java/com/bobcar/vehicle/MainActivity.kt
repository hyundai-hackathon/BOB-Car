package com.bobcar.vehicle

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.service.CollectionService
import com.bobcar.vehicle.service.VehicleRuntime
import com.bobcar.vehicle.ui.DiagnosticScreen
import com.bobcar.vehicle.ui.TripScreen
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
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (showsTripScreen(state)) {
                    TripScreen(state, activeTrip, snapshot.odometerKm)
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
