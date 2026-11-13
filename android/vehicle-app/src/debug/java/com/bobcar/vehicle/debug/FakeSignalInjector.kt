package com.bobcar.vehicle.debug

import android.car.VehicleGear
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.bobcar.vehicle.gateway.CarPropertyGateway
import com.bobcar.vehicle.gateway.VehicleGateway
import com.bobcar.vehicle.signal.RouteStart
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals
import com.bobcar.vehicle.util.maskForLog

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
 *   route    <tollFare>:<destination>   navigation guidance started (no spaces; e.g. 4700:Incheon-Airport)
 *   continue (no value)       [계속 운행]
 *   driver   <id>             driver card answered with this driver (e.g. drv-002)
 *   reservation <id> | none   mock reservation covering now: this driver for today, or none
 *   consent-reset <id>        forget this driver's consent answer (the consent card shows again)
 */
object FakeSignalInjector {

    private const val TAG = "BOBCar.Fake"
    const val ACTION = "com.bobcar.vehicle.debug.SIGNAL"

    fun register(
        context: Context,
        signals: VehicleSignals,
        vehicle: VehicleGateway,
        carProperty: CarPropertyGateway,
        hooks: FakeHooks,
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
                    "route" -> route(signals, value)
                    "continue" -> true.also { hooks.continueTapped() }
                    "driver" -> value?.takeIf { it.isNotBlank() }?.let { hooks.confirmDriver(it) } != null
                    "reservation" -> when {
                        value.isNullOrBlank() -> false
                        value == "none" -> true.also { hooks.setReservation(null) }
                        else -> true.also { hooks.setReservation(value) }
                    }
                    "consent-reset" -> value?.takeIf { it.isNotBlank() }?.let { hooks.resetConsent(it) } != null
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

    /** Publishes a RouteStarted the way NaviGateway does. Navigation is not muted by fake mode. */
    private fun route(signals: VehicleSignals, value: String?): Boolean {
        val parts = value?.split(':', limit = 2) ?: return false
        val fare = parts[0].toIntOrNull() ?: return false
        val name = parts.getOrElse(1) { "" }
        signals.post {
            val route = RouteStart(tollFare = fare, distanceMeters = 0, destinationName = name, at = signals.now())
            signals.publish(
                "routeStarted (fake) tollFare=$fare name=${maskForLog(name)}",
                { it.copy(lastRouteStart = route) },
                SignalEvent.RouteStarted(route),
            )
        }
        return true
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
