package com.bobcar.vehicle.debug

import android.car.VehicleGear
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.bobcar.vehicle.driving.DrivingMonitor
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.VehicleGateway
import com.bobcar.vehicle.signal.VehicleSignals

/**
 * Debug build only (src/debug). Accepts fake signals from adb and feeds them through the gateways'
 * normal clean-up, for tests and as a demo fallback. The release build has a no-op version.
 *
 * adb shell am broadcast --user 10 -a com.bobcar.vehicle.debug.SIGNAL -p com.bobcar.vehicle --es signal <name> --es value <value>
 *   mode     fake | real      (fake: real SDK values are dropped until mode real)
 *   seat     occupied | empty
 *   brake    on | off
 *   door     open | closed
 *   gear     P | R | N | D
 *   odometer <km>
 *   soc      <percent>
 *   continue (no value)       [계속 운행]
 *   driver   <id>             driver confirmed (for trip merging)
 */
object FakeSignalInjector {

    private const val TAG = "BOBCar.Fake"
    const val ACTION = "com.bobcar.vehicle.debug.SIGNAL"

    fun register(
        context: Context,
        signals: VehicleSignals,
        vehicle: VehicleGateway,
        carProperty: CarPropertyGateway,
        monitor: DrivingMonitor,
    ): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val signal = intent.getStringExtra("signal")
                val value = intent.getStringExtra("value")
                Log.i(TAG, "inject $signal=$value")
                val ok = when (signal) {
                    "mode" -> when (value) {
                        "fake" -> true.also { signals.setFakeMode(true) }
                        "real" -> true.also { signals.setFakeMode(false) }
                        else -> false
                    }
                    "seat" -> bool(value, "occupied", "empty")?.let { vehicle.injectSeat(it) } != null
                    "brake" -> bool(value, "on", "off")?.let { vehicle.injectBrake(it) } != null
                    "door" -> bool(value, "open", "closed")?.let { vehicle.injectDoor(it) } != null
                    "gear" -> gearRaw(value)?.let { carProperty.injectGear(it) } != null
                    "odometer" -> value?.toFloatOrNull()?.let { vehicle.injectOdometer(it) } != null
                    "soc" -> value?.toIntOrNull()?.let { vehicle.injectSoc(it) } != null
                    "continue" -> true.also { monitor.onContinueTapped() }
                    "driver" -> value?.takeIf { it.isNotBlank() }?.let { monitor.onDriverConfirmed(it) } != null
                    else -> false
                }
                if (!ok) Log.w(TAG, "unknown signal/value: $signal=$value")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "fake signal receiver ready ($ACTION)")
        return {
            context.unregisterReceiver(receiver)
            signals.setFakeMode(false)
        }
    }

    private fun bool(value: String?, yes: String, no: String): Boolean? = when (value) {
        yes -> true
        no -> false
        else -> null
    }

    private fun gearRaw(value: String?): Int? = when (value?.uppercase()) {
        "P" -> VehicleGear.GEAR_PARK
        "R" -> VehicleGear.GEAR_REVERSE
        "N" -> VehicleGear.GEAR_NEUTRAL
        "D" -> VehicleGear.GEAR_DRIVE
        else -> null
    }
}
