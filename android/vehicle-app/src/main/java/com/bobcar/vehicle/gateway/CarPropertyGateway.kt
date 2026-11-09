package com.bobcar.vehicle.gateway

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.bobcar.vehicle.signal.Gear
import com.bobcar.vehicle.signal.SignalConfig
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals

/**
 * The only entry point to Android CarPropertyManager.
 * Gear: subscribes to GEAR_SELECTION and publishes a gear only after it stays [SignalConfig.GEAR_HOLD_MS]
 * (the emulator passes through R on P→D, B1). Battery capacity: INFO_EV_BATTERY_CAPACITY read once.
 */
class CarPropertyGateway(context: Context) {

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    @Volatile
    private var signals: VehicleSignals? = null

    // --- state below is touched only on the signal thread ---
    private var stableGear: Gear? = null
    private var candidateGear: Gear? = null
    private val gearHoldCheck = Runnable { onGearHeld() }

    fun connect(): Boolean {
        if (propertyManager != null) return true
        return try {
            val connected = Car.createCar(appContext)
                ?: run {
                    Log.e(TAG, "Car.createCar returned null")
                    return false
                }
            car = connected
            propertyManager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            Log.i(TAG, "Car connect OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Car connect failed", e)
            false
        }
    }

    // ---------------------------------------------------------------- subscriptions

    fun startSignals(target: VehicleSignals) {
        val manager = propertyManager ?: return
        if (signals != null) return
        signals = target
        readBatteryCapacity(manager, target)
        try {
            // subscribePropertyEvents() exists only from API 35; the emulator is API 34.
            @Suppress("DEPRECATION")
            val ok = manager.registerCallback(
                gearCallback,
                VehiclePropertyIds.GEAR_SELECTION,
                CarPropertyManager.SENSOR_RATE_ONCHANGE,
            )
            Log.i(TAG, "GEAR_SELECTION registerCallback = $ok")
        } catch (e: Exception) {
            Log.e(TAG, "GEAR_SELECTION registerCallback failed", e)
        }
    }

    fun stopSignals() {
        val s = signals ?: return
        try {
            @Suppress("DEPRECATION")
            propertyManager?.unregisterCallback(gearCallback)
            Log.i(TAG, "GEAR_SELECTION unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "GEAR_SELECTION unregister failed", e)
        }
        s.handler.removeCallbacks(gearHoldCheck)
        signals = null
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
            // getPropertyStatus() replaces this only from API 35; the emulator is API 34.
            @Suppress("DEPRECATION")
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            val raw = value.value as? Int ?: return
            val s = signals ?: return
            if (s.fakeMode) return
            s.post { onGearRaw(raw) }
        }

        override fun onErrorEvent(propId: Int, areaId: Int) {
            Log.w(TAG, "property error propId=$propId areaId=$areaId")
        }
    }

    /** Debug only: feeds a raw VehicleGear value through the same 1-second hold as real values. */
    fun injectGear(raw: Int) {
        signals?.post { onGearRaw(raw) }
    }

    // ---------------------------------------------------------------- clean-up (signal thread)

    private fun onGearRaw(raw: Int) {
        val s = signals ?: return
        val gear = toGear(raw) ?: return // GEAR_UNKNOWN is ignored, the last gear stays
        s.handler.removeCallbacks(gearHoldCheck)
        if (gear == stableGear) {
            candidateGear = null
            return
        }
        candidateGear = gear
        Log.d(TAG, "gear raw ${VehicleGear.toString(raw)}, waiting ${SignalConfig.GEAR_HOLD_MS} ms")
        s.handler.postDelayed(gearHoldCheck, SignalConfig.GEAR_HOLD_MS)
    }

    private fun onGearHeld() {
        val s = signals ?: return
        val gear = candidateGear ?: return
        candidateGear = null
        val previous = stableGear
        stableGear = gear
        s.publish(
            "gear $gear",
            { it.copy(gear = gear) },
            if (previous == null) null else SignalEvent.GearChanged(previous, gear, s.now()),
            initial = previous == null,
        )
    }

    private fun toGear(raw: Int): Gear? = when (raw) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> null
        else -> Gear.DRIVE // GEAR_DRIVE and manual GEAR_1..9 are forward gears
    }

    private fun readBatteryCapacity(manager: CarPropertyManager, s: VehicleSignals) {
        try {
            val wh = manager.getFloatProperty(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, GLOBAL_AREA)
            s.post {
                if (wh > 0f) {
                    s.publish("batteryCapacity Wh=$wh", { it.copy(batteryCapacityWh = wh) }, null, initial = true)
                } else {
                    Log.w(TAG, "INFO_EV_BATTERY_CAPACITY = $wh, ignored")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "INFO_EV_BATTERY_CAPACITY read failed", e)
        }
    }

    fun release() {
        stopSignals()
        car?.let {
            try {
                it.disconnect()
                Log.i(TAG, "Car disconnect OK")
            } catch (e: Exception) {
                Log.e(TAG, "Car disconnect failed", e)
            }
        }
        car = null
        propertyManager = null
    }

    private companion object {
        const val TAG = "BOBCar.CarProperty"
        const val GLOBAL_AREA = 0
    }
}
