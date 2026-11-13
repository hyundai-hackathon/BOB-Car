package com.bobcar.vehicle.checkin

import com.bobcar.vehicle.signal.SignalConfig

/** Whether the car was driven without a record since the last recorded trip end. */
sealed interface Unrecorded {
    /** Odometer matches the last record. */
    data object None : Unrecorded

    /** [km] were driven with no trip recorded. Shown as one case ("1건") until the server keeps a list. */
    data class Found(val km: Float) : Unrecorded

    /** No usable reference: no trip ended yet, a value is missing, 0, or the odometer went backwards (emulator reset, B8). */
    data object Unknown : Unrecorded
}

/** What the driver card tells the driver about the car (battery %, odometer, unrecorded drive). */
data class Briefing(val socPercent: Int?, val odometerKm: Float?, val unrecorded: Unrecorded) {
    companion object {
        fun of(socPercent: Int?, odometerKm: Float?, lastRecordedOdometerKm: Float?): Briefing =
            Briefing(socPercent, odometerKm, unrecorded(lastRecordedOdometerKm, odometerKm))

        /** The one rule for the unrecorded-drive check (the server check reuses the same threshold). */
        fun unrecorded(lastRecordedKm: Float?, currentKm: Float?): Unrecorded {
            if (lastRecordedKm == null || currentKm == null) return Unrecorded.Unknown
            if (lastRecordedKm <= 0f || currentKm <= 0f || currentKm < lastRecordedKm) return Unrecorded.Unknown
            val gained = currentKm - lastRecordedKm
            return if (gained > SignalConfig.UNRECORDED_DRIVE_MIN_KM) Unrecorded.Found(gained) else Unrecorded.None
        }
    }
}
