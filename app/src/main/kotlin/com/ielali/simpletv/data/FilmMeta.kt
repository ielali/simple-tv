package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

/**
 * Details about one film or series: from the provider's own details call (Xtream `get_vod_info` /
 * `get_series_info`, no key needed) and, with a TMDB key, from The Movie Database (official ratings
 * per country, posters, trailers). Cached on the TV per subscription.
 */
@Serializable
data class FilmMeta(
    val itemId: String,
    val type: CatalogType,
    val tmdbId: Long? = null,
    /** How TMDB was matched: "id" (the provider gave its TMDB id) or "title" (search by title and year). */
    val matched: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val plot: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    /** Score out of 10. */
    val score: Double? = null,
    /** YouTube video id of a trailer. */
    val trailer: String? = null,
    /** Official rating by country code (TMDB), e.g. "AU" -> "PG". */
    val certs: Map<String, String> = emptyMap(),
    /** The provider's own age label when it gives one ("16+"); shown, never used for rules. */
    val providerRating: String? = null,
    val adult: Boolean = false,
    val fetchedAt: Long = 0L,
    /** When TMDB was asked (0 = never, e.g. no key); lets the TV fill in TMDB later. */
    val tmdbAt: Long = 0L,
) {
    companion object {
        fun key(type: CatalogType, id: String) = (if (type == CatalogType.SERIES) "s:" else "f:") + id
    }
    val key: String get() = key(type, itemId)

    /** TMDB's details win where it has them; the provider's fill the gaps. */
    fun withTmdb(d: TmdbApi.Details, id: Long, how: String, now: Long) = copy(
        tmdbId = id,
        matched = how,
        poster = d.poster ?: poster,
        backdrop = d.backdrop ?: backdrop,
        plot = d.plot ?: plot,
        cast = d.cast.ifEmpty { cast },
        director = d.director ?: director,
        genres = d.genres.ifEmpty { genres },
        year = d.year ?: year,
        score = d.score ?: score,
        trailer = d.trailer ?: trailer,
        certs = d.certs,
        adult = d.adult,
        tmdbAt = now,
    )
}

/** One official rating of a country, as TMDB lists them; [order] goes from mildest to strongest. */
@Serializable
data class Cert(val certification: String, val meaning: String = "", val order: Int = 0)

/** Official rating systems by country, for films and for TV series. */
@Serializable
data class CertLists(
    val movie: Map<String, List<Cert>> = emptyMap(),
    val tv: Map<String, List<Cert>> = emptyMap(),
    val fetchedAt: Long = 0L,
) {
    fun of(type: CatalogType, country: String): List<Cert> =
        (if (type == CatalogType.SERIES) tv else movie)[country.uppercase()].orEmpty().sortedBy { it.order }
}

/** Which films a film channel may play, from the caregiver's rating rules (PlaylistSpec). */
object RatingRules {

    /** The household's rating country: the one chosen on the page, else the TV's own region. */
    fun country(chosen: String?, tvRegion: String?): String =
        chosen?.trim()?.uppercase()?.takeIf { it.length == 2 }
            ?: tvRegion?.trim()?.uppercase()?.takeIf { it.length == 2 }
            ?: "US"

    fun hasRules(spec: PlaylistSpec) = spec.maxRating != null || spec.minScore != null

    fun certOf(meta: FilmMeta?, country: String): String? = meta?.certs?.get(country.uppercase())?.takeIf { it.isNotBlank() }

    /**
     * Unchecked films (no details yet) only play when the channel has no rating rules, so a
     * family channel never shows something it has not checked. Adult titles are left out whenever
     * [hideAdult] is on. A film without a rating (or without a score, when a minimum score is set)
     * plays unless the channel leaves such films out.
     */
    fun allowed(meta: FilmMeta?, spec: PlaylistSpec, country: String, lists: CertLists?, hideAdult: Boolean): Boolean {
        if (meta == null) return !hasRules(spec)
        if (hideAdult && meta.adult) return false
        spec.minScore?.let { min ->
            val s = meta.score
            if (s == null) { if (spec.skipUnrated) return false } else if (s < min) return false
        }
        spec.maxRating?.let { max ->
            val scale = lists?.of(meta.type, country).orEmpty()
            val limit = scale.firstOrNull { it.certification.equals(max, true) }?.order
            val cert = certOf(meta, country)
            val order = cert?.let { c -> scale.firstOrNull { it.certification.equals(c, true) }?.order }
            when {
                limit == null -> Unit // the chosen rating is not in this country's list: no limit
                order == null -> if (spec.skipUnrated) return false
                order > limit -> return false
            }
        }
        return true
    }
}
