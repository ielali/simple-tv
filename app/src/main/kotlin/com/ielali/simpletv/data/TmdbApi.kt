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

/**
 * The Movie Database (TMDB) API v3: details with official ratings, trailers and cast, title search,
 * and each country's rating system. The household's own key (ADR-018) goes in the Authorization
 * header when it is a read access token; an older v3 key has to be a query parameter, which only
 * ever travels from the TV to TMDB over HTTPS and is redacted from logs.
 */
object TmdbApi {
    const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A read access token is a JWT ("eyJ…"); anything else is treated as a v3 API key. */
    fun isToken(key: String) = key.trim().startsWith("eyJ")

    fun headers(key: String): Map<String, String> =
        if (isToken(key)) mapOf("Authorization" to "Bearer ${key.trim()}", "Accept" to "application/json")
        else mapOf("Accept" to "application/json")

    fun url(path: String, key: String, params: Map<String, String> = emptyMap()): String {
        val all = if (isToken(key)) params else params + ("api_key" to key.trim())
        return BASE + path + if (all.isEmpty()) "" else "?" + all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    fun checkUrl(key: String) = url("/configuration", key)
    fun movieUrl(id: Long, key: String) = url("/movie/$id", key, mapOf("append_to_response" to "release_dates,videos,credits", "language" to "en-US"))
    fun tvUrl(id: Long, key: String) = url("/tv/$id", key, mapOf("append_to_response" to "content_ratings,videos,credits,external_ids", "language" to "en-US"))
    fun searchUrl(type: CatalogType, title: String, year: String?, key: String): String =
        if (type == CatalogType.SERIES) url("/search/tv", key, mapOf("query" to title) + (year?.let { mapOf("first_air_date_year" to it) } ?: emptyMap()))
        else url("/search/movie", key, mapOf("query" to title) + (year?.let { mapOf("year" to it) } ?: emptyMap()))
    fun certListUrl(type: CatalogType, key: String) = url(if (type == CatalogType.SERIES) "/certification/tv/list" else "/certification/movie/list", key)

    fun image(path: String?, size: String = "w342"): String? = path?.takeIf { it.startsWith("/") }?.let { IMAGES + size + it }

    /** What TMDB adds to a film or series. */
    data class Details(
        val poster: String?, val backdrop: String?, val plot: String?, val cast: List<String>, val director: String?,
        val genres: List<String>, val year: String?, val score: Double?, val trailer: String?, val certs: Map<String, String>, val adult: Boolean,
        val imdbId: String? = null,
    )

    private val imdbPattern = Regex("""^tt\d{5,10}$""")

    /** A valid IMDb title id ("tt0042546"), or null. */
    fun imdbId(raw: String?): String? = raw?.trim()?.takeIf { imdbPattern.matches(it) }

    fun parseDetails(body: String, type: CatalogType): Details? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (o["id"] == null) return null
        val certs = LinkedHashMap<String, String>()
        if (type == CatalogType.SERIES) {
            ((o["content_ratings"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().forEach { el ->
                val r = el as? JsonObject ?: return@forEach
                val c = r["iso_3166_1"].str() ?: return@forEach
                r["rating"].str()?.let { certs[c] = it }
            }
        } else {
            ((o["release_dates"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().forEach { el ->
                val r = el as? JsonObject ?: return@forEach
                val c = r["iso_3166_1"].str() ?: return@forEach
                // Prefer the cinema release's rating (type 3), then any other release that has one.
                val dates = (r["release_dates"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val cert = dates.sortedBy { if (it["type"].str() == "3") 0 else 1 }.firstNotNullOfOrNull { it["certification"].str() }
                if (cert != null) certs[c] = cert
            }
        }
        val videos = ((o["videos"] as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["site"].str() == "YouTube" && it["key"].str() != null }
        val trailer = videos.sortedWith(compareBy({ if (it["type"].str() == "Trailer") 0 else if (it["type"].str() == "Teaser") 1 else 2 }, { if ((it["official"] as? JsonPrimitive)?.booleanOrNull == true) 0 else 1 }))
            .firstOrNull()?.get("key").str()
        val credits = o["credits"] as? JsonObject
        val cast = (credits?.get("cast") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name").str() }.take(6)
        val director = (credits?.get("crew") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it["job"].str() == "Director" }?.get("name").str()
            ?: (o["created_by"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.get("name").str() }
        val date = o["release_date"].str() ?: o["first_air_date"].str()
        return Details(
            poster = image(o["poster_path"].str()),
            backdrop = image(o["backdrop_path"].str(), "w780"),
            plot = o["overview"].str(),
            cast = cast,
            director = director,
            genres = (o["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("name").str() },
            year = date?.take(4)?.takeIf { it.length == 4 },
            score = o["vote_average"].str()?.toDoubleOrNull()?.takeIf { it > 0 && (o["vote_count"].str()?.toIntOrNull() ?: 1) > 0 },
            trailer = trailer,
            certs = certs,
            adult = (o["adult"] as? JsonPrimitive)?.booleanOrNull ?: false,
            imdbId = imdbId(o["imdb_id"].str() ?: (o["external_ids"] as? JsonObject)?.get("imdb_id").str()),
        )
    }

    /** Best search hit: an exact title match first (and the right year when given), else the first. */
    fun parseSearch(body: String, title: String, year: String?): Long? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val hits = (o["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (hits.isEmpty()) return null
        val clean = normaliseTitle(title)
        fun titleOf(h: JsonObject) = normaliseTitle(h["title"].str() ?: h["name"].str() ?: "")
        fun yearOf(h: JsonObject) = (h["release_date"].str() ?: h["first_air_date"].str())?.take(4)
        val best = hits.firstOrNull { titleOf(it) == clean && (year == null || yearOf(it) == year) }
            ?: hits.firstOrNull { titleOf(it) == clean }
            ?: hits.first().takeIf { year != null && yearOf(it) == year }
        return best?.get("id").str()?.toLongOrNull()
    }

    /** Titles from providers carry tags like "EN - ", "(1960)" or "[4K]"; strip them for searching. */
    fun searchTitle(name: String): String = name
        .replace(Regex("""^\s*[A-Z]{2,3}\s*[-|:]\s+"""), "")
        .replace(Regex("""\s*[\[(][^\])]*[\])]\s*"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun normaliseTitle(s: String) = searchTitle(s).lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    fun parseCertList(body: String): Map<String, List<Cert>> {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyMap()
        val certs = o["certifications"] as? JsonObject ?: return emptyMap()
        return certs.entries.associate { (country, arr) ->
            country.uppercase() to (arr as? JsonArray).orEmpty().mapNotNull { el ->
                val c = el as? JsonObject ?: return@mapNotNull null
                val code = c["certification"].str() ?: return@mapNotNull null
                Cert(code, c["meaning"].str().orEmpty(), c["order"].str()?.toDoubleOrNull()?.toInt() ?: 0)
            }.sortedBy { it.order }
        }.filterValues { it.isNotEmpty() }
    }

    private fun JsonElement?.str(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        else -> null
    }
}
