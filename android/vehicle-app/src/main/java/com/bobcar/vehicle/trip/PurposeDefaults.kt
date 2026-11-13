package com.bobcar.vehicle.trip

import com.bobcar.vehicle.signal.NaviPlace
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Trip purpose in the logbook (국세청 운행기록부 업무 구분). Stored by name. */
enum class Purpose { BUSINESS, COMMUTE, PERSONAL }

/** Why a purpose was suggested. */
enum class PurposeSource { CUSTOMER, COMMUTE_BOOKMARK, LAST_PURPOSE, FALLBACK }

data class DefaultPurpose(val purpose: Purpose, val source: PurposeSource)

/**
 * The purpose suggested on the trip-end card. The only place this rule lives (design-review F6):
 * 1. destination is on the customer list → BUSINESS
 * 2. destination is the navigation bookmark HOME or OFFICE → COMMUTE
 * 3. otherwise (or no destination) → the previous trip's purpose
 * 4. no previous purpose → BUSINESS (a company car)
 */
object PurposeDefaults {

    /** Two places closer than this count as the same place. */
    const val SAME_PLACE_METERS = 100.0

    fun resolve(
        destination: NaviPlace?,
        customers: List<NaviPlace>,
        commutePlaces: List<NaviPlace>,
        lastPurpose: Purpose?,
    ): DefaultPurpose = when {
        destination != null && customers.any { samePlace(destination, it) } ->
            DefaultPurpose(Purpose.BUSINESS, PurposeSource.CUSTOMER)
        destination != null && commutePlaces.any { samePlace(destination, it) } ->
            DefaultPurpose(Purpose.COMMUTE, PurposeSource.COMMUTE_BOOKMARK)
        lastPurpose != null -> DefaultPurpose(lastPurpose, PurposeSource.LAST_PURPOSE)
        else -> DefaultPurpose(Purpose.BUSINESS, PurposeSource.FALLBACK)
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
