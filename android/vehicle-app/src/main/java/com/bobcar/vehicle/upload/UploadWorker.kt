package com.bobcar.vehicle.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bobcar.vehicle.data.BobCarDatabase
import com.bobcar.vehicle.data.DrivingRecorder
import com.bobcar.shared.server.ServerClients
import com.bobcar.vehicle.server.VehicleServer
import com.bobcar.vehicle.service.VehicleRuntime

/** Runs [Uploader] when the network is up. Logs counts and status only, never record contents. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!UploadScheduler.serverConfigured) {
            Log.i(TAG, "server not configured or not allowed, queue kept")
            return Result.success()
        }
        val db = BobCarDatabase.get(applicationContext)
        val sender = HttpUploadSender(VehicleServer.current, ServerClients.tokens(applicationContext, VehicleServer.current.config))
        val uploader = Uploader(
            db.uploadQueueDao(),
            db.driverConsentDao(),
            sender,
            resendConsent = { drivers -> drivers.forEach { DrivingRecorder.requeueConsent(db, it) } },
            // The driver card is open: this stay's records wait for the real driver
            holdSince = { VehicleRuntime.checkin.current?.uploadHoldSince },
        )
        val outcome = uploader.run()
        val removed = uploader.cleanUp()
        return when (outcome) {
            is Uploader.Outcome.Done -> {
                Log.i(TAG, "upload done sent=${outcome.sentCount} removedOld=$removed")
                UploadStatus.onSuccess(outcome.sentCount)
                Result.success()
            }
            is Uploader.Outcome.Retry -> {
                Log.w(TAG, "upload failed (${outcome.reason}) sent=${outcome.sentCount}, retry #${runAttemptCount + 1} later")
                UploadStatus.onFailure(outcome.reason)
                Result.retry()
            }
            is Uploader.Outcome.Rejected -> {
                Log.w(TAG, "upload rejected (${outcome.reason}) sent=${outcome.sentCount}, batch kept for the next request")
                UploadStatus.onFailure(outcome.reason)
                Result.success()
            }
        }
    }

    private companion object {
        const val TAG = "BOBCar.Upload"
    }
}
