package com.bobcar.vehicle.driving

import com.bobcar.vehicle.signal.Gear

/** Where the car is in the check-in → drive → end flow. Pure Kotlin, no Android types. */
sealed interface DrivingState {
    /** Nobody in the driver seat and no trip running. */
    data object Idle : DrivingState

    /** Driver seated, no trip yet. [checkinClosed] = the driver card was closed (brake in P, or departure). */
    data class CheckingIn(val since: Long, val checkinClosed: Boolean) : DrivingState

    /** Trip running. [gear] is the last settled gear (P only after [계속 운행]). */
    data class Driving(val tripStartedAt: Long, val gear: Gear) : DrivingState

    /** Trip running, gear settled in P, countdown to the end of the trip. */
    data class EndCountdown(val tripStartedAt: Long, val deadline: Long) : DrivingState

    /** Trip ended while the driver is still seated. */
    data class Parked(val tripEndedAt: Long) : DrivingState
}

/** What the state machine tells the rest of the app. */
sealed interface DrivingEvent {
    val at: Long

    /** Driver seat went empty → occupied. Show the driver card. */
    data class Boarded(override val at: Long) : DrivingEvent

    /** Driver card closes now; an unconfirmed driver is recorded with the default (provisional). Once per trip. */
    data class CheckinClosed(override val at: Long, val trigger: CheckinTrigger) : DrivingEvent

    /** Gear left P. [mergedWithPrevious] = same driver within the merge window, continue the last trip. */
    data class TripStarted(override val at: Long, val mergedWithPrevious: Boolean) : DrivingEvent

    /** Gear settled in P during a trip. Show the "운행 종료까지 10초" card until [deadline]. */
    data class EndCountdownStarted(override val at: Long, val deadline: Long) : DrivingEvent

    data class EndCountdownCancelled(override val at: Long, val reason: CancelReason) : DrivingEvent

    /** Trip confirmed as ended. */
    data class TripEnded(override val at: Long, val reason: EndReason) : DrivingEvent

    /**
     * App or service (re)started; [state] was rebuilt from the current signals.
     * [gearKnown] = false: no gear value yet, so whether the car is driving is unknown (a running trip
     * must not be closed). Another AppResumed follows once the gear arrives.
     */
    data class AppResumed(override val at: Long, val state: DrivingState, val gearKnown: Boolean = true) : DrivingEvent
}

enum class CheckinTrigger { BRAKE_IN_PARK, TRIP_START }

enum class CancelReason { CONTINUE_TAPPED, GEAR_CHANGED }

enum class EndReason { COUNTDOWN, SEAT_EMPTY, DOOR_OPEN }
