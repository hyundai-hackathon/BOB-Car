package com.bobcar.mobile.data

/*
 * What the phone app reads from the server (server/functions/src/phone.ts). App-made IDs only.
 */

data class Driver(val id: String, val name: String, val department: String?)

/** The driver this phone acts as (chosen on first run) and whether they agreed to collection. */
data class Me(val driverId: String, val name: String, val department: String?, val agreed: Boolean)

enum class Purpose(val label: String) {
    BUSINESS("업무"),
    COMMUTE("출퇴근"),
    PERSONAL("개인");

    companion object {
        fun of(value: String?): Purpose? = entries.firstOrNull { it.name == value }
    }
}

/** What the 확인 tab asks about a trip. */
enum class Check { UNRECORDED, DRIVER, PURPOSE }

data class PhoneTrip(
    val tripDocId: String,
    val vehicleId: String,
    /** ACTIVE (driving now), ENDED, or UNRECORDED (nobody has taken it yet) */
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val startKm: Double?,
    val endKm: Double?,
    val distanceKm: Double?,
    /** null for a personal trip (never stored) or when no destination was set */
    val destination: String?,
    val expectedTollFare: Int?,
    val purpose: Purpose?,
    val purposeProvisional: Boolean,
    val driverId: String?,
    val driverName: String?,
    val driverProvisional: Boolean,
    val memo: String?,
    val closedByServer: Boolean,
    /** Someone else named this driver on the phone */
    val assignedByName: String?,
    /** Unrecorded drive: who may have driven it */
    val candidates: List<Driver>,
    val check: Check?,
) {
    val active: Boolean get() = status == STATUS_ACTIVE
    val unrecorded: Boolean get() = status == STATUS_UNRECORDED

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_UNRECORDED = "UNRECORDED"
    }
}

data class LastCharge(val at: Long, val fromPercent: Int?, val toPercent: Int?)

data class VehicleStatus(
    val vehicleId: String,
    val name: String?,
    val model: String?,
    val plate: String?,
    val driving: Boolean,
    val socPercent: Int?,
    val lastCharge: LastCharge?,
    val odometerKm: Double?,
    val monthKm: Double,
    val businessRatioPercent: Double?,
    val lastDriverName: String?,
    val lastUploadAt: Long?,
)

/** One change to a trip. Only the set fields are sent. [memo] "" clears it. */
data class TripChange(
    val driverId: String? = null,
    val purpose: Purpose? = null,
    val memo: String? = null,
    /** "맞습니다": my provisional driver and purpose become confirmed */
    val confirm: Boolean = false,
)
