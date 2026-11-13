package com.bobcar.vehicle.upload

import com.bobcar.vehicle.data.UploadQueueEntity
import com.bobcar.vehicle.server.HttpResponse
import com.bobcar.vehicle.server.HttpTransport
import com.bobcar.vehicle.server.IdTokenProvider
import com.bobcar.vehicle.server.RefreshTokenStore
import com.bobcar.vehicle.server.ServerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class HttpUploadSenderTest {

    private data class Call(val url: String, val headers: Map<String, String>, val body: String)

    /** Auth calls get a fresh token each time ("id-1", "id-2", ...); upload calls answer from [uploadAnswers]. */
    private class FakeServer(vararg uploadAnswers: () -> HttpResponse) : HttpTransport {
        val uploads = mutableListOf<Call>()
        private var tokens = 0
        private val script = ArrayDeque(uploadAnswers.toList())
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (url.contains("googleapis.com")) {
                tokens++
                return HttpResponse(200, """{"idToken":"id-$tokens","refreshToken":"rt","expiresIn":"3600","id_token":"id-$tokens","refresh_token":"rt","expires_in":"3600"}""")
            }
            uploads += Call(url, headers, body?.toString(Charsets.UTF_8) ?: "")
            return script.removeFirst()()
        }
    }

    private class MemoryStore : RefreshTokenStore {
        var token: String? = null
        override fun load() = token
        override fun save(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private val config = ServerConfig("http://10.0.2.2:5001/demo-bobcar/asia-northeast3", "http://10.0.2.2:9099", "emulator", "veh-001", debug = true)

    private fun sender(server: FakeServer) = HttpUploadSender(config, IdTokenProvider(config, MemoryStore(), server), server)

    private val items = listOf(
        UploadQueueEntity(id = 11, kind = "vehicle_event", payloadJson = """{"eventId":5,"type":"Boarded","occurredAt":100,"detail":null}""", createdAt = 100, driverId = "drv-001"),
        UploadQueueEntity(id = 12, kind = "trip", payloadJson = """{"tripId":3,"status":"ACTIVE","startedAt":100}""", createdAt = 101, driverId = "drv-001"),
    )

    @Test
    fun postsTheBatchWithVehicleAndDriverAndTheTokenInAHeader() {
        val server = FakeServer({ HttpResponse(200, """{"stored":[11,12],"duplicates":[],"skipped":[]}""") })

        assertEquals(SendResult.Success(), sender(server).send(items))

        val call = server.uploads.single()
        assertEquals("http://10.0.2.2:5001/demo-bobcar/asia-northeast3/api/v1/uploads", call.url)
        assertEquals("Bearer id-1", call.headers["Authorization"])
        assertFalse("no token in the URL", call.url.contains("id-1"))
        val body = JSONObject(call.body)
        assertEquals("veh-001", body.getString("vehicleId"))
        val first = body.getJSONArray("items").getJSONObject(0)
        assertEquals(11, first.getLong("queueId"))
        assertEquals("vehicle_event", first.getString("kind"))
        assertEquals("drv-001", first.getString("driverId"))
        assertEquals("Boarded", first.getJSONObject("payload").getString("type"))
        assertEquals("trip", body.getJSONArray("items").getJSONObject(1).getString("kind"))
    }

    @Test
    fun skippedItemsComeBackWithTheirReason() {
        val server = FakeServer({ HttpResponse(200, """{"stored":[11],"duplicates":[],"skipped":[{"queueId":12,"reason":"no-consent"},{"queueId":null,"reason":"invalid"}]}""") })

        assertEquals(SendResult.Success(mapOf(12L to "no-consent")), sender(server).send(items))
    }

    @Test
    fun a401IsTriedOnceMoreWithAFreshToken() {
        val server = FakeServer({ HttpResponse(401, """{"error":"invalid token"}""") }, { HttpResponse(200, """{"skipped":[]}""") })

        assertEquals(SendResult.Success(), sender(server).send(items))
        assertEquals(listOf("Bearer id-1", "Bearer id-2"), server.uploads.map { it.headers["Authorization"] })
    }

    @Test
    fun a401TwiceIsRetryLaterNotARejection() {
        val server = FakeServer({ HttpResponse(401, "") }, { HttpResponse(401, "") })

        assertEquals(SendResult.Retry("HTTP 401"), sender(server).send(items))
    }

    @Test
    fun serverAndNetworkTroubleIsRetryAndOtherRefusalsAreRejections() {
        assertEquals(SendResult.Retry("HTTP 503"), sender(FakeServer({ HttpResponse(503, "") })).send(items))
        assertEquals(SendResult.Retry("ConnectException"), sender(FakeServer({ throw java.net.ConnectException() })).send(items))
        assertEquals(SendResult.Rejected("HTTP 403"), sender(FakeServer({ HttpResponse(403, """{"error":"vehicle is not registered"}""") })).send(items))
    }

    @Test
    fun signInTroubleIsRetryAndNothingIsPosted() {
        val down = object : HttpTransport {
            var uploads = 0
            override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                if (url.contains("googleapis.com")) throw IOException("auth down")
                uploads++
                return HttpResponse(200, "{}")
            }
        }
        val result = HttpUploadSender(config, IdTokenProvider(config, MemoryStore(), down), down).send(items)

        assertTrue(result is SendResult.Retry)
        assertEquals(0, down.uploads)
    }
}
