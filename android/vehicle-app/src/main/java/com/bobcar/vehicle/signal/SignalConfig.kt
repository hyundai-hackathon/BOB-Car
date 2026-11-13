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

    /**
     * On start, wait at most this long for the first seat and gear values before rebuilding the state.
     * The first gear value takes about 3.3 s on the emulator (SDK connect ~2.3 s + [GEAR_HOLD_MS]).
     */
    const val RESUME_WAIT_MS = 15_000L

    /** Odometer gain since the last recorded trip end above this counts as a drive nobody recorded. */
    const val UNRECORDED_DRIVE_MIN_KM = 0.5f
}
