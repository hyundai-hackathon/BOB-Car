package com.bobcar.vehicle.checkin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The check-in of the driver now in the seat.
 * [closed] = the driver card closed (brake in P or departure); an unconfirmed driver is then
 * recorded as provisional with the default.
 */
data class CheckinState(
    val boardedAt: Long,
    val default: DefaultDriver?,
    val confirmedDriverId: String? = null,
    val closed: Boolean = false,
) {
    /** The driver to record: the confirmed one, else the default. */
    val driverId: String? get() = confirmedDriverId ?: default?.driverId

    val provisional: Boolean get() = confirmedDriverId == null
}

/**
 * Holds the current check-in for the driver card and the trip record.
 * Changed only from the trip recording's write queue (one at a time); read from anywhere.
 * Kept for the whole stay in the seat (also across trips merged or started again while seated);
 * a new boarding starts a new one.
 */
class DriverCheckin {

    private val _state = MutableStateFlow<CheckinState?>(null)
    val state: StateFlow<CheckinState?> = _state.asStateFlow()

    val current: CheckinState? get() = _state.value

    fun start(at: Long, default: DefaultDriver?): CheckinState =
        CheckinState(at, default).also { _state.value = it }

    /** null when nobody is checked in. */
    fun confirm(driverId: String): CheckinState? =
        _state.value?.copy(confirmedDriverId = driverId)?.also { _state.value = it }

    fun close(): CheckinState? =
        _state.value?.copy(closed = true)?.also { _state.value = it }

    fun clear() {
        _state.value = null
    }
}
