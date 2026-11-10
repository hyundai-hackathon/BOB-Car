package com.bobcar.vehicle.util

/** Masks free text (destination names) before it goes to logcat: first two characters + length. */
fun maskForLog(text: String?): String =
    if (text.isNullOrEmpty()) "(empty)" else text.take(2) + "***(${text.length})"
