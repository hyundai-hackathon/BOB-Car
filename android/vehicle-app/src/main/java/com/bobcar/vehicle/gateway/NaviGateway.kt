package com.bobcar.vehicle.gateway

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.bobcar.vehicle.signal.RouteStart
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals

/**
 * The only entry point to the Pleos NaviHelper SDK.
 * Publishes guidance start (expected toll fare: only RouteStartInfo.tollFare is valid, day1-check 14)
 * to [VehicleSignals]. Place names are masked in logs (personal data).
 */
class NaviGateway(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)

    @Volatile
    private var initialized = false

    @Volatile
    private var signals: VehicleSignals? = null

    fun startSignals(target: VehicleSignals) {
        signals = target
    }

    fun stopSignals() {
        signals = null
    }

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(
                TAG,
                "onRouteStarted tollFare=${routeStartInfo.tollFare} distance=${routeStartInfo.distance} " +
                    "duration=${routeStartInfo.duration} name=${mask(routeStartInfo.name)}"
            )
            val s = signals ?: return
            s.post {
                val route = RouteStart(
                    tollFare = routeStartInfo.tollFare,
                    distanceMeters = routeStartInfo.distance,
                    destinationName = routeStartInfo.name,
                    at = s.now(),
                )
                s.publish(
                    "routeStarted tollFare=${route.tollFare} name=${mask(route.destinationName)}",
                    { it.copy(lastRouteStart = route) },
                    SignalEvent.RouteStarted(route),
                )
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(
                TAG,
                "onDestinationInfo name=${mask(destinationInfo.name)} " +
                    "remainDistance=${destinationInfo.remainDistance} tollFare=${destinationInfo.tollFare}"
            )
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError $errorCode")
        }
    }

    fun initialize() {
        if (initialized) return
        try {
            naviHelper.initialize(
                {
                    initialized = true
                    Log.i(TAG, "NaviHelper initialize OK")
                    try {
                        naviHelper.addListener(listener)
                        naviHelper.getDestinationInfo()
                        Log.i(TAG, "getDestinationInfo requested")
                    } catch (e: Exception) {
                        Log.e(TAG, "NaviHelper listener/getDestinationInfo failed", e)
                    }
                },
                { t -> Log.e(TAG, "NaviHelper initialize failed", t) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper initialize threw", e)
        }
    }

    fun release() {
        stopSignals()
        if (!initialized) return
        try {
            naviHelper.removeListener(listener)
            naviHelper.release()
            Log.i(TAG, "NaviHelper release OK")
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper release failed", e)
        } finally {
            initialized = false
        }
    }

    private fun mask(text: String): String =
        if (text.isEmpty()) "(empty)" else text.take(2) + "***(${text.length})"

    private companion object {
        const val TAG = "BOBCar.Navi"
    }
}
