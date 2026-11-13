package com.bobcar.vehicle.server

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Reads from the server (server/functions/src/app.ts): this vehicle's baseline, its reservations and the
 * driver list. Each call returns null when the server cannot answer (off, offline, sign-in trouble);
 * the app then keeps using its local values. Blocking; call off the main thread.
 */
class ServerApi(
    private val config: ServerConfig,
    private val tokens: IdTokenProvider,
    private val http: HttpTransport = UrlConnectionTransport,
) {
    /** Per-vehicle baseline kept by the server. Missing values are null. */
    data class Baseline(
        val lastOdometerKm: Float?,
        val lastSocPercent: Int?,
        val lastTripEndedAt: Long?,
    )

    data class Reservation(val driverId: String, val startAt: Long, val endAt: Long)

    data class Driver(val id: String, val name: String)

    fun baseline(): Baseline? = get("/vehicles/${config.vehicleId}")?.let { json ->
        Baseline(
            lastOdometerKm = json.optNumber("lastOdometerKm")?.toFloat(),
            lastSocPercent = json.optNumber("lastSocPercent")?.toInt(),
            lastTripEndedAt = json.optNumber("lastTripEndedAt")?.toLong(),
        )
    }

    /** Reservations of this vehicle overlapping [from, to). */
    fun reservations(from: Long, to: Long): List<Reservation>? =
        get("/reservations?vehicleId=${config.vehicleId}&from=$from&to=$to")?.let { json ->
            val items = json.optJSONArray("items") ?: return@let emptyList()
            (0 until items.length()).mapNotNull { i ->
                val r = items.getJSONObject(i)
                val driverId = r.optString("driverId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Reservation(driverId, r.optLong("startAt"), r.optLong("endAt"))
            }
        }

    fun drivers(): List<Driver>? = get("/drivers")?.let { json ->
        val items = json.optJSONArray("items") ?: return@let emptyList()
        (0 until items.length()).mapNotNull { i ->
            val d = items.getJSONObject(i)
            val id = d.optString("driverId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Driver(id, d.optString("name", id))
        }
    }

    private fun get(path: String): JSONObject? {
        if (!config.configured) return null
        var response = request(path) { tokens.get() } ?: return null
        if (response.code == 401) response = request(path) { tokens.get(forceRefresh = true) } ?: return null
        if (response.code == 401) response = request(path) { tokens.signUpAgain() } ?: return null
        if (response.code !in 200..299) return null
        return try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            null
        }
    }

    private fun request(path: String, token: () -> IdTokenProvider.Result): HttpResponse? {
        val idToken = (token() as? IdTokenProvider.Result.Token)?.idToken ?: return null
        return try {
            http.send("GET", config.api(path), mapOf("Authorization" to "Bearer $idToken"), null)
        } catch (e: IOException) {
            null
        }
    }

    private fun JSONObject.optNumber(name: String): Number? =
        if (isNull(name)) null else (opt(name) as? Number)
}
