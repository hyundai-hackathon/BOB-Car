package com.bobcar.shared.server

import java.io.IOException

/**
 * One server call with the ID token in the Authorization header (never in the URL, CLAUDE.md rule 6-1).
 * A 401 gets one more try with a refreshed token, then one with a new anonymous account (the server no longer
 * knows the old one, e.g. emulator data wiped). Blocking; call off the main thread.
 */
class ApiCaller(
    private val config: ServerConfig,
    private val tokens: IdTokenProvider,
    private val http: HttpTransport = UrlConnectionTransport,
) {
    sealed interface Outcome {
        /** The server answered (any status, 401 included after the retries). */
        data class Answered(val response: HttpResponse) : Outcome

        /** No answer: no network, server off, or sign-in trouble. [retryable] false = the setup is wrong. */
        data class Unreachable(val reason: String, val retryable: Boolean) : Outcome
    }

    /** [path] under /api/v1 ("/phone/me"); [jsonBody] is sent as UTF-8 JSON when given. */
    fun call(method: String, path: String, jsonBody: String? = null): Outcome {
        if (!config.configured) return Outcome.Unreachable("server not configured", retryable = false)
        var outcome = send(method, path, jsonBody) { tokens.get() }
        if (outcome.unauthorized()) outcome = send(method, path, jsonBody) { tokens.get(forceRefresh = true) }
        if (outcome.unauthorized()) outcome = send(method, path, jsonBody) { tokens.signUpAgain() }
        return outcome
    }

    private fun Outcome.unauthorized() = this is Outcome.Answered && response.code == 401

    private fun send(method: String, path: String, jsonBody: String?, token: () -> IdTokenProvider.Result): Outcome {
        val idToken = when (val t = token()) {
            is IdTokenProvider.Result.Token -> t.idToken
            is IdTokenProvider.Result.Unavailable -> return Outcome.Unreachable(t.reason, t.retryable)
        }
        val headers = buildMap {
            put("Authorization", "Bearer $idToken")
            if (jsonBody != null) put("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            Outcome.Answered(http.send(method, config.api(path), headers, jsonBody?.toByteArray(Charsets.UTF_8)))
        } catch (e: IOException) {
            Outcome.Unreachable(e.javaClass.simpleName, retryable = true)
        }
    }
}
