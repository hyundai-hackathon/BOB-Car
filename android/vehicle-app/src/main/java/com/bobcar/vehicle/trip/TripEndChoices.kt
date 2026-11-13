package com.bobcar.vehicle.trip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the trip-end card shows and what the trip is saved with when it ends.
 * Filled with the defaults when the end countdown starts. Any answer on the card (a purpose, or a driver
 * from [변경]) confirms both the driver and the purpose shown. Untouched → saved as provisional.
 */
data class EndChoice(
    val tripId: Long,
    val driverId: String?,
    /** The driver was confirmed on the driver card or on this card. */
    val driverConfirmed: Boolean,
    val purpose: Purpose,
    val purposeSource: PurposeSource,
    /** Answered on this card. */
    val answered: Boolean = false,
)

/**
 * Holds the choice for the running trip's end card. Changed only from the trip recording's write queue
 * (one at a time); read from anywhere (the screen observes [state]).
 */
class TripEndChoices {

    private val _state = MutableStateFlow<EndChoice?>(null)
    val state: StateFlow<EndChoice?> = _state.asStateFlow()

    val current: EndChoice? get() = _state.value

    fun set(choice: EndChoice) {
        _state.value = choice
    }

    /** null when there is no card for a trip. */
    fun choosePurpose(purpose: Purpose): EndChoice? =
        _state.value?.let { it.copy(purpose = purpose, driverConfirmed = it.driverId != null, answered = true) }
            ?.also { _state.value = it }

    fun chooseDriver(driverId: String): EndChoice? =
        _state.value?.copy(driverId = driverId, driverConfirmed = true, answered = true)?.also { _state.value = it }

    fun clear() {
        _state.value = null
    }
}
