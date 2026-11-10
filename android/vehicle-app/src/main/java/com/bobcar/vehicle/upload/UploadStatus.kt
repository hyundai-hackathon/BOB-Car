package com.bobcar.vehicle.upload

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Last upload result, for the screen. Process memory only; the queue itself is in the DB. */
object UploadStatus {

    data class State(
        val serverConfigured: Boolean = UploadScheduler.serverConfigured,
        /** Wall-clock time of the last run that finished without error. */
        val lastSuccessAt: Long? = null,
        val lastSentCount: Int = 0,
        val lastFailureAt: Long? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onSuccess(sentCount: Int) = _state.update {
        it.copy(lastSuccessAt = System.currentTimeMillis(), lastSentCount = sentCount)
    }

    fun onFailure(reason: String) = _state.update {
        it.copy(lastFailureAt = System.currentTimeMillis(), lastError = reason)
    }
}
