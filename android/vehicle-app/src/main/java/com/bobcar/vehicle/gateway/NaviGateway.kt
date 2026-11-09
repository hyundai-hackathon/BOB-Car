package com.bobcar.vehicle.gateway

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/**
 * The only entry point to the Pleos NaviHelper SDK.
 * Destination and toll fare are kept here later; for now events are only logged.
 * Place names are masked in logs (personal data).
 */
class NaviGateway(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)

    @Volatile
    private var initialized = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(
                TAG,
                "onRouteStarted tollFare=${routeStartInfo.tollFare} distance=${routeStartInfo.distance} " +
                    "duration=${routeStartInfo.duration} name=${mask(routeStartInfo.name)}"
            )
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
