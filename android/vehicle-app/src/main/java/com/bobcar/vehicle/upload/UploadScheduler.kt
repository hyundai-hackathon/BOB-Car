package com.bobcar.vehicle.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bobcar.vehicle.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Asks WorkManager to drain the upload queue. Called after each stored record and when collection
 * starts (to send what piled up while the app or server was off).
 *
 * Requests are merged into one unique job (KEEP), so calling it for every event costs nothing extra.
 * With no server URL nothing is scheduled and records simply wait in the queue.
 */
object UploadScheduler {

    private const val TAG = "BOBCar.Upload"
    private const val WORK_NAME = "upload-queue"
    private const val BACKOFF_SECONDS = 30L

    val serverUrl: String = BuildConfig.SERVER_URL.trim()
    val serverConfigured: Boolean get() = serverUrl.isNotEmpty()

    @Volatile private var loggedNotConfigured = false

    fun request(context: Context) {
        if (!serverConfigured) {
            if (!loggedNotConfigured) {
                loggedNotConfigured = true
                Log.i(TAG, "server not configured, records stay in the upload queue")
            }
            return
        }
        val work = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, work)
    }
}
