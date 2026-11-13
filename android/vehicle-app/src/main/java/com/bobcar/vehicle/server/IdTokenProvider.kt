package com.bobcar.vehicle.server

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Keeps the refresh token between app runs (encrypted on the device, see [KeystoreTokenStore]). */
interface RefreshTokenStore {
    fun load(): String?
    fun save(token: String)
    fun clear()
}

/**
 * Firebase anonymous sign-in over the Auth REST API (no Firebase SDK: the car may have no Google Play
 * services). The ID token goes in the Authorization header of every server call (CLAUDE.md rule 6-1).
 *
 * - First use: sign up anonymously → ID token (about 1 hour) + refresh token
 * - Later: the stored refresh token gives a new ID token; if the Auth side no longer knows it (emulator
 *   data wiped, account removed), sign up again
 * - The API key goes in the X-Goog-Api-Key header, never in a URL. Tokens are never logged.
 *
 * Blocking; call from a background thread. Calls are serialized.
 */
class IdTokenProvider(
    private val config: ServerConfig,
    private val store: RefreshTokenStore,
    private val http: HttpTransport = UrlConnectionTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Result {
        data class Token(val idToken: String) : Result

        /** No token now. [retryable] = network or server trouble; false = the setup is wrong. */
        data class Unavailable(val reason: String, val retryable: Boolean) : Result
    }

    private var idToken: String? = null
    private var expiresAt = 0L

    /** A valid ID token; [forceRefresh] after the server answered 401 with the cached one. */
    @Synchronized
    fun get(forceRefresh: Boolean = false): Result {
        val cached = idToken
        if (!forceRefresh && cached != null && now() < expiresAt - EARLY_REFRESH_MS) return Result.Token(cached)
        return try {
            store.load()?.let { refresh(it) } ?: signUp()
        } catch (e: IOException) {
            Result.Unavailable("auth unreachable: ${e.javaClass.simpleName}", retryable = true)
        }
    }

    /**
     * The server refused even a freshly refreshed token: the account behind the stored refresh token is gone
     * on the server side (e.g. emulator data wiped while the Auth refresh still works). Forget it and sign up again.
     */
    @Synchronized
    fun signUpAgain(): Result {
        store.clear()
        idToken = null
        expiresAt = 0
        return try {
            signUp()
        } catch (e: IOException) {
            Result.Unavailable("auth unreachable: ${e.javaClass.simpleName}", retryable = true)
        }
    }

    /** null when the refresh token is no longer accepted (then a new sign-up follows). */
    private fun refresh(refreshToken: String): Result? {
        val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val response = http.send(
            "POST",
            config.refreshUrl,
            headers("application/x-www-form-urlencoded"),
            form.toByteArray(Charsets.UTF_8),
        )
        if (response.code == 400 || response.code == 403 || response.code == 404) {
            // Unknown or expired account: forget it and sign up again
            store.clear()
            return null
        }
        if (response.code !in 200..299) return unavailable(response.code)
        return accept(response.body, "id_token", "refresh_token", "expires_in")
    }

    private fun signUp(): Result {
        val response = http.send(
            "POST",
            config.signUpUrl,
            headers("application/json; charset=utf-8"),
            """{"returnSecureToken":true}""".toByteArray(Charsets.UTF_8),
        )
        if (response.code !in 200..299) return unavailable(response.code)
        return accept(response.body, "idToken", "refreshToken", "expiresIn")
    }

    private fun accept(body: String, idKey: String, refreshKey: String, expiresKey: String): Result = try {
        val json = JSONObject(body)
        val token = json.getString(idKey)
        store.save(json.getString(refreshKey))
        // expiresIn is a string of seconds ("3600")
        val seconds = json.optString(expiresKey).toLongOrNull() ?: DEFAULT_LIFETIME_S
        idToken = token
        expiresAt = now() + seconds * 1_000
        Result.Token(token)
    } catch (e: JSONException) {
        Result.Unavailable("auth answer unreadable", retryable = true)
    }

    private fun unavailable(code: Int): Result =
        Result.Unavailable("auth HTTP $code", retryable = code == 408 || code == 429 || code >= 500)

    private fun headers(contentType: String) = mapOf(
        "Content-Type" to contentType,
        "X-Goog-Api-Key" to config.apiKey,
    )

    private companion object {
        /** Renew a little before the ID token runs out. */
        const val EARLY_REFRESH_MS = 5 * 60_000L
        const val DEFAULT_LIFETIME_S = 3_600L
    }
}
