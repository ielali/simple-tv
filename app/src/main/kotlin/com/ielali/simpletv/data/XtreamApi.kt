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

    /** The provider's whole TV guide as XMLTV; it names channels by their epg_channel_id. */
    fun xmltvUrl(server: String, username: String, password: String): String =
        "${XtreamCodes.baseUrl(server)}/xmltv.php?username=${enc(username.trim())}&password=${enc(password)}"

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
            val name = TextClean.name(o["name"].asString().orEmpty()).ifEmpty { "Channel $id" }
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
                sourceId = id,
                epgId = o["epg_channel_id"].asString()?.trim(),
            )
        }.sortedBy { it.first }.map { it.second }
    }

    fun vodCategoriesUrl(server: String, username: String, password: String) =
        accountUrl(server, username, password) + "&action=get_vod_categories"

    fun vodStreamsUrl(server: String, username: String, password: String) =
        accountUrl(server, username, password) + "&action=get_vod_streams"

    fun seriesCategoriesUrl(server: String, username: String, password: String) =
        accountUrl(server, username, password) + "&action=get_series_categories"

    fun seriesListUrl(server: String, username: String, password: String) =
        accountUrl(server, username, password) + "&action=get_series"

    fun seriesInfoUrl(server: String, username: String, password: String, seriesId: String) =
        accountUrl(server, username, password) + "&action=get_series_info&series_id=${enc(seriesId)}"

    fun vodInfoUrl(server: String, username: String, password: String, vodId: String) =
        accountUrl(server, username, password) + "&action=get_vod_info&vod_id=${enc(vodId)}"

    private val youtubeId = Regex("""(?:v=|youtu\.be/|embed/|^)([A-Za-z0-9_-]{11})(?:[?&#]|$)""")

    /**
     * The provider's details for a film (`get_vod_info`) or series (`get_series_info`): the `info`
     * block. Panels differ a lot; every field is optional.
     */
    fun parseInfoMeta(body: String, itemId: String, type: CatalogType, now: Long): FilmMeta? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val o = root["info"] as? JsonObject ?: return null
        val list = { v: String? -> v?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty() }
        val backdrop = when (val b = o["backdrop_path"]) {
            is JsonArray -> b.firstOrNull().asString()
            else -> b.asString()
        }
        val score = o["rating"].asString()?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: o["rating_5based"].asString()?.toDoubleOrNull()?.takeIf { it > 0 }?.times(2)
        val trailer = o["youtube_trailer"].asString()?.let { t -> youtubeId.find(t.trim())?.groupValues?.get(1) }
        return FilmMeta(
            itemId = itemId,
            type = type,
            tmdbId = (o["tmdb_id"] ?: o["tmdb"]).asString()?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 },
            poster = (o["movie_image"] ?: o["cover_big"] ?: o["cover"]).asString()?.takeIf { it.startsWith("http") },
            backdrop = backdrop?.takeIf { it.startsWith("http") },
            plot = (o["plot"] ?: o["description"]).asString()?.trim(),
            cast = list((o["cast"] ?: o["actors"]).asString()).take(6),
            director = o["director"].asString()?.trim(),
            genres = list(o["genre"].asString()),
            year = (o["releasedate"] ?: o["releaseDate"] ?: o["release_date"]).asString()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
            score = score,
            trailer = trailer,
            providerRating = (o["age"] ?: o["mpaa_rating"] ?: o["mpaa"]).asString()?.trim(),
            imdbId = TmdbApi.imdbId(o["imdb_id"].asString()),
            fetchedAt = now,
        )
    }

    fun movieUrl(server: String, username: String, password: String, id: String, ext: String): String =
        "${XtreamCodes.baseUrl(server)}/movie/${enc(username.trim())}/${enc(password)}/$id.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(server: String, username: String, password: String, id: String, ext: String): String =
        "${XtreamCodes.baseUrl(server)}/series/${enc(username.trim())}/${enc(password)}/$id.${ext.ifBlank { "mp4" }}"

    private val yearInName = Regex("""\((19|20)\d{2}\)""")

    private fun yearOf(o: JsonObject, name: String): String? =
        (o["year"].asString() ?: o["releaseDate"].asString() ?: o["release_date"].asString())
            ?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: yearInName.find(name)?.value?.trim('(', ')')

    /** The provider's score out of 10 ("rating", or "rating_5based" doubled); 0 means none given. */
    private fun scoreOf(o: JsonObject): Double? =
        (o["rating"].asString()?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }
            ?: o["rating_5based"].asString()?.toDoubleOrNull()?.takeIf { it in 0.1..5.0 }?.times(2))
            ?.let { Math.round(it * 10) / 10.0 }

    /** A unix time in seconds as the provider sends it, as epoch millis; nonsense is ignored. */
    private fun timeOf(s: String?): Long? = s?.trim()?.toLongOrNull()?.takeIf { it in 946_684_800L..4_102_444_800L }?.times(1000)

    /** Films (`get_vod_streams`) in the provider's order. */
    fun parseVodStreams(body: String, categories: Map<String, String>): List<CatalogItem> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["stream_id"].asString() ?: return@mapNotNull null
            val name = TextClean.name(o["name"].asString().orEmpty()).ifEmpty { "Film $id" }
            val categoryId = o["category_id"].asString()
            (o["num"].asInt() ?: Int.MAX_VALUE) to CatalogItem(
                id = id,
                name = name,
                type = CatalogType.FILM,
                group = categoryId?.let { categories[it] ?: "Category $it" },
                logo = o["stream_icon"].asString()?.takeIf { it.startsWith("http") },
                year = yearOf(o, name),
                ext = o["container_extension"].asString(),
                score = scoreOf(o),
                added = timeOf(o["added"].asString()),
            )
        }.sortedBy { it.first }.map { it.second }
    }

    /** Series (`get_series`) in the provider's order. */
    fun parseSeriesList(body: String, categories: Map<String, String>): List<CatalogItem> {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["series_id"].asString() ?: return@mapNotNull null
            val name = TextClean.name(o["name"].asString().orEmpty()).ifEmpty { "Series $id" }
            val categoryId = o["category_id"].asString()
            (o["num"].asInt() ?: Int.MAX_VALUE) to CatalogItem(
                id = id,
                name = name,
                type = CatalogType.SERIES,
                group = categoryId?.let { categories[it] ?: "Category $it" },
                logo = o["cover"].asString()?.takeIf { it.startsWith("http") },
                year = yearOf(o, name),
                score = scoreOf(o),
                added = timeOf(o["last_modified"].asString()),
            )
        }.sortedBy { it.first }.map { it.second }
    }

    /** Episodes of one series (`get_series_info`), in season and episode order. */
    fun parseSeriesEpisodes(body: String): List<Episode> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val seasons = root["episodes"] as? JsonObject ?: return emptyList()
        return seasons.entries.flatMap { (seasonKey, value) ->
            (value as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"].asString() ?: return@mapNotNull null
                val info = o["info"] as? JsonObject
                val seconds = info?.get("duration_secs").asInt()
                Episode(
                    id = id,
                    season = o["season"].asInt() ?: seasonKey.toIntOrNull() ?: 1,
                    episode = o["episode_num"].asInt() ?: 0,
                    title = o["title"].asString()?.trim().orEmpty(),
                    ext = o["container_extension"].asString(),
                    minutes = seconds?.let { (it + 30) / 60 },
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))
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
