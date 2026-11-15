package com.bobcar.mobile.data

import com.bobcar.shared.server.ApiCaller
import com.bobcar.shared.server.HttpResponse
import com.bobcar.shared.server.HttpTransport
import com.bobcar.shared.server.IdTokenProvider
import com.bobcar.shared.server.RefreshTokenStore
import com.bobcar.shared.server.ServerConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PhoneApiTest {

    private data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Sign-in always works; API calls answer from [answers] in order. */
    private class FakeServer(vararg answers: () -> HttpResponse) : HttpTransport {
        val calls = mutableListOf<Call>()
        private val script = ArrayDeque(answers.toList())
        private var tokens = 0
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (url.contains("googleapis.com")) {
                tokens++
                return HttpResponse(200, """{"idToken":"id-$tokens","refreshToken":"rt","expiresIn":"3600","id_token":"id-$tokens","refresh_token":"rt","expires_in":"3600"}""")
            }
            calls += Call(method, url, headers, body?.toString(Charsets.UTF_8))
            return script.removeFirst()()
        }
    }

    private class MemoryStore : RefreshTokenStore {
        var token: String? = null
        override fun load() = token
        override fun save(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    private val config = ServerConfig("http://10.0.2.2:5001/demo-bobcar/asia-northeast3", "http://10.0.2.2:9099", "emulator", debug = true)

    private fun api(server: HttpTransport) = PhoneApi(ApiCaller(config, IdTokenProvider(config, MemoryStore(), server), server))

    @Test
    fun linkSendsTheChosenDriverWithTheTokenInTheHeader() {
        val server = FakeServer({ HttpResponse(200, """{"driverId":"drv-001","name":"김민준","department":"영업팀","agreed":true}""") })

        val result = api(server).link("drv-001")

        assertEquals(ApiResult.Ok(Me("drv-001", "김민준", "영업팀", agreed = true)), result)
        val call = server.calls.single()
        assertEquals("POST", call.method)
        assertEquals("http://10.0.2.2:5001/demo-bobcar/asia-northeast3/api/v1/phone/link", call.url)
        assertEquals("Bearer id-1", call.headers["Authorization"])
        assertEquals("drv-001", JSONObject(call.body!!).getString("driverId"))
    }

    @Test
    fun serverAnswersBecomeProblems() {
        fun meWith(code: Int, body: String) = api(FakeServer({ HttpResponse(code, body) })).me()

        assertEquals(ApiResult.Failed(Problem.NOT_LINKED), meWith(404, """{"error":"phone not linked"}"""))
        assertEquals(ApiResult.Failed(Problem.NO_CONSENT), meWith(403, """{"error":"consent required"}"""))
        assertEquals(ApiResult.Failed(Problem.REFUSED), meWith(403, """{"error":"not your trip"}"""))
        assertEquals(ApiResult.Failed(Problem.OFFLINE), meWith(503, ""))
        assertEquals(ApiResult.Failed(Problem.SERVER), meWith(200, "not json"))
        val down = object : HttpTransport {
            override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                if (url.contains("googleapis.com")) HttpResponse(200, """{"idToken":"id","refreshToken":"rt","expiresIn":"3600"}""") else throw IOException("down")
        }
        assertEquals(ApiResult.Failed(Problem.OFFLINE), api(down).me())
    }

    @Test
    fun a401IsRetriedWithAFreshToken() {
        val server = FakeServer(
            { HttpResponse(401, """{"error":"invalid token"}""") },
            { HttpResponse(200, """{"driverId":"drv-001","name":"김민준","agreed":false}""") },
        )

        val result = api(server).me()

        assertTrue(result is ApiResult.Ok)
        assertEquals(listOf("Bearer id-1", "Bearer id-2"), server.calls.map { it.headers["Authorization"] })
    }

    @Test
    fun releaseWithoutAServerSendsNothing() {
        val none = ServerConfig("", "", "", debug = false)
        val server = FakeServer()

        val result = PhoneApi(ApiCaller(none, IdTokenProvider(none, MemoryStore(), server), server)).me()

        assertEquals(ApiResult.Failed(Problem.NOT_CONFIGURED), result)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun tripsAreRead() {
        val server = FakeServer({
            HttpResponse(
                200,
                """{"items":[
                  {"tripDocId":"veh-001_unrecorded_veh-001_9","vehicleId":"veh-001","status":"UNRECORDED","startedAt":1000,"endedAt":2000,
                   "startKm":12358.4,"endKm":12364.4,"distanceKm":6,"destination":null,"expectedTollFare":null,"purpose":null,
                   "purposeProvisional":false,"driverId":null,"driverName":null,"driverProvisional":false,"memo":null,"closedByServer":false,
                   "assignedByName":null,"candidates":[{"driverId":"drv-002","name":"이서연"}],"check":"UNRECORDED"},
                  {"tripDocId":"veh-001_3","vehicleId":"veh-001","status":"ENDED","startedAt":500,"endedAt":900,"distanceKm":18.4,
                   "destination":"판교 테크노밸리","expectedTollFare":2400,"purpose":"BUSINESS","purposeProvisional":false,
                   "driverId":"drv-001","driverName":"김민준","driverProvisional":true,"memo":"고객 미팅","check":"DRIVER","candidates":[]}
                ]}""",
            )
        })

        val trips = (api(server).trips() as ApiResult.Ok).value

        assertEquals(2, trips.size)
        assertTrue(trips[0].unrecorded)
        assertEquals(Check.UNRECORDED, trips[0].check)
        assertEquals(listOf(Driver("drv-002", "이서연", null)), trips[0].candidates)
        assertNull(trips[0].purpose)
        assertEquals(Purpose.BUSINESS, trips[1].purpose)
        assertEquals(2400, trips[1].expectedTollFare)
        assertEquals("고객 미팅", trips[1].memo)
        assertEquals(Check.DRIVER, trips[1].check)
    }

    @Test
    fun aCorrectionSendsOnlyWhatChanged() {
        val server = FakeServer({
            HttpResponse(200, """{"tripDocId":"veh-001_3","status":"ENDED","startedAt":500,"purpose":"PERSONAL","driverId":"drv-001","candidates":[]}""")
        })

        api(server).correct("veh-001_3", TripChange(purpose = Purpose.PERSONAL, memo = "", confirm = true))

        val call = server.calls.single()
        assertTrue(call.url.endsWith("/api/v1/phone/trips/veh-001_3/correction"))
        val body = JSONObject(call.body!!)
        assertEquals("PERSONAL", body.getString("purpose"))
        assertEquals("", body.getString("memo"))
        assertTrue(body.getBoolean("confirm"))
        assertTrue(!body.has("driverId"))
    }
}
