package com.bobcar.vehicle.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.data.UploadQueueDao
import com.bobcar.vehicle.data.UploadQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploaderTest {

    private lateinit var db: BobCarDatabase
    private lateinit var dao: UploadQueueDao

    /** Records each batch (as queue ids) and answers with the next scripted result, then Success. */
    private class FakeSender(vararg results: SendResult) : UploadSender {
        val batches = mutableListOf<List<Long>>()
        private val script = ArrayDeque(results.toList())
        override fun send(items: List<UploadQueueEntity>): SendResult {
            batches += items.map { it.id }
            return script.removeFirstOrNull() ?: SendResult.Success
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadQueueDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun enqueue(count: Int): List<Long> =
        (1..count).map { dao.insert(UploadQueueEntity(kind = "vehicle_event", payloadJson = "{}", createdAt = it.toLong())) }

    @Test
    fun sendsEverythingOldestFirstInBatches() = runTest {
        val ids = enqueue(5)
        val sender = FakeSender()

        val outcome = Uploader(dao, sender, batchSize = 2, now = { 100 }).run()

        assertEquals(Uploader.Outcome.Done(5), outcome)
        assertEquals(listOf(ids.subList(0, 2), ids.subList(2, 4), ids.subList(4, 5)), sender.batches)
        assertEquals(0, dao.pendingCountFlow().first())
    }

    @Test
    fun emptyQueueSendsNothing() = runTest {
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), Uploader(dao, sender).run())
        assertEquals(0, sender.batches.size)
    }

    @Test
    fun temporaryFailureKeepsItemsUntouchedForRetry() = runTest {
        enqueue(3)
        val sender = FakeSender(SendResult.Success, SendResult.Retry("ConnectException"))

        val outcome = Uploader(dao, sender, batchSize = 2).run()

        assertEquals(Uploader.Outcome.Retry(2, "ConnectException"), outcome)
        val left = dao.getPending(10).single()
        assertEquals(0, left.attempts)
        assertEquals(null, left.lastError)

        // Server back: the retry sends the rest
        assertEquals(Uploader.Outcome.Done(1), Uploader(dao, FakeSender(), batchSize = 2).run())
        assertEquals(0, dao.pendingCountFlow().first())
    }

    @Test
    fun rejectedBatchStopsTheRunAndCountsAnAttempt() = runTest {
        val ids = enqueue(3)
        val sender = FakeSender(SendResult.Rejected("HTTP 400"))

        val outcome = Uploader(dao, sender, batchSize = 2).run()

        assertEquals(Uploader.Outcome.Rejected(0, "HTTP 400"), outcome)
        assertEquals(1, sender.batches.size)
        val pending = dao.getPending(10).associateBy { it.id }
        assertEquals(1, pending.getValue(ids[0]).attempts)
        assertEquals("HTTP 400", pending.getValue(ids[0]).lastError)
        assertEquals(0, pending.getValue(ids[2]).attempts)
    }

    @Test
    fun itemRefusedTooOftenStaysQueuedButNoLongerBlocksOthers() = runTest {
        val ids = enqueue(2)
        repeat(Uploader.MAX_ATTEMPTS) {
            Uploader(dao, FakeSender(SendResult.Rejected("HTTP 422")), batchSize = 1).run()
        }

        val sender = FakeSender()
        val outcome = Uploader(dao, sender, batchSize = 1).run()

        assertEquals(Uploader.Outcome.Done(1), outcome)
        assertEquals(listOf(listOf(ids[1])), sender.batches)
        // Still counted as unsent on screen
        assertEquals(1, dao.pendingCountFlow().first())
    }

    @Test
    fun cleanUpRemovesOnlyItemsSentLongAgo() = runTest {
        val ids = enqueue(2)
        dao.markSent(listOf(ids[0]), 0)
        dao.markSent(listOf(ids[1]), Uploader.KEEP_SENT_MS)

        val removed = Uploader(dao, FakeSender(), now = { Uploader.KEEP_SENT_MS + 1 }).cleanUp()

        assertEquals(1, removed)
    }
}
