package com.bobcar.vehicle.gateway

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The only entry point to the Pleos Vehicle SDK.
 * Signal reads and value clean-up (seat occupancy inversion, repeated values) are added here later.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
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

    /**
     * Reads the odometer once. initialize() returns before the Vehicle Service is bound,
     * so a failed attempt is retried a few times.
     */
    fun readDistanceDrivenOnce(onResult: (Float?) -> Unit) {
        readDistanceDriven(attempt = 1, onResult = onResult)
    }

    private fun readDistanceDriven(attempt: Int, onResult: (Float?) -> Unit) {
        if (!initialized) {
            Log.w(TAG, "readDistanceDriven skipped: not initialized")
            return
        }
        try {
            vehicle.getOdometer().getCurrentDistanceDriven(
                { distance ->
                    Log.i(TAG, "DistanceDriven = $distance (attempt $attempt)")
                    onResult(distance)
                },
                { e -> retryOrGiveUp(attempt, e, onResult) },
            )
        } catch (e: Exception) {
            retryOrGiveUp(attempt, e, onResult)
        }
    }

    private fun retryOrGiveUp(attempt: Int, e: Exception, onResult: (Float?) -> Unit) {
        if (attempt < MAX_ATTEMPTS) {
            Log.w(TAG, "DistanceDriven attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            mainHandler.postDelayed({ readDistanceDriven(attempt + 1, onResult) }, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "DistanceDriven failed after $attempt attempts", e)
            onResult(null)
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
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
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 500L
    }
}
