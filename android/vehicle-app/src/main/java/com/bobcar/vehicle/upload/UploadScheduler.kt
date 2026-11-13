package com.bobcar.vehicle.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bobcar.vehicle.server.ServerConfig
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

    /** A server is set and allowed for this build (release: HTTPS only), with what sign-in needs. */
    val serverConfigured: Boolean get() = ServerConfig.current.configured

    @Volatile private var loggedNotConfigured = false

    fun request(context: Context) {
        if (!serverConfigured) {
            if (!loggedNotConfigured) {
                loggedNotConfigured = true
                if (ServerConfig.current.serverUrl.isEmpty()) Log.i(TAG, "server not configured, records stay in the upload queue")
                else Log.w(TAG, "server or sign-in not allowed for this build (release: HTTPS only, API key needed); records stay in the upload queue")
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

/**
 * Whether records may be sent to [url] (CLAUDE.md rule 6-1).
 * Release: HTTPS only. Debug: HTTP is also accepted here; network_security_config still allows plain HTTP
 * only to the PC's local server (10.0.2.2). Empty = no server yet.
 */
internal fun canSendTo(url: String, debug: Boolean): Boolean {
    val u = url.trim().lowercase()
    return when {
        u.startsWith("https://") -> u.length > "https://".length
        u.startsWith("http://") -> debug && u.length > "http://".length
        else -> false
    }
}
