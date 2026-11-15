package com.bobcar.shared.server

/**
 * Where the server and Firebase Auth are, for this build (each app's build.gradle.kts, -Pbobcar.*).
 * Debug defaults to the PC's Firebase emulators (10.0.2.2); release has no server until a real HTTPS one
 * is set. Release only ever talks HTTPS (CLAUDE.md rule 6-1).
 */
data class ServerConfig(
    /** Functions base URL ending at the region; API paths are added to it ("/api/v1/..."). */
    val serverUrl: String,
    /** Auth emulator base URL; empty = Google's real Auth endpoints. */
    val authUrl: String,
    /** Firebase Web API key, sent as the X-Goog-Api-Key header (never in a URL). */
    val apiKey: String,
    val debug: Boolean,
) {
    /** A server is set and allowed for this build, and sign-in can work. */
    val configured: Boolean
        get() = canSendTo(serverUrl, debug) &&
            (authUrl.isEmpty() || canSendTo(authUrl, debug)) &&
            apiKey.isNotEmpty()

    fun api(path: String): String = serverUrl.trim().trimEnd('/') + "/api/v1" + path

    /** Anonymous sign-up. The Auth emulator serves each Google host as the first path part under its own base. */
    val signUpUrl: String get() = auth(IDENTITY_HOST, "/v1/accounts:signUp")

    /** Exchanges a refresh token for a new ID token. */
    val refreshUrl: String get() = auth(SECURE_TOKEN_HOST, "/v1/token")

    private fun auth(host: String, path: String): String =
        if (authUrl.isEmpty()) "https://$host$path" else authUrl.trim().trimEnd('/') + "/$host$path"

    private companion object {
        const val IDENTITY_HOST = "identitytoolkit.googleapis.com"
        const val SECURE_TOKEN_HOST = "securetoken.googleapis.com"
    }
}

/**
 * Whether data may be sent to [url] (CLAUDE.md rule 6-1).
 * Release: HTTPS only. Debug: HTTP is also accepted here; network_security_config still allows plain HTTP
 * only to the PC's local server (10.0.2.2). Empty = no server yet.
 */
fun canSendTo(url: String, debug: Boolean): Boolean {
    val u = url.trim().lowercase()
    return when {
        u.startsWith("https://") -> u.length > "https://".length
        u.startsWith("http://") -> debug && u.length > "http://".length
        else -> false
    }
}
