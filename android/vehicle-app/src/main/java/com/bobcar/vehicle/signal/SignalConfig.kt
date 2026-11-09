package com.bobcar.vehicle.signal

/**
 * Timing and filter values for vehicle signals and trip detection, kept in one place so they can be
 * tuned without touching gateway or state machine code.
 * Basis: docs day1-check 2, 3, 5, design-review F5 and troubleshooting B1, B7, B8.
 */
object SignalConfig {
    /** A gear value counts only after it stays this long (Pleos Gear passes through R on P→D, B1). */
    const val GEAR_HOLD_MS = 1_000L

    /** Brake presses closer together than this are treated as one press. */
    const val BRAKE_PRESS_IGNORE_MS = 1_000L

    /** Battery state of charge outside this range is dropped. */
    val SOC_RANGE = 0..100

    /** After the gear settles in P during a trip, the trip ends when this countdown runs out. */
    const val END_COUNTDOWN_MS = 10_000L

    /** The same driver departing again within this time continues the previous trip. */
    const val TRIP_MERGE_WINDOW_MS = 10 * 60_000L
}
