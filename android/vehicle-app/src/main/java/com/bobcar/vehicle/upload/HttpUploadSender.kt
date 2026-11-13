package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueEntity
import com.bobcar.vehicle.server.HttpTransport
import com.bobcar.vehicle.server.IdTokenProvider
import com.bobcar.vehicle.server.ServerConfig
import com.bobcar.vehicle.server.UrlConnectionTransport
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * POST <server>/api/v1/uploads with the ID token in the Authorization header (never in the URL).
 *
 * Body: {"vehicleId":"veh-001","items":[{"queueId":1,"kind":"vehicle_event","createdAt":..,
 *        "driverId":"drv-001","payload":{..}}]}
 * Answer 200: {"stored":[..],"duplicates":[..],"skipped":[{"queueId":3,"reason":"no-consent"}]}
 * (server/functions/src/uploads.ts). A resent item is a duplicate on the server, never stored twice.
 *
 * A 401 gets one more try with a refreshed token, then one with a new anonymous account (the server no longer
 * knows the old one, e.g. emulator data wiped). Sign-in trouble is a Retry: items keep their attempts.
 */
class HttpUploadSender(
    private val config: ServerConfig,
    private val tokens: IdTokenProvider,
    private val http: HttpTransport = UrlConnectionTransport,
) : UploadSender {

    override fun send(items: List<UploadQueueEntity>): SendResult {
        val body = body(items)
        var answer = post(body) { tokens.get() }
        if (answer is Answer.Unauthorized) answer = post(body) { tokens.get(forceRefresh = true) }
        if (answer is Answer.Unauthorized) answer = post(body) { tokens.signUpAgain() }
        return when (answer) {
            is Answer.Done -> answer.result
            Answer.Unauthorized -> SendResult.Retry("HTTP 401")
        }
    }

    private sealed interface Answer {
        data class Done(val result: SendResult) : Answer
        data object Unauthorized : Answer
    }

    private fun post(body: ByteArray, token: () -> IdTokenProvider.Result): Answer {
        val idToken = when (val t = token()) {
            is IdTokenProvider.Result.Token -> t.idToken
            is IdTokenProvider.Result.Unavailable -> return Answer.Done(SendResult.Retry(t.reason))
        }
        val response = try {
            http.send(
                "POST",
                config.api(PATH),
                mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "Authorization" to "Bearer $idToken",
                ),
                body,
            )
        } catch (e: IOException) {
            return Answer.Done(SendResult.Retry(e.javaClass.simpleName))
        }
        val code = response.code
        return when {
            code == 401 -> Answer.Unauthorized
            code in 200..299 -> Answer.Done(SendResult.Success(skipped(response.body)))
            // 404: the API is not there yet (server still starting, or not deployed): nothing wrong with the items
            code == 404 || code == 408 || code == 429 || code >= 500 -> Answer.Done(SendResult.Retry("HTTP $code"))
            else -> Answer.Done(SendResult.Rejected("HTTP $code"))
        }
    }

    private fun body(items: List<UploadQueueEntity>): ByteArray =
        JSONObject()
            .put("vehicleId", config.vehicleId)
            .put(
                "items",
                JSONArray().apply {
                    items.forEach {
                        put(
                            JSONObject()
                                .put("queueId", it.id)
                                .put("kind", it.kind)
                                .put("createdAt", it.createdAt)
                                .put("driverId", it.driverId ?: JSONObject.NULL)
                                .put("payload", JSONObject(it.payloadJson)),
                        )
                    }
                },
            )
            .toString()
            .toByteArray(Charsets.UTF_8)

    /** Queue IDs the server did not store, with its reason. An answer it cannot read counts as all done. */
    private fun skipped(body: String): Map<Long, String> = try {
        val list = JSONObject(body).optJSONArray("skipped") ?: return emptyMap()
        buildMap {
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                if (entry.isNull("queueId")) continue
                put(entry.getLong("queueId"), entry.optString("reason", "unknown"))
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    private companion object {
        const val PATH = "/uploads"
    }
}
