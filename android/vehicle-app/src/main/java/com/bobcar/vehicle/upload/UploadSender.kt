package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueEntity

/** Sends one batch of queued records. The only place that knows the server API. */
fun interface UploadSender {
    fun send(items: List<UploadQueueEntity>): SendResult
}

sealed interface SendResult {
    data object Success : SendResult

    /** Network down, timeout, 5xx, 408, 429: try the same batch again later. */
    data class Retry(val reason: String) : SendResult

    /** The server refused the batch (other 4xx): count an attempt on each item. */
    data class Rejected(val reason: String) : SendResult
}
