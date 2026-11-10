package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts a batch as JSON to [baseUrl] + [PATH]. The server API is not fixed yet (server stage),
 * so the path and body shape live only here.
 *
 * Body: {"items":[{"queueId":1,"kind":"vehicle_event","createdAt":..,"payload":{..}}]}
 * The server should ignore a queueId it has already stored, since a batch can be sent twice
 * when the response is lost.
 */
class HttpUploadSender(private val baseUrl: String) : UploadSender {

    override fun send(items: List<UploadQueueEntity>): SendResult {
        val body = JSONObject().put(
            "items",
            JSONArray().apply {
                items.forEach {
                    put(
                        JSONObject()
                            .put("queueId", it.id)
                            .put("kind", it.kind)
                            .put("createdAt", it.createdAt)
                            .put("payload", JSONObject(it.payloadJson)),
                    )
                }
            },
        ).toString().toByteArray(Charsets.UTF_8)

        val connection = try {
            URL(baseUrl.trimEnd('/') + PATH).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return SendResult.Rejected("bad server url: ${e.javaClass.simpleName}")
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            when {
                code in 200..299 -> SendResult.Success
                code == 408 || code == 429 || code >= 500 -> SendResult.Retry("HTTP $code")
                else -> SendResult.Rejected("HTTP $code")
            }
        } catch (e: IOException) {
            SendResult.Retry(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PATH = "/api/v1/uploads"
        const val TIMEOUT_MS = 10_000
    }
}
