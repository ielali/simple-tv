package com.ielali.simpletv.data

import java.io.IOException

/** Talks to TMDB with the household's key. No Android, so it is tested with a fake [TextFetcher]. */
class TmdbClient(private val fetcher: TextFetcher) {

    sealed interface Check {
        data object Ok : Check
        data class Problem(val message: String) : Check
    }

    fun check(key: String): Check {
        if (key.isBlank()) return Check.Problem("Paste the key from your TMDB account.")
        return try {
            val r = fetcher.get(TmdbApi.checkUrl(key), TmdbApi.headers(key))
            when {
                r.ok -> Check.Ok
                r.code == 401 || r.code == 403 -> Check.Problem("TMDB didn’t accept this key. Copy the API Read Access Token from your TMDB account settings.")
                r.code == 429 -> Check.Problem("TMDB is busy. Try again in a minute.")
                else -> Check.Problem("TMDB isn’t answering right now. Try again later.")
            }
        } catch (e: IOException) {
            Check.Problem("The TV couldn’t reach TMDB. Check its internet connection.")
        }
    }

    /** Adds TMDB's details to [meta]: by the provider's TMDB id, else by searching title and year. */
    fun enrich(meta: FilmMeta, title: String, key: String, now: Long): FilmMeta {
        return try {
            val id = meta.tmdbId?.let { it to "id" } ?: search(meta.type, title, meta.year, key)?.let { it to "title" }
                ?: return meta.copy(tmdbAt = now)
            val url = if (meta.type == CatalogType.SERIES) TmdbApi.tvUrl(id.first, key) else TmdbApi.movieUrl(id.first, key)
            val r = fetcher.get(url, TmdbApi.headers(key))
            if (r.code == 429) return meta // busy: try again later
            val d = if (r.ok) TmdbApi.parseDetails(r.body, meta.type) else null
            d?.let { meta.withTmdb(it, id.first, id.second, now) } ?: meta.copy(tmdbAt = now)
        } catch (e: IOException) {
            meta
        }
    }

    private fun search(type: CatalogType, title: String, year: String?, key: String): Long? {
        val clean = TmdbApi.searchTitle(title)
        if (clean.isBlank()) return null
        val r = fetcher.get(TmdbApi.searchUrl(type, clean, year, key), TmdbApi.headers(key))
        if (!r.ok) return null
        return TmdbApi.parseSearch(r.body, clean, year)
            ?: if (year != null) fetcher.get(TmdbApi.searchUrl(type, clean, null, key), TmdbApi.headers(key))
                .takeIf { it.ok }?.let { TmdbApi.parseSearch(it.body, clean, year) } else null
    }

    fun certLists(key: String, now: Long): CertLists? = try {
        val m = fetcher.get(TmdbApi.certListUrl(CatalogType.FILM, key), TmdbApi.headers(key))
        val t = fetcher.get(TmdbApi.certListUrl(CatalogType.SERIES, key), TmdbApi.headers(key))
        if (m.ok && t.ok) CertLists(TmdbApi.parseCertList(m.body), TmdbApi.parseCertList(t.body), now) else null
    } catch (e: IOException) {
        null
    }
}
