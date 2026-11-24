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

/** Sends a form POST (OAuth sign-in). Credentials go in the body, never in the address. */
fun interface FormPoster {
    fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResult
}

class HttpFetcher(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 45_000,
) : TextFetcher, FormPoster {
    override fun post(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResult {
        val body = form.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return HttpResult(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

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
