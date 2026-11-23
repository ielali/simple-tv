package com.ielali.simpletv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.util.UUID

/**
 * The Xtream Codes player API (`player_api.php`), which IPTV apps use. Many providers disable the
 * `get.php` playlist download but keep this API, and it also reports the account's status.
 * Parsing is lenient: panels send numbers as strings and leave fields null.
 */
object XtreamApi {

    data class Account(
        val authenticated: Boolean,
        val status: String?,
        /** Epoch seconds, or null for no expiry. */
        val expiresAt: Long?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val formats: List<String>,
    ) {
        val active: Boolean get() = status == null || status.equals("Active", ignoreCase = true)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun accountUrl(server: String, username: String, password: String): String =
        "${XtreamCodes.baseUrl(server)}/player_api.php?username=${enc(username.trim())}&password=${enc(password)}"

    fun liveStreamsUrl(server: String, username: String, password: String): String =
        accountUrl(server, username, password) + "&action=get_live_streams"

    fun liveCategoriesUrl(server: String, username: String, password: String): String =
        accountUrl(server, username, password) + "&action=get_live_categories"

    fun streamUrl(server: String, username: String, password: String, streamId: String, ext: String): String =
        "${XtreamCodes.baseUrl(server)}/live/${enc(username.trim())}/${enc(password)}/$streamId.$ext"

    /** Null when the body is not an Xtream account response (not JSON, or no user_info). */
    fun parseAccount(body: String): Account? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val info = root["user_info"] as? JsonObject ?: return null
        return Account(
            authenticated = info["auth"].asInt() == 1 || info["auth"].asBoolean() == true,
            status = info["status"].asString(),
            expiresAt = info["exp_date"].asLong(),
            maxConnections = info["max_connections"].asInt(),
            activeConnections = info["active_cons"].asInt(),
            formats = (info["allowed_output_formats"] as? JsonArray)?.mapNotNull { it.asString() }.orEmpty(),
        )
    }

    /** category_id -> category name. Empty when the body is not a category list. */
    fun parseCategories(body: String): Map<String, String> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyMap()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["category_id"].asString() ?: return@mapNotNull null
            id to (o["category_name"].asString()?.trim().orEmpty().ifEmpty { "Category $id" })
        }.toMap()
    }

    /**
     * Live channels in the provider's own order. Prefers HLS (.m3u8) when the account allows it, as
     * ExoPlayer recovers from live hiccups better with HLS; otherwise MPEG-TS.
     */
    fun parseLiveStreams(
        body: String,
        server: String,
        username: String,
        password: String,
        categories: Map<String, String>,
        formats: List<String>,
    ): List<Channel> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        val ext = if (formats.isEmpty() || formats.any { it.equals("m3u8", true) }) "m3u8" else "ts"
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["stream_id"].asString() ?: return@mapNotNull null
            val name = o["name"].asString()?.trim().orEmpty().ifEmpty { "Channel $id" }
            val categoryId = o["category_id"].asString()
            val order = o["num"].asInt() ?: Int.MAX_VALUE
            order to Channel(
                id = UUID.randomUUID().toString(),
                number = 0,
                name = name,
                type = SourceType.STREAM,
                url = streamUrl(server, username, password, id, ext),
                logoUrl = o["stream_icon"].asString()?.takeIf { it.startsWith("http") },
                group = categoryId?.let { categories[it] ?: "Category $it" },
            )
        }.sortedBy { it.first }.map { it.second }
    }

    private fun JsonElement?.asString(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }
        else -> null
    }

    private fun JsonElement?.asLong(): Long? = asString()?.toDoubleOrNull()?.toLong()
    private fun JsonElement?.asInt(): Int? = asString()?.toDoubleOrNull()?.toInt()
    private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
