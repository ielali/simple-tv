package com.ielali.simpletv.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Caregiver-readable explanations for provider failures. Never include a URL: Xtream URLs carry the
 * username and password, and these messages are shown on the config page.
 */
object FetchErrors {

    fun forHttp(code: Int): String = when (code) {
        401, 403 -> "The provider refused the connection (HTTP $code). Check the username and password, " +
            "and that the subscription is not in use on too many devices."
        404, 410 -> "The provider does not offer a playlist at this address (HTTP $code). " +
            "Check the server address, or ask the provider for an M3U link."
        429 -> "The provider is limiting requests (HTTP 429). Wait a few minutes and try again."
        in 500..599 -> "The provider's server had a problem (HTTP $code). Try again later."
        else -> "The provider answered with an error (HTTP $code)."
    }

    fun forNetwork(e: IOException): String = when (e) {
        is UnknownHostException -> "The server address could not be found. Check it for typos."
        is SocketTimeoutException -> "The provider did not answer in time. Try again, or check the TV's internet."
        else -> "The provider could not be reached (${e.javaClass.simpleName}). Check the server address and port."
    }

    private val queryCredential = Regex("""(?i)((?:username|password|user|pass|token|api_key)=)[^&\s]*""")
    private val xtreamPath = Regex("""(/(?:live|movie|series)/)[^/\s]+/[^/\s]+/""")
    private val userInfo = Regex("""(?i)(https?://)[^/@\s]+@""")

    /** Strips credentials from any text that might contain a provider URL (logs, exception messages). */
    fun redact(text: String): String = text
        .replace(queryCredential, "$1***")
        .replace(xtreamPath, "$1***/***/")
        .replace(userInfo, "$1***@")
}
