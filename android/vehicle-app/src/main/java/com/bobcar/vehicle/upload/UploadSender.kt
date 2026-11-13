package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueEntity

/** Sends one batch of queued records. The only place that knows the server API. */
fun interface UploadSender {
    fun send(items: List<UploadQueueEntity>): SendResult
}

sealed interface SendResult {
    /**
     * The server took the batch. [skipped] = queue IDs it did not store, with its reason
     * ("no-consent", "invalid", ...); every other item is done (stored, or already there).
     */
    data class Success(val skipped: Map<Long, String> = emptyMap()) : SendResult

    /** Network down, timeout, 5xx, 408, 429, sign-in trouble: try the same batch again later. */
    data class Retry(val reason: String) : SendResult

    /** The server refused the batch (other 4xx): count an attempt on each item. */
    data class Rejected(val reason: String) : SendResult

    companion object {
        /** Server reason: the driver's consent is not on the server (e.g. its data was wiped). */
        const val SKIP_NO_CONSENT = "no-consent"
    }
}
