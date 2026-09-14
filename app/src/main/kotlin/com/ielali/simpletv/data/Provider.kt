package com.ielali.simpletv.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable
enum class ProviderKind {
    /** Xtream Codes style account: server URL + username + password. The most common IPTV subscription. */
    XTREAM,

    /** A plain M3U/M3U8 playlist link. Username/password, if given, are sent as HTTP Basic auth. */
    M3U,
}

/**
 * An IPTV subscription. Channels imported from it carry its id so they can be re-synced or removed
 * together and inherit its icon when they have none of their own.
 *
 * The password is stored on the device only. The config API never returns it; see ConfigServer.
 */
@Serializable
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderKind = ProviderKind.XTREAM,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val logoUrl: String? = null,
) {
    /** URL of the M3U playlist to fetch for this provider. */
    fun playlistUrl(): String = when (kind) {
        ProviderKind.XTREAM -> XtreamCodes.playlistUrl(url, username.orEmpty(), password.orEmpty())
        ProviderKind.M3U -> url.trim()
    }

    /** Headers to send when fetching the playlist and, for M3U providers, when playing its streams. */
    fun authHeaders(): Map<String, String> =
        if (kind == ProviderKind.M3U && !username.isNullOrBlank() && !password.isNullOrEmpty()) {
            mapOf("Authorization" to BasicAuth.header(username, password))
        } else {
            emptyMap()
        }
}

@Serializable
data class ProviderList(
    val version: Int = 1,
    val providers: List<Provider> = emptyList(),
)

object XtreamCodes {
    /**
     * Builds the M3U playlist URL for an Xtream Codes account. Accepts the server as
     * `host`, `host:port`, `http://host:port` or `http://host:port/` and tolerates a pasted
     * `get.php`/`player_api.php` link, from which only the origin is kept.
     */
    fun playlistUrl(server: String, username: String, password: String): String {
        val base = baseUrl(server)
        val u = URLEncoder.encode(username.trim(), "UTF-8")
        val p = URLEncoder.encode(password, "UTF-8")
        return "$base/get.php?username=$u&password=$p&type=m3u_plus&output=ts"
    }

    fun baseUrl(server: String): String {
        var s = server.trim()
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
        val uri = runCatching { java.net.URI(s) }.getOrNull() ?: return s.trimEnd('/')
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }
}

object BasicAuth {
    fun header(username: String, password: String): String {
        val token = java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }
}
