package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueDao

/**
 * Drains the upload queue oldest first, one batch at a time. Pure logic over the DAO and an
 * [UploadSender], so it is tested without WorkManager or a network.
 */
class Uploader(
    private val dao: UploadQueueDao,
    private val sender: UploadSender,
    private val batchSize: Int = BATCH_SIZE,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome {
        val sentCount: Int

        /** Queue empty (or only items the server refused too often). */
        data class Done(override val sentCount: Int) : Outcome

        /** Temporary failure: WorkManager should retry later with backoff. */
        data class Retry(override val sentCount: Int, val reason: String) : Outcome

        /**
         * The server refused a batch. Stop this run so the same batch is not resent in a loop;
         * it is tried again on the next upload request, up to [MAX_ATTEMPTS] times.
         */
        data class Rejected(override val sentCount: Int, val reason: String) : Outcome
    }

    suspend fun run(): Outcome {
        var sent = 0
        while (true) {
            val batch = dao.getSendable(batchSize, MAX_ATTEMPTS)
            if (batch.isEmpty()) return Outcome.Done(sent)
            val ids = batch.map { it.id }
            when (val result = sender.send(batch)) {
                SendResult.Success -> {
                    dao.markSent(ids, now())
                    sent += batch.size
                }
                is SendResult.Retry -> return Outcome.Retry(sent, result.reason)
                is SendResult.Rejected -> {
                    dao.markFailed(ids, result.reason)
                    return Outcome.Rejected(sent, result.reason)
                }
            }
        }
    }

    /** Sent rows are kept a while for checking, then removed. */
    suspend fun cleanUp(): Int = dao.deleteSentBefore(now() - KEEP_SENT_MS)

    companion object {
        const val BATCH_SIZE = 50
        /** Items the server refused this many times stay in the queue but are no longer sent. */
        const val MAX_ATTEMPTS = 3
        const val KEEP_SENT_MS = 7L * 24 * 60 * 60 * 1000
    }
}
