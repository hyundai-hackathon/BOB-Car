package com.bobcar.vehicle.util

/**
 * Masks free text (destination names) before it goes to logcat (CLAUDE.md rule 6).
 * First character + length; text of two characters or fewer (e.g. "집") shows its length only.
 * Driver names are never logged (only app-made driver IDs). Coordinates and VIN are not read yet;
 * add their masking here when they are.
 */
fun maskForLog(text: String?): String = when {
    text.isNullOrEmpty() -> "(empty)"
    text.length <= 2 -> "***(${text.length})"
    else -> text.take(1) + "***(${text.length})"
}
