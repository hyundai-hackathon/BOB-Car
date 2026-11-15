package com.bobcar.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bobcar.mobile.data.ApiResult
import com.bobcar.mobile.data.Driver
import com.bobcar.mobile.data.Me
import com.bobcar.mobile.data.PhoneApi
import com.bobcar.mobile.data.PhoneTrip
import com.bobcar.mobile.data.Problem
import com.bobcar.mobile.data.TripChange
import com.bobcar.mobile.data.VehicleStatus
import com.bobcar.shared.consent.ConsentNotice
import com.bobcar.shared.server.ApiCaller
import com.bobcar.shared.server.ServerClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Stage { LOADING, PICK, CONSENT, MAIN, ERROR }

enum class Tab { RECORDS, CHECK, VEHICLE }

data class UiState(
    val stage: Stage = Stage.LOADING,
    val me: Me? = null,
    val drivers: List<Driver> = emptyList(),
    val tab: Tab = Tab.RECORDS,
    val trips: List<PhoneTrip>? = null,
    val vehicle: VehicleStatus? = null,
    /** M3 open for this trip */
    val openTripId: String? = null,
    /** A call is running (buttons wait) */
    val busy: Boolean = false,
    /** Why the last call failed (LOADING / ERROR screens, and a line on the main screens) */
    val problem: Problem? = null,
    /** One-off note after an action ("저장했어요") */
    val notice: String? = null,
) {
    val toCheck: List<PhoneTrip> get() = trips.orEmpty().filter { it.check != null }
    val openTrip: PhoneTrip? get() = trips?.firstOrNull { it.tripDocId == openTripId }
}

/**
 * The phone app's state: first run (pick yourself, consent), then the three tabs and the trip detail.
 * Every server call runs on the IO dispatcher; logs carry status names only, never records or names.
 */
class AppModel(app: Application) : AndroidViewModel(app) {

    private val api = PhoneApi(ApiCaller(PhoneApi.config, ServerClients.tokens(app, PhoneApi.config)))

