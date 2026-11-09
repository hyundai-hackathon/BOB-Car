package com.bobcar.vehicle.driving

import com.bobcar.vehicle.signal.Gear
import com.bobcar.vehicle.signal.SignalConfig
import com.bobcar.vehicle.signal.SignalEvent

/**
 * Turns cleaned vehicle signals into the check-in → drive → end flow (design-review "현재 기획").
 *
 * - Boarded: driver seat empty → occupied.
 * - CheckinClosed: brake pressed in P while checking in, once per trip. If the car leaves P first,
 *   it closes together with TripStarted.
 * - TripStarted: settled gear leaves P. Same confirmed driver within [mergeWindowMs] of the last end
 *   continues the previous trip.
 * - EndCountdown: settled gear returns to P during a trip. [계속 운행] or leaving P cancels it.
 * - TripEnded: countdown runs out, or the driver seat empties / driver door opens while in P.
 *
 * Pure Kotlin: time comes in with every call and the countdown is advanced by [onTick], so the
 * whole flow is unit-testable. Not thread-safe; call it from one thread (the signal thread).
 */
class DrivingStateMachine(
    private val countdownMs: Long = SignalConfig.END_COUNTDOWN_MS,
    private val mergeWindowMs: Long = SignalConfig.TRIP_MERGE_WINDOW_MS,
    private val emit: (DrivingEvent) -> Unit,
) {
    var state: DrivingState = DrivingState.Idle
        private set

    private var seatOccupied = false
    private var gear: Gear? = null
    private var currentDriverId: String? = null
    private var lastTrip: LastTrip? = null

    private data class LastTrip(val endedAt: Long, val driverId: String?)

    /** App or service start: rebuild the state from current values instead of waiting for changes. */
    fun resume(seatOccupied: Boolean?, gear: Gear?, at: Long) {
        this.seatOccupied = seatOccupied == true
        this.gear = gear
        state = when {
            gear != null && gear != Gear.PARK -> DrivingState.Driving(at, gear)
            this.seatOccupied -> DrivingState.CheckingIn(at, checkinClosed = false)
            else -> DrivingState.Idle
        }
        emit(DrivingEvent.AppResumed(at, state))
    }

    fun onSignal(event: SignalEvent) {
        when (event) {
            is SignalEvent.DriverSeatChanged -> onSeat(event.occupied, event.at)
            is SignalEvent.GearChanged -> onGear(event.to, event.at)
            is SignalEvent.BrakePressed -> onBrake(event.at)
            is SignalEvent.DriverDoorChanged -> if (event.open) onExitSign(EndReason.DOOR_OPEN, event.at)
            is SignalEvent.OdometerChanged,
            is SignalEvent.SocChanged,
            is SignalEvent.RouteStarted -> Unit
        }
    }

    /** The driver card was answered (or a default was applied). Used to decide trip merging. */
    fun onDriverConfirmed(driverId: String) {
        currentDriverId = driverId
    }

    /** [계속 운행] on the countdown card. */
    fun onContinueTapped(at: Long) {
        val s = state as? DrivingState.EndCountdown ?: return
        state = DrivingState.Driving(s.tripStartedAt, Gear.PARK)
        emit(DrivingEvent.EndCountdownCancelled(at, CancelReason.CONTINUE_TAPPED))
    }

    /** Called by a timer; ends the trip once the countdown deadline has passed. */
    fun onTick(now: Long) {
        val s = state as? DrivingState.EndCountdown ?: return
        if (now >= s.deadline) endTrip(EndReason.COUNTDOWN, now)
    }

    // ---------------------------------------------------------------- inputs

    private fun onSeat(occupied: Boolean, at: Long) {
        seatOccupied = occupied
        if (occupied) {
            if (state == DrivingState.Idle) {
                currentDriverId = null
                state = DrivingState.CheckingIn(at, checkinClosed = false)
                emit(DrivingEvent.Boarded(at))
            }
            return
        }
        when (state) {
            is DrivingState.CheckingIn, is DrivingState.Parked -> state = DrivingState.Idle
            else -> onExitSign(EndReason.SEAT_EMPTY, at)
        }
    }

    private fun onBrake(at: Long) {
        val s = state as? DrivingState.CheckingIn ?: return
        if (s.checkinClosed || gear != Gear.PARK) return
        state = s.copy(checkinClosed = true)
        emit(DrivingEvent.CheckinClosed(at, CheckinTrigger.BRAKE_IN_PARK))
    }

    private fun onGear(to: Gear, at: Long) {
        gear = to
        when (val s = state) {
            DrivingState.Idle, is DrivingState.CheckingIn, is DrivingState.Parked ->
                if (to != Gear.PARK) startTrip(s, to, at)

            is DrivingState.Driving ->
                if (to == Gear.PARK) {
                    val deadline = at + countdownMs
                    state = DrivingState.EndCountdown(s.tripStartedAt, deadline)
                    emit(DrivingEvent.EndCountdownStarted(at, deadline))
                } else {
                    state = s.copy(gear = to)
                }

            is DrivingState.EndCountdown ->
                if (to != Gear.PARK) {
                    state = DrivingState.Driving(s.tripStartedAt, to)
                    emit(DrivingEvent.EndCountdownCancelled(at, CancelReason.GEAR_CHANGED))
                }
        }
    }

    /** Seat empty or door open: ends the trip only while the car is in P. */
    private fun onExitSign(reason: EndReason, at: Long) {
        val s = state
        val trippingInPark = s is DrivingState.EndCountdown ||
            (s is DrivingState.Driving && s.gear == Gear.PARK)
        if (trippingInPark) endTrip(reason, at)
    }

    // ---------------------------------------------------------------- transitions

    private fun startTrip(from: DrivingState, to: Gear, at: Long) {
        if (from !is DrivingState.CheckingIn || !from.checkinClosed) {
            emit(DrivingEvent.CheckinClosed(at, CheckinTrigger.TRIP_START))
        }
        val last = lastTrip
        val merged = last != null &&
            last.driverId != null &&
            last.driverId == currentDriverId &&
            at - last.endedAt <= mergeWindowMs
        state = DrivingState.Driving(at, to)
        emit(DrivingEvent.TripStarted(at, merged))
    }

    private fun endTrip(reason: EndReason, at: Long) {
        lastTrip = LastTrip(at, currentDriverId)
        state = if (seatOccupied) DrivingState.Parked(at) else DrivingState.Idle
        emit(DrivingEvent.TripEnded(at, reason))
    }
}
