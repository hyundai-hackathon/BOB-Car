package com.bobcar.vehicle.energy

import com.bobcar.vehicle.checkin.Briefing
import com.bobcar.vehicle.checkin.Unrecorded
import com.bobcar.vehicle.signal.SignalConfig

/** The battery was charged between two snapshots. [kwh] is null when the battery capacity is not known. */
data class Charge(val gainedPercent: Int, val kwh: Float?)

/**
 * The rules for comparing two snapshots of the parked car (the earlier one and now):
 * a charge, and a drive nobody recorded. Values that cannot be trusted give no result.
 */
object GapCheck {

    /**
     * Battery gained [SignalConfig.CHARGE_MIN_SOC_GAIN_PERCENT] points or more. kWh = gained points × capacity.
     * No result when a value is missing or outside [SignalConfig.SOC_RANGE].
     */
    fun charge(beforePercent: Int?, nowPercent: Int?, capacityWh: Float?): Charge? {
        if (beforePercent == null || nowPercent == null) return null
        if (beforePercent !in SignalConfig.SOC_RANGE || nowPercent !in SignalConfig.SOC_RANGE) return null
        val gained = nowPercent - beforePercent
        if (gained < SignalConfig.CHARGE_MIN_SOC_GAIN_PERCENT) return null
        val kwh = capacityWh?.takeIf { it > 0f }?.let { gained * it / 100f / 1_000f }
        return Charge(gained, kwh)
    }

    /**
     * Kilometres driven with no trip recorded, by the same rule as the driver card's briefing
     * ([Briefing.unrecorded]); null when there are none or the odometer values cannot be compared.
     */
    fun unrecordedKm(beforeKm: Float?, nowKm: Float?): Float? =
        (Briefing.unrecorded(beforeKm, nowKm) as? Unrecorded.Found)?.km
}
