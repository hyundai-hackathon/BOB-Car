package com.bobcar.vehicle.gateway

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.bobcar.vehicle.signal.CommutePlaces
import com.bobcar.vehicle.signal.NaviPlace
import com.bobcar.vehicle.signal.RouteStart
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals
import com.bobcar.vehicle.util.maskForLog

/**
 * The only entry point to the Pleos NaviHelper SDK. Publishes to [VehicleSignals]:
 * - guidance start (expected toll fare: only RouteStartInfo.tollFare is valid, day1-check 14)
 * - the destination's details (poiId, coordinates), asked for on every guidance start
 * - bookmarks HOME / OFFICE (no change event, so read on start and on every trip start: [refreshBookmarks])
 * Place names are masked in logs and coordinates are never logged (personal data).
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
                    "duration=${routeStartInfo.duration} name=${maskForLog(routeStartInfo.name)}"
            )
            // RouteStartInfo has the name only; the poiId and coordinates come with onDestinationInfo
            requestDestination()
            val s = signals ?: return
            s.post {
                val route = RouteStart(
                    tollFare = routeStartInfo.tollFare,
                    distanceMeters = routeStartInfo.distance,
                    destinationName = routeStartInfo.name,
                    at = s.now(),
                )
                s.publish(
                    "routeStarted tollFare=${route.tollFare} name=${maskForLog(route.destinationName)}",
                    { it.copy(lastRouteStart = route) },
                    SignalEvent.RouteStarted(route),
                )
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(
                TAG,
                "onDestinationInfo name=${maskForLog(destinationInfo.name)} " +
                    "remainDistance=${destinationInfo.remainDistance} tollFare=${destinationInfo.tollFare}"
            )
            // Empty while there is no guidance (FREE_DRIVE): keep the last destination, the trip keeps it too
            val place = naviPlace(
                destinationInfo.name, destinationInfo.poiId, destinationInfo.latitude, destinationInfo.longitude,
            ) ?: return
            val s = signals ?: return
            s.post {
                s.publish(
                    "destinationPlace name=${maskForLog(place.name)} poi=${place.poiId != null} coords=${place.latitude != null}",
                    { it.copy(destinationPlace = place) },
                    null,
                )
            }
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            // HOME / OFFICE entries come even when empty (no name) until the driver sets them
            fun placeOf(type: BookmarkType) = bookmarkInfoList
                .filter { it.category == type }
                .firstNotNullOfOrNull { naviPlace(it.name, it.poiId, it.latitude, it.longitude) }
            val commute = CommutePlaces(home = placeOf(BookmarkType.HOME), office = placeOf(BookmarkType.OFFICE))
            val summary = "home=${commute.home != null} office=${commute.office != null}"
            Log.i(TAG, "onBookmarkInfo total=${bookmarkInfoList.size} $summary")
            val s = signals ?: return
            s.post {
                s.publish("commutePlaces $summary", { it.copy(commutePlaces = commute) }, null)
            }
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
                        naviHelper.getBookmarkInfo()
                        Log.i(TAG, "getDestinationInfo, getBookmarkInfo requested")
                    } catch (e: Exception) {
                        Log.e(TAG, "NaviHelper listener/first requests failed", e)
                    }
                },
                { t -> Log.e(TAG, "NaviHelper initialize failed", t) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper initialize threw", e)
        }
    }

    /** Bookmarks may have changed since the last read (no change event). Called on every trip start. */
    fun refreshBookmarks() {
        if (!initialized) return
        try {
            naviHelper.getBookmarkInfo()
        } catch (e: Exception) {
            Log.e(TAG, "getBookmarkInfo failed", e)
        }
    }

    private fun requestDestination() {
        if (!initialized) return
        try {
            naviHelper.getDestinationInfo()
        } catch (e: Exception) {
            Log.e(TAG, "getDestinationInfo failed", e)
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

    private companion object {
        const val TAG = "BOBCar.Navi"
    }
}

/** null for an empty entry (no name). Coordinates 0, 0 mean "not set". */
internal fun naviPlace(name: String?, poiId: String?, latitude: Double, longitude: Double): NaviPlace? {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val hasCoords = !(latitude == 0.0 && longitude == 0.0)
    return NaviPlace(
        name = trimmed,
        poiId = poiId?.trim()?.takeIf { it.isNotEmpty() },
        latitude = latitude.takeIf { hasCoords },
        longitude = longitude.takeIf { hasCoords },
    )
}
