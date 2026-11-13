package com.bobcar.vehicle.driving

import com.bobcar.vehicle.signal.Gear
import com.bobcar.vehicle.signal.SignalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DrivingStateMachineTest {

    private val events = mutableListOf<DrivingEvent>()
    private lateinit var machine: DrivingStateMachine
    private var gear = Gear.PARK

    @Before
    fun setUp() {
        events.clear()
        gear = Gear.PARK
        machine = DrivingStateMachine(countdownMs = COUNTDOWN, mergeWindowMs = MERGE) { events += it }
        machine.resume(seatOccupied = false, gear = Gear.PARK, at = 0)
        events.clear()
    }

    // ---------------------------------------------------------------- helpers

    private fun seat(at: Long, occupied: Boolean) =
        machine.onSignal(SignalEvent.DriverSeatChanged(occupied, at))

    private fun door(at: Long, open: Boolean) =
        machine.onSignal(SignalEvent.DriverDoorChanged(open, at))

    private fun brake(at: Long) = machine.onSignal(SignalEvent.BrakePressed(at))

    private fun shift(at: Long, to: Gear) {
        machine.onSignal(SignalEvent.GearChanged(gear, to, at))
        gear = to
    }

    /** Board, close check-in with the brake, drive off, come back to P. Returns the P time. */
    private fun driveAndPark(start: Long, driverId: String? = "kim"): Long {
        seat(start, true)
        driverId?.let { machine.onDriverConfirmed(it) }
        brake(start + 1_000)
        shift(start + 2_000, Gear.DRIVE)
        shift(start + 60_000, Gear.PARK)
        return start + 60_000
    }

    private fun types() = events.map { it::class.simpleName }

    // ---------------------------------------------------------------- check-in

    @Test
    fun boardingShowsCardAndBrakeInParkClosesItOnce() {
        seat(1_000, true)
        brake(2_000)
        brake(5_000)

        assertEquals(listOf("Boarded", "CheckinClosed"), types())
        assertEquals(CheckinTrigger.BRAKE_IN_PARK, (events[1] as DrivingEvent.CheckinClosed).trigger)
        assertEquals(DrivingState.CheckingIn(1_000, checkinClosed = true), machine.state)
    }

    @Test
    fun brakeWithoutDriverDoesNothing() {
        brake(1_000)
        assertTrue(events.isEmpty())
        assertEquals(DrivingState.Idle, machine.state)
    }

    @Test
    fun leavingBeforeDrivingGoesBackToIdleWithoutTripEvents() {
        seat(1_000, true)
        seat(3_000, false)
        assertEquals(listOf("Boarded"), types())
        assertEquals(DrivingState.Idle, machine.state)
    }

    @Test
    fun departingWithoutBrakeClosesCheckinTogetherWithTripStart() {
        seat(1_000, true)
        shift(2_000, Gear.DRIVE)

        assertEquals(listOf("Boarded", "CheckinClosed", "TripStarted"), types())
        assertEquals(CheckinTrigger.TRIP_START, (events[1] as DrivingEvent.CheckinClosed).trigger)
    }

    // ---------------------------------------------------------------- end countdown

    @Test
    fun parkStartsCountdownAndTripEndsWhenItRunsOut() {
        val parkedAt = driveAndPark(0)
        val started = events.filterIsInstance<DrivingEvent.EndCountdownStarted>().single()
        assertEquals(parkedAt + COUNTDOWN, started.deadline)

        machine.onTick(parkedAt + COUNTDOWN - 1)
        assertTrue(events.none { it is DrivingEvent.TripEnded })

        machine.onTick(parkedAt + COUNTDOWN)
        assertEquals(EndReason.COUNTDOWN, events.filterIsInstance<DrivingEvent.TripEnded>().single().reason)
        assertEquals(DrivingState.Parked(parkedAt + COUNTDOWN), machine.state)
    }

    @Test
    fun continueTappedCancelsCountdownAndTripKeepsGoing() {
        val parkedAt = driveAndPark(0)
        machine.onContinueTapped(parkedAt + 3_000)
        machine.onTick(parkedAt + COUNTDOWN * 3)

        val cancel = events.filterIsInstance<DrivingEvent.EndCountdownCancelled>().single()
        assertEquals(CancelReason.CONTINUE_TAPPED, cancel.reason)
        assertTrue(events.none { it is DrivingEvent.TripEnded })
        assertTrue(machine.state is DrivingState.Driving)
    }

    @Test
    fun leavingParkCancelsCountdownAndNextParkStartsANewOne() {
        val parkedAt = driveAndPark(0)
        shift(parkedAt + 4_000, Gear.DRIVE)
        machine.onTick(parkedAt + COUNTDOWN * 2)
        shift(parkedAt + 30_000, Gear.PARK)

        val cancel = events.filterIsInstance<DrivingEvent.EndCountdownCancelled>().single()
        assertEquals(CancelReason.GEAR_CHANGED, cancel.reason)
        assertEquals(2, events.count { it is DrivingEvent.EndCountdownStarted })
        assertEquals(1, events.count { it is DrivingEvent.TripStarted })
        assertTrue(events.none { it is DrivingEvent.TripEnded })
    }

    @Test
    fun countdownWaitsWhileTheDriverListIsOpenAndRestartsInFull() {
        val parkedAt = driveAndPark(0)
        machine.onEndCountdownPaused()
        machine.onTick(parkedAt + COUNTDOWN * 5)
        assertTrue(events.none { it is DrivingEvent.TripEnded })
        assertTrue((machine.state as DrivingState.EndCountdown).paused)

        val closedAt = parkedAt + 40_000
        machine.onEndCountdownResumed(closedAt)
        assertEquals(DrivingState.EndCountdown(2_000, closedAt + COUNTDOWN), machine.state)

        machine.onTick(closedAt + COUNTDOWN)
        assertEquals(EndReason.COUNTDOWN, events.filterIsInstance<DrivingEvent.TripEnded>().single().reason)
    }

    @Test
    fun seatEmptyWhileTheDriverListIsOpenStillEndsTheTrip() {
        val parkedAt = driveAndPark(0)
        machine.onEndCountdownPaused()
        seat(parkedAt + 5_000, false)

        assertEquals(EndReason.SEAT_EMPTY, events.filterIsInstance<DrivingEvent.TripEnded>().single().reason)
    }

    @Test
    fun leavingParkWhileTheDriverListIsOpenCancelsTheCard() {
        val parkedAt = driveAndPark(0)
        machine.onEndCountdownPaused()
        shift(parkedAt + 5_000, Gear.DRIVE)

        assertEquals(CancelReason.GEAR_CHANGED, events.filterIsInstance<DrivingEvent.EndCountdownCancelled>().single().reason)
        assertTrue(machine.state is DrivingState.Driving)
    }

    @Test
    fun pauseAndResumeOutsideTheCountdownDoNothing() {
        driveAndPark(0)
        machine.onContinueTapped(61_000)
        val before = machine.state

        machine.onEndCountdownPaused()
        machine.onEndCountdownResumed(62_000)

        assertEquals(before, machine.state)
    }

    // ---------------------------------------------------------------- exit detection

    @Test
    fun seatEmptyDuringCountdownEndsTripImmediately() {
        val parkedAt = driveAndPark(0)
        seat(parkedAt + 2_000, false)

        val end = events.filterIsInstance<DrivingEvent.TripEnded>().single()
        assertEquals(EndReason.SEAT_EMPTY, end.reason)
        assertEquals(parkedAt + 2_000, end.at)
        assertEquals(DrivingState.Idle, machine.state)
    }

    @Test
    fun doorOpenDuringCountdownEndsTripImmediately() {
        val parkedAt = driveAndPark(0)
        door(parkedAt + 2_000, true)

        assertEquals(EndReason.DOOR_OPEN, events.filterIsInstance<DrivingEvent.TripEnded>().single().reason)
        assertEquals(DrivingState.Parked(parkedAt + 2_000), machine.state)
    }

    @Test
    fun seatEmptyInParkAfterContinueEndsTrip() {
        val parkedAt = driveAndPark(0)
        machine.onContinueTapped(parkedAt + 1_000)
        seat(parkedAt + 120_000, false)

        assertEquals(EndReason.SEAT_EMPTY, events.filterIsInstance<DrivingEvent.TripEnded>().single().reason)
    }

    @Test
    fun doorOpenOrSeatEmptyWhileInDriveIsIgnored() {
        seat(0, true)
        shift(1_000, Gear.DRIVE)
        door(2_000, true)
        seat(3_000, false)

        assertTrue(events.none { it is DrivingEvent.TripEnded })
        assertTrue(machine.state is DrivingState.Driving)
    }

    // ---------------------------------------------------------------- merging (10 minutes)

    @Test
    fun sameDriverWithinWindowContinuesPreviousTrip() {
        val parkedAt = driveAndPark(0, driverId = "kim")
        seat(parkedAt + 1_000, false) // trip ends on exit
        val endedAt = parkedAt + 1_000

        seat(endedAt + 60_000, true)
        machine.onDriverConfirmed("kim")
        shift(endedAt + MERGE, Gear.DRIVE)

        assertEquals(listOf(false, true), events.filterIsInstance<DrivingEvent.TripStarted>().map { it.mergedWithPrevious })
    }

    @Test
    fun sameDriverAfterWindowStartsNewTrip() {
        val parkedAt = driveAndPark(0, driverId = "kim")
        seat(parkedAt + 1_000, false)
        val endedAt = parkedAt + 1_000

        seat(endedAt + 60_000, true)
        machine.onDriverConfirmed("kim")
        shift(endedAt + MERGE + 1, Gear.DRIVE)

        assertEquals(listOf(false, false), events.filterIsInstance<DrivingEvent.TripStarted>().map { it.mergedWithPrevious })
    }

    @Test
    fun differentDriverWithinWindowStartsNewTrip() {
        val parkedAt = driveAndPark(0, driverId = "kim")
        seat(parkedAt + 1_000, false)

        seat(parkedAt + 30_000, true)
        machine.onDriverConfirmed("lee")
        shift(parkedAt + 40_000, Gear.DRIVE)

        assertEquals(listOf(false, false), events.filterIsInstance<DrivingEvent.TripStarted>().map { it.mergedWithPrevious })
    }

    @Test
    fun driverStayingSeatedAfterCountdownContinuesTrip() {
        val parkedAt = driveAndPark(0, driverId = "kim")
        machine.onTick(parkedAt + COUNTDOWN)
        shift(parkedAt + COUNTDOWN + 5_000, Gear.DRIVE)

        assertEquals(listOf(false, true), events.filterIsInstance<DrivingEvent.TripStarted>().map { it.mergedWithPrevious })
    }

    @Test
    fun unknownDriverIsNeverMerged() {
        val parkedAt = driveAndPark(0, driverId = null)
        machine.onTick(parkedAt + COUNTDOWN)
        shift(parkedAt + COUNTDOWN + 5_000, Gear.DRIVE)

        assertEquals(listOf(false, false), events.filterIsInstance<DrivingEvent.TripStarted>().map { it.mergedWithPrevious })
    }

    // ---------------------------------------------------------------- app resume

    @Test
    fun resumeInDriveRebuildsTripAndParkStartsCountdown() {
        machine.resume(seatOccupied = true, gear = Gear.DRIVE, at = 100_000)
        gear = Gear.DRIVE
        shift(130_000, Gear.PARK)

        assertEquals(listOf("AppResumed", "EndCountdownStarted"), types())
        assertTrue((events[0] as DrivingEvent.AppResumed).state is DrivingState.Driving)
    }

    @Test
    fun resumeSeatedInParkWaitsForCheckin() {
        machine.resume(seatOccupied = true, gear = Gear.PARK, at = 100_000)
        brake(101_000)

        assertEquals(listOf("AppResumed", "CheckinClosed"), types())
        assertEquals(DrivingState.CheckingIn(100_000, checkinClosed = true), machine.state)
    }

    @Test
    fun resumeWithoutGearSaysGearUnknownAndLateGearRebuildsDriving() {
        // Restart in D: seat came in, the gear did not before the wait ran out
        machine.resume(seatOccupied = true, gear = null, at = 100_000)
        val first = events.last() as DrivingEvent.AppResumed
        assertEquals(false, first.gearKnown)
        assertTrue(first.state is DrivingState.CheckingIn)

        // The first gear arrives (no GearChanged for a first value): resume again
        machine.resume(seatOccupied = true, gear = Gear.DRIVE, at = 101_000)
        gear = Gear.DRIVE
        val second = events.last() as DrivingEvent.AppResumed
        assertEquals(true, second.gearKnown)
        assertEquals(DrivingState.Driving(101_000, Gear.DRIVE), second.state)

        // and the trip can end normally
        shift(130_000, Gear.PARK)
        machine.onTick(130_000 + COUNTDOWN)
        assertEquals(listOf("AppResumed", "AppResumed", "EndCountdownStarted", "TripEnded"), types())
    }

    private companion object {
        const val COUNTDOWN = 10_000L
        const val MERGE = 10 * 60_000L
    }
}
