package com.bobcar.vehicle.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class IdTokenProviderTest {

    private data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    /** Answers from a script and records every call. */
    private class FakeHttp(vararg answers: () -> HttpResponse) : HttpTransport {
        val calls = mutableListOf<Call>()
        private val script = ArrayDeque(answers.toList())
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            calls += Call(method, url, headers, body?.toString(Charsets.UTF_8) ?: "")
            return script.removeFirst()()
        }
    }

    private class MemoryStore(var token: String? = null) : RefreshTokenStore {
        override fun load() = token
        override fun save(token: String) {
            this.token = token
        }
        override fun clear() {
            token = null
        }
    }

    private val emulator = ServerConfig("http://10.0.2.2:5001/demo-bobcar/asia-northeast3", "http://10.0.2.2:9099", "emulator", "veh-001", debug = true)
    private var clock = 1_000_000L

    private fun signUpOk(id: String = "id-1", refresh: String = "rt-1") =
        { HttpResponse(200, """{"idToken":"$id","refreshToken":"$refresh","expiresIn":"3600"}""") }

    private fun refreshOk(id: String, refresh: String = "rt-2") =
        { HttpResponse(200, """{"id_token":"$id","refresh_token":"$refresh","expires_in":"3600"}""") }

    @Test
    fun firstUseSignsUpAnonymouslyWithTheKeyInAHeader() {
        val http = FakeHttp(signUpOk())
        val store = MemoryStore()

        val result = IdTokenProvider(emulator, store, http) { clock }.get()

        assertEquals(IdTokenProvider.Result.Token("id-1"), result)
        assertEquals("rt-1", store.token)
        val call = http.calls.single()
        assertEquals("http://10.0.2.2:9099/identitytoolkit.googleapis.com/v1/accounts:signUp", call.url)
        assertFalse("no key or token in the URL", call.url.contains("key="))
        assertEquals("emulator", call.headers["X-Goog-Api-Key"])
        assertEquals("""{"returnSecureToken":true}""", call.body)
    }

    @Test
    fun cachedTokenIsReusedUntilShortlyBeforeItExpires() {
        val http = FakeHttp(signUpOk(), refreshOk("id-2"))
        val provider = IdTokenProvider(emulator, MemoryStore(), http) { clock }
        provider.get()

        clock += 50 * 60_000L
        assertEquals(IdTokenProvider.Result.Token("id-1"), provider.get())
        assertEquals(1, http.calls.size)

        clock += 6 * 60_000L // 56 min: inside the last 5 minutes
        assertEquals(IdTokenProvider.Result.Token("id-2"), provider.get())
        assertTrue(http.calls[1].url.endsWith("/securetoken.googleapis.com/v1/token"))
        assertEquals("grant_type=refresh_token&refresh_token=rt-1", http.calls[1].body)
    }

    @Test
    fun storedRefreshTokenIsUsedOnTheNextRun() {
        val http = FakeHttp(refreshOk("id-9", "rt-10"))
        val store = MemoryStore("rt-9")

        assertEquals(IdTokenProvider.Result.Token("id-9"), IdTokenProvider(emulator, store, http) { clock }.get())
        assertEquals("rt-10", store.token)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun forceRefreshAfterA401GetsANewToken() {
        val http = FakeHttp(signUpOk(), refreshOk("id-2"))
        val provider = IdTokenProvider(emulator, MemoryStore(), http) { clock }
        provider.get()

        assertEquals(IdTokenProvider.Result.Token("id-2"), provider.get(forceRefresh = true))
    }

    @Test
    fun signUpAgainForgetsTheStoredAccount() {
        val http = FakeHttp(signUpOk("id-1", "rt-1"), signUpOk("id-9", "rt-9"))
        val store = MemoryStore()
        val provider = IdTokenProvider(emulator, store, http) { clock }
        provider.get()

        assertEquals(IdTokenProvider.Result.Token("id-9"), provider.signUpAgain())
        assertEquals("rt-9", store.token)
        assertTrue(http.calls[1].url.endsWith("accounts:signUp"))
    }

    @Test
    fun unknownRefreshTokenLeadsToANewSignUp() {
        val http = FakeHttp(
            { HttpResponse(400, """{"error":{"message":"INVALID_REFRESH_TOKEN"}}""") },
            signUpOk("id-new", "rt-new"),
        )
        val store = MemoryStore("rt-old")

        assertEquals(IdTokenProvider.Result.Token("id-new"), IdTokenProvider(emulator, store, http) { clock }.get())
        assertEquals("rt-new", store.token)
    }

    @Test
    fun networkTroubleIsRetryableAndKeepsTheStoredToken() {
        val http = FakeHttp({ throw IOException("down") })
        val store = MemoryStore("rt-1")

        val result = IdTokenProvider(emulator, store, http) { clock }.get()

        assertTrue(result is IdTokenProvider.Result.Unavailable && result.retryable)
        assertEquals("rt-1", store.token)
    }

    @Test
    fun serverErrorIsRetryableAndAWrongKeyIsNot() {
        val down = IdTokenProvider(emulator, MemoryStore(), FakeHttp({ HttpResponse(503, "") })) { clock }.get()
        val wrongKey = IdTokenProvider(emulator, MemoryStore(), FakeHttp({ HttpResponse(400, """{"error":{"message":"API key not valid"}}""") })) { clock }.get()

        assertTrue((down as IdTokenProvider.Result.Unavailable).retryable)
        assertFalse((wrongKey as IdTokenProvider.Result.Unavailable).retryable)
    }

    @Test
    fun releaseUsesGoogleAuthOverHttps() {
        val real = ServerConfig("https://asia-northeast3-example.cloudfunctions.net", "", "web-key", "veh-001", debug = false)

        assertEquals("https://identitytoolkit.googleapis.com/v1/accounts:signUp", real.signUpUrl)
        assertEquals("https://securetoken.googleapis.com/v1/token", real.refreshUrl)
        assertEquals("https://asia-northeast3-example.cloudfunctions.net/api/v1/uploads", real.api("/uploads"))
        assertTrue(real.configured)
    }

    @Test
    fun releaseWithPlainHttpOrNoKeyIsNotConfigured() {
        assertFalse(ServerConfig("http://10.0.2.2:5001/x", "", "k", "veh-001", debug = false).configured)
        assertFalse(ServerConfig("https://example.com", "http://10.0.2.2:9099", "k", "veh-001", debug = false).configured)
        assertFalse(ServerConfig("https://example.com", "", "", "veh-001", debug = false).configured)
        assertFalse(ServerConfig("", "", "k", "veh-001", debug = true).configured)
        assertTrue(emulator.configured)
        assertNull(MemoryStore().load())
    }
}