    /**
     * The person chosen on this phone (app-made driver ID only; app data is not backed up). When the sign-in
     * changes (the Auth emulator keeps no refresh tokens across a restart, or the stored token is lost), the
     * server no longer knows this phone and the same person is linked again without asking.
     */
    private val chosen = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        start()
    }

    /**
     * Who is this phone? Not linked → the remembered person again, or the list to pick from;
     * not agreed → consent; else the records.
     */
    fun start() = launch(showBusy = false) {
        _state.update { it.copy(stage = Stage.LOADING, problem = null) }
        when (val me = io { api.me() }) {
            is ApiResult.Ok -> enter(me.value)
            is ApiResult.Failed -> if (me.problem == Problem.NOT_LINKED) relinkOrPick() else fail(me.problem)
        }
    }

    fun pick(driverId: String) = launch {
        when (val me = io { api.link(driverId) }) {
            is ApiResult.Ok -> {
                chosen.edit().putString(KEY_DRIVER, driverId).apply()
                enter(me.value)
            }
            is ApiResult.Failed -> _state.update { it.copy(problem = me.problem) }
        }
    }

    private suspend fun relinkOrPick() {
        val remembered = chosen.getString(KEY_DRIVER, null) ?: return showPick()
        when (val me = io { api.link(remembered) }) {
            is ApiResult.Ok -> {
                Log.i(TAG, "linked again to the remembered person")
                enter(me.value)
            }
            is ApiResult.Failed -> {
                // The person is gone from the staff list (or the server refuses): ask again
                if (me.problem != Problem.OFFLINE) chosen.edit().remove(KEY_DRIVER).apply()
                if (me.problem == Problem.OFFLINE) fail(me.problem) else showPick()
            }
        }
    }

    /** Back to the list of names (from the consent screen: "다른 사람으로 다시 선택"). */
    fun choose() = launch { showPick() }

    fun agree() = launch {
        when (val me = io { api.agree(ConsentNotice.VERSION) }) {
            is ApiResult.Ok -> enter(me.value)
            is ApiResult.Failed -> _state.update { it.copy(problem = me.problem) }
        }
    }

    fun selectTab(tab: Tab) {
        _state.update { it.copy(tab = tab, openTripId = null) }
        refresh()
    }

    fun open(tripDocId: String) = _state.update { it.copy(openTripId = tripDocId, notice = null) }

    fun close() = _state.update { it.copy(openTripId = null) }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    /** Trips and the car again (tab change, resume, the refresh button). */
    fun refresh() = launch(showBusy = false) {
        if (_state.value.stage != Stage.MAIN) return@launch
        val trips = io { api.trips() }
        val vehicle = io { api.vehicle() }
        if (trips is ApiResult.Failed && trips.problem == Problem.NO_CONSENT) {
            _state.update { it.copy(stage = Stage.CONSENT) }
            return@launch
        }
        _state.update {
            it.copy(
                trips = (trips as? ApiResult.Ok)?.value ?: it.trips,
                vehicle = (vehicle as? ApiResult.Ok)?.value ?: it.vehicle,
                problem = (trips as? ApiResult.Failed)?.problem ?: (vehicle as? ApiResult.Failed)?.problem,
            )
        }
    }

    /** Drivers for "다른 사람" (loaded once). */
    fun loadDrivers() = launch(showBusy = false) {
        if (_state.value.drivers.isNotEmpty()) return@launch
        (io { api.drivers() } as? ApiResult.Ok)?.let { list -> _state.update { it.copy(drivers = list.value) } }
    }

    /**
     * Saves one change. The trip comes back as the server now has it; one that is no longer mine (handed to
     * someone else) leaves the list, and the detail screen closes.
     */
    fun save(tripDocId: String, change: TripChange, closeAfter: Boolean = false) = launch {
        when (val result = io { api.correct(tripDocId, change) }) {
            is ApiResult.Ok -> {
                val trip = result.value
                val me = _state.value.me?.driverId
                val stillMine = trip.driverId == me || trip.check != null
                _state.update { s ->
                    s.copy(
                        trips = s.trips?.mapNotNull { if (it.tripDocId == tripDocId) trip.takeIf { stillMine } else it },
                        openTripId = if (closeAfter || !stillMine) null else s.openTripId,
                        notice = if (stillMine) "저장했어요" else "${trip.driverName ?: "다른 사람"} 님에게 확인을 요청했어요",
                        problem = null,
                    )
                }
                Log.i(TAG, "trip saved")
                refresh()
            }
            is ApiResult.Failed -> {
                Log.w(TAG, "save failed: ${result.problem}")
                _state.update { it.copy(problem = result.problem) }
            }
        }
    }

    private suspend fun enter(me: Me) {
        _state.update { it.copy(me = me, problem = null, stage = if (me.agreed) Stage.MAIN else Stage.CONSENT) }
        if (me.agreed) refresh()
    }

    private suspend fun showPick() {
        when (val drivers = io { api.drivers() }) {
            is ApiResult.Ok -> _state.update { it.copy(stage = Stage.PICK, drivers = drivers.value, problem = null) }
            is ApiResult.Failed -> fail(drivers.problem)
        }
    }

    private fun fail(problem: Problem) {
        Log.w(TAG, "start failed: $problem")
        _state.update { it.copy(stage = Stage.ERROR, problem = problem) }
    }

    private fun launch(showBusy: Boolean = true, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (showBusy) _state.update { it.copy(busy = true) }
            try {
                block()
            } finally {
                if (showBusy) _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun <T> io(call: () -> T): T = withContext(Dispatchers.IO) { call() }

    private companion object {
        const val TAG = "BOBCar.Phone"
        const val PREFS = "bobcar_phone"
        const val KEY_DRIVER = "chosen_driver_id"
    }
}
