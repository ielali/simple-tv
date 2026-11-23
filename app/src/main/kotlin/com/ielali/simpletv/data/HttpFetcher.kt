package com.ielali.simpletv.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP GET's outcome. Non-2xx responses are results, not exceptions, so callers can explain them. */
data class HttpResult(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/** Fetches text over HTTP. An interface so provider loading can be tested against fakes or a local server. */
fun interface TextFetcher {
    /** Throws [IOException] only when the server cannot be reached at all. */
    fun get(url: String, headers: Map<String, String>): HttpResult
}

class HttpFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 45_000,
) : TextFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResult(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
