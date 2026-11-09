package com.bobcar.vehicle.signal

/**
 * Timing and filter values for vehicle signals, kept in one place so they can be tuned
 * without touching gateway code. Basis: docs day1-check 2, 3, 5 and troubleshooting B1, B7, B8.
 */
object SignalConfig {
    /** A gear value counts only after it stays this long (P→D passes through R on the emulator). */
    const val GEAR_HOLD_MS = 1_000L

    /** Brake presses closer together than this are treated as one press. */
    const val BRAKE_PRESS_IGNORE_MS = 1_000L

    /** Battery state of charge outside this range is dropped. */
    val SOC_RANGE = 0..100
}
