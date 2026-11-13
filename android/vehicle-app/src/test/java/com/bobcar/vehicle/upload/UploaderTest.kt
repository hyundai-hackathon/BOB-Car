package com.bobcar.vehicle.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bobcar.vehicle.checkin.ConsentNotice
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.data.DriverConsentDao
import com.bobcar.vehicle.data.DriverConsentEntity
import com.bobcar.vehicle.data.UploadQueueDao
import com.bobcar.vehicle.data.UploadQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private lateinit var consents: DriverConsentDao

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BobCarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadQueueDao()
        consents = db.driverConsentDao()
        // The queue mechanics tests below use a driver who agreed
        agree(AGREED)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun agree(driverId: String, version: Int = ConsentNotice.VERSION) =
        consents.upsert(DriverConsentEntity(driverId, DriverConsentEntity.STATUS_AGREED, 0, version))

    private fun uploader(sender: UploadSender, batchSize: Int = Uploader.BATCH_SIZE, now: () -> Long = { 0 }) =
        Uploader(dao, consents, sender, batchSize, now)

    private suspend fun enqueue(count: Int, driverId: String? = AGREED, kind: String = "vehicle_event", createdAt: Long = 0): List<Long> =
        (1..count).map {
            dao.insert(
                UploadQueueEntity(kind = kind, payloadJson = "{}", createdAt = createdAt + it, driverId = driverId),
            )
        }

    // ---------------------------------------------------------------- consent

    @Test
    fun recordsOfADriverWhoHasNotAgreedAreNotSentUntilTheyAgree() = runTest {
        val ids = enqueue(3, driverId = "drv-002")
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())
        assertEquals(0, sender.batches.size)
        assertEquals(3, dao.pendingCountFlow().first())

        agree("drv-002")
        assertEquals(Uploader.Outcome.Done(3), uploader(sender).run())
        assertEquals(listOf(ids), sender.batches)
        assertEquals(0, dao.pendingCountFlow().first())
    }

    @Test
    fun nothingIsSentWhenNobodyAgreed() = runTest {
        consents.delete(AGREED)
        enqueue(2)
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())
        assertEquals(0, sender.batches.size)
    }

    @Test
    fun laterAnswerAndOldNoticeVersionKeepRecordsWaiting() = runTest {
        consents.upsert(DriverConsentEntity("drv-002", DriverConsentEntity.STATUS_DEFERRED, 0, ConsentNotice.VERSION))
        agree("drv-003", version = ConsentNotice.VERSION - 1)
        enqueue(1, driverId = "drv-002")
        enqueue(1, driverId = "drv-003")
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())
        assertEquals(2, dao.pendingCountFlow().first())
    }

    @Test
    fun recordsWithNoDriverWaitUntilTheStayGetsADriverWhoAgreed() = runTest {
        val ids = enqueue(2, driverId = null, createdAt = 1_000)
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())

        dao.assignDriver(since = 1_000, driverId = "drv-002")
        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())

        agree("drv-002")
        assertEquals(Uploader.Outcome.Done(2), uploader(sender).run())
        assertEquals(listOf(ids), sender.batches)
    }

    @Test
    fun confirmingAnotherDriverMovesUnsentRecordsOfTheStayButNotConsentOrEarlierOnes() = runTest {
        val before = enqueue(1, driverId = AGREED, createdAt = 0)
        val stay = enqueue(2, driverId = AGREED, createdAt = 1_000) // provisional default = AGREED
        val consent = enqueue(1, driverId = AGREED, kind = "consent", createdAt = 1_000)

        // [다른 사람]: drv-002 (not agreed yet) was in the seat
        dao.assignDriver(since = 1_000, driverId = "drv-002")
        val sender = FakeSender()
        uploader(sender).run()

        assertEquals(listOf(before + consent), sender.batches)
        assertEquals(stay, dao.getPending(10).map { it.id })
        assertTrue(dao.getPending(10).all { it.driverId == "drv-002" })
    }

    // ---------------------------------------------------------------- queue mechanics

    @Test
    fun sendsEverythingOldestFirstInBatches() = runTest {
        val ids = enqueue(5)
        val sender = FakeSender()

        val outcome = uploader(sender, batchSize = 2, now = { 100 }).run()

        assertEquals(Uploader.Outcome.Done(5), outcome)
        assertEquals(listOf(ids.subList(0, 2), ids.subList(2, 4), ids.subList(4, 5)), sender.batches)
        assertEquals(0, dao.pendingCountFlow().first())
    }

    @Test
    fun emptyQueueSendsNothing() = runTest {
        val sender = FakeSender()

        assertEquals(Uploader.Outcome.Done(0), uploader(sender).run())
        assertEquals(0, sender.batches.size)
    }

    @Test
    fun temporaryFailureKeepsItemsUntouchedForRetry() = runTest {
        enqueue(3)
        val sender = FakeSender(SendResult.Success, SendResult.Retry("ConnectException"))

        val outcome = uploader(sender, batchSize = 2).run()

        assertEquals(Uploader.Outcome.Retry(2, "ConnectException"), outcome)
        val left = dao.getPending(10).single()
        assertEquals(0, left.attempts)
        assertEquals(null, left.lastError)

        // Server back: the retry sends the rest
        assertEquals(Uploader.Outcome.Done(1), uploader(FakeSender(), batchSize = 2).run())
        assertEquals(0, dao.pendingCountFlow().first())
    }

    @Test
    fun rejectedBatchStopsTheRunAndCountsAnAttempt() = runTest {
        val ids = enqueue(3)
        val sender = FakeSender(SendResult.Rejected("HTTP 400"))

        val outcome = uploader(sender, batchSize = 2).run()

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
            uploader(FakeSender(SendResult.Rejected("HTTP 422")), batchSize = 1).run()
        }

        val sender = FakeSender()
        val outcome = uploader(sender, batchSize = 1).run()

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

        val removed = uploader(FakeSender(), now = { Uploader.KEEP_SENT_MS + 1 }).cleanUp()

        assertEquals(1, removed)
    }

    private companion object {
        const val AGREED = "drv-001"
    }
}
