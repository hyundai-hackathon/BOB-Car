package com.bobcar.mobile.data

import com.bobcar.mobile.BuildConfig
import com.bobcar.shared.server.ApiCaller
import com.bobcar.shared.server.ServerConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Why a call gave no result. The screens turn it into a message. */
enum class Problem {
    /** No network, or the server is off */
    OFFLINE,
    /** This build has no server (release before a real HTTPS server exists) */
    NOT_CONFIGURED,
    /** First run: nobody chosen yet on this phone */
    NOT_LINKED,
    /** The driver has not agreed to collection */
    NO_CONSENT,
    /** Not this driver's trip, or a trip that cannot be changed now */
    REFUSED,
    SERVER,
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(val problem: Problem) : ApiResult<Nothing>
}

/**
 * The phone app's server calls (server/functions/src/phone.ts, /api/v1/phone/...).
 * Blocking; call off the main thread. Answers are read leniently: a missing value is null.
 */
class PhoneApi(private val caller: ApiCaller) {

    fun drivers(): ApiResult<List<Driver>> = get("/drivers") { json ->
        json.optJSONArray("items").objects().mapNotNull { d ->
            val id = d.str("driverId") ?: return@mapNotNull null
            Driver(id, d.str("name") ?: id, d.str("department"))
        }
    }

    fun me(): ApiResult<Me> = get("/phone/me", ::me)

    fun link(driverId: String): ApiResult<Me> = post("/phone/link", JSONObject().put("driverId", driverId), ::me)

    fun agree(noticeVersion: Int): ApiResult<Me> = post("/phone/consent", JSONObject().put("noticeVersion", noticeVersion), ::me)

    fun trips(): ApiResult<List<PhoneTrip>> = get("/phone/trips") { json -> json.optJSONArray("items").objects().mapNotNull(::trip) }

    fun correct(tripDocId: String, change: TripChange): ApiResult<PhoneTrip> {
        val body = JSONObject()
        change.driverId?.let { body.put("driverId", it) }
        change.purpose?.let { body.put("purpose", it.name) }
        change.memo?.let { body.put("memo", it) }
        if (change.confirm) body.put("confirm", true)
        return post("/phone/trips/${segment(tripDocId)}/correction", body) { trip(it) ?: throw JSONException("no trip") }
    }

    fun vehicle(): ApiResult<VehicleStatus> = get("/phone/vehicle") { json ->
        val v = json.getJSONObject("vehicle")
        val charge = json.optJSONObject("lastCharge")
        VehicleStatus(
            vehicleId = v.getString("vehicleId"),
            name = v.str("name"),
            model = v.str("model"),
            plate = v.str("plate"),
            driving = json.str("state") == "DRIVING",
            socPercent = json.dbl("socPercent")?.toInt(),
            lastCharge = charge?.let { LastCharge(it.getLong("at"), it.dbl("fromPercent")?.toInt(), it.dbl("toPercent")?.toInt()) },
            odometerKm = json.dbl("odometerKm"),
            monthKm = json.dbl("monthKm") ?: 0.0,
            businessRatioPercent = json.dbl("businessRatioPercent"),
            lastDriverName = json.str("lastDriverName"),
            lastUploadAt = json.dbl("lastUploadAt")?.toLong(),
        )
    }

    // ---------------------------------------------------------------- reading answers

    private fun me(json: JSONObject) = Me(
        driverId = json.getString("driverId"),
        name = json.str("name") ?: json.getString("driverId"),
        department = json.str("department"),
        agreed = json.optBoolean("agreed"),
    )

    private fun trip(t: JSONObject): PhoneTrip? {
        val id = t.str("tripDocId") ?: return null
        return PhoneTrip(
            tripDocId = id,
            vehicleId = t.str("vehicleId") ?: "",
            status = t.str("status") ?: return null,
            startedAt = t.dbl("startedAt")?.toLong() ?: return null,
            endedAt = t.dbl("endedAt")?.toLong(),
            startKm = t.dbl("startKm"),
            endKm = t.dbl("endKm"),
            distanceKm = t.dbl("distanceKm"),
            destination = t.str("destination"),
            expectedTollFare = t.dbl("expectedTollFare")?.toInt(),
            purpose = Purpose.of(t.str("purpose")),
            purposeProvisional = t.optBoolean("purposeProvisional"),
            driverId = t.str("driverId"),
            driverName = t.str("driverName"),
            driverProvisional = t.optBoolean("driverProvisional"),
            memo = t.str("memo"),
            closedByServer = t.optBoolean("closedByServer"),
            assignedByName = t.str("assignedByName"),
            candidates = t.optJSONArray("candidates").objects().mapNotNull { c ->
                val cid = c.str("driverId") ?: return@mapNotNull null
                Driver(cid, c.str("name") ?: cid, null)
            },
            check = t.str("check")?.let { c -> Check.entries.firstOrNull { it.name == c } },
        )
    }

    // ---------------------------------------------------------------- calls

    private fun <T> get(path: String, read: (JSONObject) -> T): ApiResult<T> = run("GET", path, null, read)

    private fun <T> post(path: String, body: JSONObject, read: (JSONObject) -> T): ApiResult<T> = run("POST", path, body.toString(), read)

    private fun <T> run(method: String, path: String, body: String?, read: (JSONObject) -> T): ApiResult<T> {
        val response = when (val outcome = caller.call(method, path, body)) {
            is ApiCaller.Outcome.Unreachable -> return ApiResult.Failed(if (outcome.retryable) Problem.OFFLINE else Problem.NOT_CONFIGURED)
            is ApiCaller.Outcome.Answered -> outcome.response
        }
        val error = runCatching { JSONObject(response.body).optString("error") }.getOrDefault("")
        return when {
            response.code in 200..299 -> try {
                ApiResult.Ok(read(JSONObject(response.body)))
            } catch (e: JSONException) {
                ApiResult.Failed(Problem.SERVER)
            }
            response.code == 404 && error == "phone not linked" -> ApiResult.Failed(Problem.NOT_LINKED)
            response.code == 403 && error == "consent required" -> ApiResult.Failed(Problem.NO_CONSENT)
            response.code == 403 || response.code == 409 -> ApiResult.Failed(Problem.REFUSED)
            // 404 from a server still starting (function not loaded yet) is a "try again"
            response.code == 404 && error.isEmpty() -> ApiResult.Failed(Problem.OFFLINE)
            response.code >= 500 -> ApiResult.Failed(Problem.OFFLINE)
            else -> ApiResult.Failed(Problem.SERVER)
        }
    }

    private fun segment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        /** This build's server (debug: the PC's emulators at 10.0.2.2; release: none until a real HTTPS one is set). */
        val config: ServerConfig by lazy {
            ServerConfig(
                serverUrl = BuildConfig.SERVER_URL.trim(),
                authUrl = BuildConfig.AUTH_URL.trim(),
                apiKey = BuildConfig.FIREBASE_API_KEY.trim(),
                debug = BuildConfig.DEBUG,
            )
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject.dbl(name: String): Double? = if (isNull(name)) null else (opt(name) as? Number)?.toDouble()
