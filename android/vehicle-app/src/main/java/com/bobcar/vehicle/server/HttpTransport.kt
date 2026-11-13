package com.bobcar.vehicle.server

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer. [body] is the response text (error bodies included). */
data class HttpResponse(val code: Int, val body: String)

/**
 * Sends one HTTP request and waits for the answer (call it off the main thread).
 * Throws [IOException] when the server cannot be reached. Tests replace it with a fake.
 */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

/** [HttpTransport] over HttpURLConnection. Certificates are always checked (CLAUDE.md rule 6-1). */
object UrlConnectionTransport : HttpTransport {

    private const val TIMEOUT_MS = 10_000

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("bad url", e)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
