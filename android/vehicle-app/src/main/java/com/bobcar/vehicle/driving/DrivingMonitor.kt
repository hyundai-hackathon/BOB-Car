package com.bobcar.vehicle.driving

import android.util.Log
import com.bobcar.vehicle.signal.SignalConfig
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Connects [VehicleSignals] to [DrivingStateMachine] on the signal thread:
 * rebuilds the state once the first seat and gear values are in (AppResume), feeds signal events,
 * and runs the end countdown timer. Exposes the state and recent events for the screen.
 */
class DrivingMonitor(private val signals: VehicleSignals) {

    private val machine = DrivingStateMachine { onDrivingEvent(it) }

    private val _state = MutableStateFlow<DrivingState>(DrivingState.Idle)
    val state: StateFlow<DrivingState> = _state.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<String>>(emptyList())
    val recentEvents: StateFlow<List<String>> = _recentEvents.asStateFlow()

    private val eventListeners = CopyOnWriteArrayList<(DrivingEvent) -> Unit>()

    /** Called on the signal thread for every driving event (e.g. to store it). */
    fun addEventListener(listener: (DrivingEvent) -> Unit) {
        eventListeners += listener
    }

    fun removeEventListener(listener: (DrivingEvent) -> Unit) {
        eventListeners -= listener
    }

    // --- touched only on the signal thread ---
    private var resumed = false
    private val countdownTick = Runnable {
        machine.onTick(signals.now())
        afterChange()
    }

    private val signalListener: (SignalEvent) -> Unit = { event ->
        if (resumed) {
            machine.onSignal(event)
            afterChange()
        }
    }

    fun start() {
        signals.addListener(signalListener)
        val startedAt = signals.now()
        signals.post { resumeWhenReady(startedAt) }
    }

    fun stop() {
        signals.removeListener(signalListener)
        signals.handler.removeCallbacks(countdownTick)
        signals.handler.removeCallbacks(resumeOnFirstGear)
    }

    /** [계속 운행] on the trip-end card. */
    fun onContinueTapped() = signals.post {
        machine.onContinueTapped(signals.now())
        afterChange()
    }

    /** The driver list opened on the trip-end card: the countdown waits. */
    fun pauseEndCountdown() = signals.post {
        machine.onEndCountdownPaused()
        Log.i(TAG, "end countdown paused (driver list)")
        afterChange()
    }

    /** The driver list closed: the countdown starts again from the full time. */
    fun resumeEndCountdown() = signals.post {
        machine.onEndCountdownResumed(signals.now())
        Log.i(TAG, "end countdown resumed")
        afterChange()
    }

    fun onDriverConfirmed(driverId: String) = signals.post { machine.onDriverConfirmed(driverId) }

    // ---------------------------------------------------------------- signal thread

    private fun resumeWhenReady(startedAt: Long) {
        val snapshot = signals.snapshot.value
        val now = signals.now()
        val ready = snapshot.driverSeatOccupied != null && snapshot.gear != null
        if (!ready && now - startedAt < SignalConfig.RESUME_WAIT_MS) {
            signals.handler.postDelayed({ resumeWhenReady(startedAt) }, RESUME_POLL_MS)
            return
        }
        if (!ready) Log.w(TAG, "resume without all values: seat=${snapshot.driverSeatOccupied} gear=${snapshot.gear}")
        machine.resume(snapshot.driverSeatOccupied, snapshot.gear, now)
        resumed = true
        afterChange()
        if (snapshot.gear == null) signals.handler.postDelayed(resumeOnFirstGear, GEAR_POLL_MS)
    }

    /**
     * Resumed without a gear: the first gear value has no GearChanged event, so rebuild the state again
     * when it arrives. Until then no gear events can have moved the state past Idle / CheckingIn.
     */
    private val resumeOnFirstGear: Runnable = object : Runnable {
        override fun run() {
            val snapshot = signals.snapshot.value
            if (snapshot.gear == null) {
                signals.handler.postDelayed(this, GEAR_POLL_MS)
                return
            }
            Log.i(TAG, "first gear ${snapshot.gear} after resume, rebuilding the state")
            machine.resume(snapshot.driverSeatOccupied, snapshot.gear, signals.now())
            afterChange()
        }
    }

    /** Publishes the state and (re)arms the countdown timer for the current deadline. */
    private fun afterChange() {
        val current = machine.state
        _state.value = current
        signals.handler.removeCallbacks(countdownTick)
        if (current is DrivingState.EndCountdown && !current.paused) {
            val delay = (current.deadline - signals.now()).coerceAtLeast(0)
            signals.handler.postDelayed(countdownTick, delay)
        }
    }

    private fun onDrivingEvent(event: DrivingEvent) {
        val line = "${clock.format(Date())} ${describe(event)}"
        Log.i(TAG, describe(event))
        _recentEvents.value = (listOf(line) + _recentEvents.value).take(RECENT_EVENT_COUNT)
        eventListeners.forEach { it(event) }
    }

    private fun describe(event: DrivingEvent): String = when (event) {
        is DrivingEvent.Boarded -> "Boarded"
        is DrivingEvent.CheckinClosed -> "CheckinClosed (${event.trigger})"
        is DrivingEvent.TripStarted -> "TripStarted${if (event.mergedWithPrevious) " (merged)" else ""}"
        is DrivingEvent.EndCountdownStarted -> "EndCountdownStarted (${(event.deadline - event.at) / 1000}s)"
        is DrivingEvent.EndCountdownCancelled -> "EndCountdownCancelled (${event.reason})"
        is DrivingEvent.TripEnded -> "TripEnded (${event.reason})"
        is DrivingEvent.AppResumed ->
            "AppResumed -> ${event.state::class.simpleName}${if (event.gearKnown) "" else " (gear unknown)"}"
    }

    private companion object {
        const val TAG = "BOBCar.Driving"
        const val RESUME_POLL_MS = 200L
        const val GEAR_POLL_MS = 1_000L
        const val RECENT_EVENT_COUNT = 8
        val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }
}
