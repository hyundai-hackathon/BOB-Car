package com.bobcar.vehicle.data

import android.os.SystemClock

/** Signal and driving-event times are elapsedRealtime; stored times are wall-clock epoch milliseconds. */
object WallClock {
    fun fromElapsed(elapsedAt: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedAt)
}
