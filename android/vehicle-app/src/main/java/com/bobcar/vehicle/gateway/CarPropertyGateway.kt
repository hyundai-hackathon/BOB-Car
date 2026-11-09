package com.bobcar.vehicle.gateway

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * The only entry point to Android CarPropertyManager.
 * Gear subscription with the 1-second hold filter is added here later.
 */
class CarPropertyGateway(context: Context) {

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

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

    /** Reads GEAR_SELECTION once. Returns a VehicleGear constant, or null when it cannot be read. */
    fun readGearOnce(): Int? {
        val manager = propertyManager ?: run {
            Log.w(TAG, "readGearOnce before connect")
            return null
        }
        return try {
            val gear = manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, GLOBAL_AREA)
            Log.i(TAG, "GEAR_SELECTION = $gear (${VehicleGear.toString(gear)})")
            gear
        } catch (e: Exception) {
            Log.e(TAG, "GEAR_SELECTION read failed", e)
            null
        }
    }

    fun release() {
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
