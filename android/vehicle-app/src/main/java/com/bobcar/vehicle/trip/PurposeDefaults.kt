package com.bobcar.vehicle.trip

import com.bobcar.vehicle.signal.NaviPlace
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trip purpose in the logbook. The tax logbook form (국세청 업무용승용차 운행기록부) splits business
 * distance into 출·퇴근용 ([COMMUTE]) and 일반 업무용 ([BUSINESS]). Stored by name.
 */
enum class Purpose { BUSINESS, COMMUTE, PERSONAL }

/** Why a purpose was suggested. */
enum class PurposeSource {
    /** Going to the navigation bookmark HOME. */
    HOME,

    /** Going to the bookmark OFFICE right after a commute trip (the car was taken home): going to work. */
    OFFICE_AFTER_COMMUTE,

    /** Going to the bookmark OFFICE otherwise: coming back from business. */
    OFFICE,

    /** Any other destination, or none. */
    DEFAULT,
}

data class DefaultPurpose(val purpose: Purpose, val source: PurposeSource)

/**
 * The purpose suggested on the trip-end card. The only place this rule lives (design-review F6):
 * 1. destination is the navigation bookmark HOME → COMMUTE
 * 2. destination is the bookmark OFFICE → COMMUTE when the previous trip was a commute, else BUSINESS
 * 3. anything else, or no destination → BUSINESS (a company car)
 * PERSONAL is never suggested: only the driver picks it.
 */
object PurposeDefaults {

    /** Two places closer than this count as the same place. */
    const val SAME_PLACE_METERS = 100.0

    fun resolve(
        destination: NaviPlace?,
        home: NaviPlace?,
        office: NaviPlace?,
        lastPurpose: Purpose?,
    ): DefaultPurpose = when {
        destination != null && home != null && samePlace(destination, home) ->
            DefaultPurpose(Purpose.COMMUTE, PurposeSource.HOME)
        destination != null && office != null && samePlace(destination, office) ->
            if (lastPurpose == Purpose.COMMUTE) DefaultPurpose(Purpose.COMMUTE, PurposeSource.OFFICE_AFTER_COMMUTE)
            else DefaultPurpose(Purpose.BUSINESS, PurposeSource.OFFICE)
        else -> DefaultPurpose(Purpose.BUSINESS, PurposeSource.DEFAULT)
    }

    /** Same poiId, or within [SAME_PLACE_METERS], or the same name ignoring spaces and case. */
    fun samePlace(a: NaviPlace, b: NaviPlace): Boolean {
        if (a.poiId != null && a.poiId == b.poiId) return true
        val near = distanceMeters(a, b)?.let { it <= SAME_PLACE_METERS } ?: false
        return near || normalize(a.name) == normalize(b.name)
    }

    /** Great-circle distance, null when either place has no coordinates. */
    fun distanceMeters(a: NaviPlace, b: NaviPlace): Double? {
        val lat1 = a.latitude ?: return null
        val lon1 = a.longitude ?: return null
        val lat2 = b.latitude ?: return null
        val lon2 = b.longitude ?: return null
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }

    private fun normalize(name: String): String = name.filterNot { it.isWhitespace() }.lowercase()

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
