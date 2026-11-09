package com.bobcar.vehicle.gateway

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log

/**
 * The only entry point to the Pleos Vehicle SDK.
 * Signal reads and value clean-up (seat occupancy inversion, repeated values) are added here later.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            Log.i(TAG, "Vehicle initialize OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle initialize failed", e)
            false
        }
    }

    fun release() {
        if (!initialized) return
        try {
            vehicle.release()
            Log.i(TAG, "Vehicle release OK")
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle release failed", e)
        } finally {
            initialized = false
        }
    }

    private companion object {
        const val TAG = "BOBCar.Vehicle"
    }
}
