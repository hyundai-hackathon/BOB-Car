package com.bobcar.vehicle.signal

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects signals from all gateways onto one thread (SDK callbacks arrive on many binder threads, B3).
 * Gateways post raw values here and do their clean-up on this thread, so their state needs no locks.
 */
class VehicleSignals {

    private val thread = HandlerThread("bobcar-signals").apply { start() }
    val handler = Handler(thread.looper)

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(SignalEvent) -> Unit>()

    fun addListener(listener: (SignalEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (SignalEvent) -> Unit) {
        listeners -= listener
    }

    /** Runs [block] on the signal thread. Safe to call from any thread. */
    fun post(block: () -> Unit) {
        handler.post(block)
    }

    fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * Applies a cleaned value. Must be called on the signal thread (inside [post]).
     * [initial] marks the first value of a signal; [event] is null when the change needs no reaction
     * (initial values, brake release).
     */
    fun publish(
        description: String,
        transform: (VehicleSnapshot) -> VehicleSnapshot,
        event: SignalEvent?,
        initial: Boolean = false,
    ) {
        check(Looper.myLooper() == thread.looper) { "publish must run on the signal thread" }
        _snapshot.value = transform(_snapshot.value)
        Log.i(TAG, if (initial) "$description (initial)" else description)
        if (event != null) listeners.forEach { it(event) }
    }

    fun close() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "BOBCar.Signal"
    }
}
