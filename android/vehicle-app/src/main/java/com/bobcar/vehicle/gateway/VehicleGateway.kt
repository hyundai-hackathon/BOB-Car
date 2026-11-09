package com.bobcar.vehicle.gateway

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.util.Log
import com.bobcar.vehicle.signal.SignalConfig
import com.bobcar.vehicle.signal.SignalEvent
import com.bobcar.vehicle.signal.VehicleSignals

/**
 * The only entry point to the Pleos Vehicle SDK.
 * Subscribes to driver seat, brake, driver door, odometer and battery SoC, cleans the values
 * and publishes them to [VehicleSignals]. All clean-up runs on the signal thread.
 *
 * Requests made right after initialize() are answered once the service binds (day1-check 16, B4),
 * so no waiting is needed here.
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)

    @Volatile
    private var initialized = false

    @Volatile
    private var signals: VehicleSignals? = null

    // --- state below is touched only on the signal thread ---
    private var seatOccupied: Boolean? = null
    private var brakePressed: Boolean? = null
    private var lastBrakePressAt: Long? = null
    private var doorOpen: Boolean? = null
    private var odometerKm: Float? = null
    private var pendingDecreaseKm: Float? = null
    private var socPercent: Int? = null

    fun initialize(): Boolean {
        if (initialized) return true
        return try {
            vehicle.initialize()
            initialized = true
            Log.i(TAG, "Vehicle initialize OK")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle initialize failed", e)
            false
        }
    }

    // ---------------------------------------------------------------- subscriptions

    fun startSignals(target: VehicleSignals) {
        if (!initialized || signals != null) return
        signals = target
        try {
            vehicle.getSeat().getCurrentSeatOccupancy(DRIVER_SEAT, { v -> post { onSeatRaw(v) } }, ::logFailure)
            vehicle.getSeat().registerSeatOccupancy(DRIVER_SEAT, seatListener)

            vehicle.getBrake().getCurrentBrakePedalStatus({ v -> post { onBrakeRaw(v) } }, ::logFailure)
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)

            vehicle.getDoor().getCurrentDoorPosition(DRIVER_DOOR, { v -> post { onDoorRaw(v) } }, ::logFailure)
            vehicle.getDoor().registerDoorPosition(DRIVER_DOOR, doorListener)

            vehicle.getOdometer().getCurrentDistanceDriven({ v -> post { onOdometerRaw(v) } }, ::logFailure)
            vehicle.getOdometer().registerDistanceDriven(odometerListener)

            vehicle.getEvBattery().getEvBatteryStateOfCharge({ v -> post { onSocRaw(v) } }, ::logFailure)
            vehicle.getEvBattery().registerEvBatteryStateOfCharge(socListener)

            Log.i(TAG, "Vehicle signals registered")
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle signal registration failed", e)
        }
    }

    fun stopSignals() {
        if (signals == null) return
        try {
            vehicle.getSeat().unregisterSeatOccupancy(DRIVER_SEAT, seatListener)
            vehicle.getBrake().unregisterBrakePedalStatus(brakeListener)
            vehicle.getDoor().unregisterDoorPosition(DRIVER_DOOR, doorListener)
            vehicle.getOdometer().unregisterDistanceDriven(odometerListener)
            vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            Log.i(TAG, "Vehicle signals unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle signal unregistration failed", e)
        } finally {
            signals = null
        }
    }

    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(value: Boolean?) = post { onSeatRaw(value) }
        override fun onFailed(e: Exception) = logFailure(e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(value: Boolean?) = post { onBrakeRaw(value) }
        override fun onFailed(e: Exception) = logFailure(e)
    }

    private val doorListener = object : DoorPositionListener {
        override fun onPositionUpdated(value: Int?) = post { onDoorRaw(value) }
        override fun onFailed(e: Exception) = logFailure(e)
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) = post { onOdometerRaw(value) }
        override fun onFailed(e: Exception) = logFailure(e)
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) = post { onSocRaw(value) }
        override fun onFailed(e: Exception) = logFailure(e)
    }

    // ---------------------------------------------------------------- clean-up (signal thread)

    /** SDK reports true when the seat is EMPTY (S2). Inverted here and nowhere else. */
    private fun onSeatRaw(empty: Boolean?) {
        val s = signals ?: return
        if (empty == null) return
        val occupied = !empty
        if (occupied == seatOccupied) return
        val previous = seatOccupied
        seatOccupied = occupied
        s.publish(
            "driverSeat occupied=$occupied",
            { it.copy(driverSeatOccupied = occupied) },
            if (previous == null) null else SignalEvent.DriverSeatChanged(occupied, s.now()),
            initial = previous == null,
        )
    }

    /** Only the press (false → true) is an event; presses within the ignore window count once. */
    private fun onBrakeRaw(pressed: Boolean?) {
        val s = signals ?: return
        if (pressed == null || pressed == brakePressed) return
        val previous = brakePressed
        brakePressed = pressed
        val now = s.now()
        val lastPress = lastBrakePressAt
        val isPress = previous == false && pressed &&
            (lastPress == null || now - lastPress >= SignalConfig.BRAKE_PRESS_IGNORE_MS)
        if (isPress) lastBrakePressAt = now
        val ignored = previous == false && pressed && !isPress
        s.publish(
            "brake pressed=$pressed" + when {
                isPress -> " -> BrakePressed"
                ignored -> " (within ${SignalConfig.BRAKE_PRESS_IGNORE_MS} ms, ignored)"
                else -> ""
            },
            { it.copy(brakePressed = pressed) },
            if (isPress) SignalEvent.BrakePressed(now) else null,
            initial = previous == null,
        )
    }

    /** Door position 0 = closed, 1 = open (day1-check 0). */
    private fun onDoorRaw(position: Int?) {
        val s = signals ?: return
        if (position == null) return
        val open = position != 0
        if (open == doorOpen) return
        val previous = doorOpen
        doorOpen = open
        s.publish(
            "driverDoor open=$open",
            { it.copy(driverDoorOpen = open) },
            if (previous == null) null else SignalEvent.DriverDoorChanged(open, s.now()),
            initial = previous == null,
        )
    }

    /**
     * Same value repeats every few seconds (B2) and is ignored.
     * A smaller value is dropped once (stale 0.0 right after register, B7); if the same smaller value
     * arrives again it is accepted as the new baseline (console reset or emulator restart, B8).
     */
    private fun onOdometerRaw(km: Float?) {
        val s = signals ?: return
        if (km == null || km < 0f) return
        val current = odometerKm
        when {
            current == null -> Unit
            km == current -> {
                pendingDecreaseKm = null
                return
            }
            km < current && pendingDecreaseKm != km -> {
                pendingDecreaseKm = km
                Log.w(TAG, "odometer decreased $current -> $km, ignored once")
                return
            }
            km < current -> Log.w(TAG, "odometer decrease $current -> $km confirmed, new baseline")
        }
        pendingDecreaseKm = null
        odometerKm = km
        s.publish(
            "odometer km=$km",
            { it.copy(odometerKm = km) },
            if (current == null) null else SignalEvent.OdometerChanged(km, s.now()),
            initial = current == null,
        )
    }

    private fun onSocRaw(percent: Int?) {
        val s = signals ?: return
        if (percent == null || percent == socPercent) return
        if (percent !in SignalConfig.SOC_RANGE) {
            Log.w(TAG, "soc $percent out of range, dropped")
            return
        }
        val previous = socPercent
        socPercent = percent
        s.publish(
            "soc percent=$percent",
            { it.copy(socPercent = percent) },
            if (previous == null) null else SignalEvent.SocChanged(percent, s.now()),
            initial = previous == null,
        )
    }

    // ---------------------------------------------------------------- fake values (debug)

    /** Feeds values through the same clean-up as SDK values. Used by the debug fake signal receiver. */
    fun injectSeat(occupied: Boolean) = signals?.post { onSeatRaw(!occupied) }
    fun injectBrake(pressed: Boolean) = signals?.post { onBrakeRaw(pressed) }
    fun injectDoor(open: Boolean) = signals?.post { onDoorRaw(if (open) 1 else 0) }
    fun injectOdometer(km: Float) = signals?.post { onOdometerRaw(km) }
    fun injectSoc(percent: Int) = signals?.post { onSocRaw(percent) }

    // ---------------------------------------------------------------- helpers

    /** Posts a real SDK value; dropped while fake mode is on. */
    private fun post(block: () -> Unit) {
        val s = signals ?: return
        if (s.fakeMode) return
        s.post(block)
    }

    private fun logFailure(e: Exception) {
        Log.e(TAG, "Vehicle callback failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    fun release() {
        stopSignals()
        if (!initialized) return
        try {
            vehicle.release()
            Log.i(TAG, "Vehicle release OK")
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle release failed", e)
        } finally {
            initialized = false
        }
    }

    private companion object {
        const val TAG = "BOBCar.Vehicle"
        val DRIVER_SEAT = SeatArea(SeatValue.ROW_1_LEFT)
        val DRIVER_DOOR = DoorArea(DoorValue.ROW_1_LEFT)
    }
}
