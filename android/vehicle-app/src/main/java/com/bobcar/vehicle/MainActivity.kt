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
import com.bobcar.vehicle.data.DrivingRecorder
import com.bobcar.vehicle.debug.FakeSignalInjector
import com.bobcar.vehicle.driving.DrivingMonitor
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.NaviGateway
import com.bobcar.vehicle.gateway.VehicleGateway
import com.bobcar.vehicle.signal.VehicleSignals
import com.bobcar.vehicle.ui.DiagnosticScreen

class MainActivity : ComponentActivity() {

    // Temporary owner until the foreground collection service exists; everything moves there later.
    private lateinit var signals: VehicleSignals
    private lateinit var vehicleGateway: VehicleGateway
    private lateinit var carPropertyGateway: CarPropertyGateway
    private lateinit var naviGateway: NaviGateway
    private lateinit var drivingMonitor: DrivingMonitor
    private lateinit var recorder: DrivingRecorder
    private lateinit var unregisterFakeSignals: () -> Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity created (version ${BuildConfig.VERSION_NAME})")

        val db = BobCarDatabase.get(this)
        recorder = DrivingRecorder(db)
        signals = VehicleSignals()
        drivingMonitor = DrivingMonitor(signals).also {
            it.addEventListener(recorder::record)
            it.start()
        }
        vehicleGateway = VehicleGateway(this).also {
            if (it.initialize()) it.startSignals(signals)
        }
        carPropertyGateway = CarPropertyGateway(this).also {
            if (it.connect()) it.startSignals(signals)
        }
        naviGateway = NaviGateway(this).also {
            it.startSignals(signals)
            it.initialize()
        }
        unregisterFakeSignals = FakeSignalInjector.register(
            this, signals, vehicleGateway, carPropertyGateway, drivingMonitor,
        )

        val storedEventCountFlow = db.vehicleEventDao().countFlow()
        val pendingUploadCountFlow = db.uploadQueueDao().pendingCountFlow()
        setContent {
            val snapshot by signals.snapshot.collectAsState()
            val state by drivingMonitor.state.collectAsState()
            val recentEvents by drivingMonitor.recentEvents.collectAsState()
            val fakeMode by signals.fakeModeFlow.collectAsState()
            val storedEventCount by storedEventCountFlow.collectAsState(initial = 0)
            val pendingUploadCount by pendingUploadCountFlow.collectAsState(initial = 0)
            MaterialTheme(colorScheme = darkColorScheme()) {
                DiagnosticScreen(snapshot, state, recentEvents, fakeMode, storedEventCount, pendingUploadCount)
            }
        }
    }

    override fun onDestroy() {
        unregisterFakeSignals()
        drivingMonitor.stop()
        naviGateway.release()
        carPropertyGateway.release()
        vehicleGateway.release()
        signals.close()
        recorder.close()
        Log.i(TAG, "MainActivity destroyed")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "BOBCar"
    }
}
