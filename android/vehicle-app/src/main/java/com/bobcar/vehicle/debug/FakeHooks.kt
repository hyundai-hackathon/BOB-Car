package com.bobcar.vehicle.debug

import com.bobcar.vehicle.trip.Purpose

/** App actions the debug fake-signal receiver can trigger (screen taps and mock data changes). */
class FakeHooks(
    /** [계속 운행] */
    val continueTapped: () -> Unit,
    /** Driver card answered with this driver ID. */
    val confirmDriver: (String) -> Unit,
    /** Mock reservation for now: a driver ID, or null for no reservation. */
    val setReservation: (String?) -> Unit,
    /** Forget this driver's consent answer (try the first check-in again). */
    val resetConsent: (String) -> Unit,
    /** A purpose tapped on the trip-end card. */
    val choosePurpose: (Purpose) -> Unit,
    /** Voice guidance behaves as if TTS initialization had failed (screen only from now on). */
    val failVoice: () -> Unit,
)
