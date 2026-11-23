package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmDetailsTest {

    private val au = listOf(Cert("G", "General", 1), Cert("PG", "Parental guidance", 2), Cert("M", "Mature", 3), Cert("MA15+", "", 4), Cert("R18+", "", 5))
    private val lists = CertLists(movie = mapOf("AU" to au), tv = mapOf("AU" to listOf(Cert("G", "", 2), Cert("PG", "", 3), Cert("M", "", 4))))
    private fun film(id: String, cert: String? = null, score: Double? = null, adult: Boolean = false) =
        FilmMeta(id, CatalogType.FILM, certs = cert?.let { mapOf("AU" to it) } ?: emptyMap(), score = score, adult = adult)
    private val spec = PlaylistSpec(PlaylistMode.FILM, "p", categories = listOf("Comedy"))

    @Test
    fun `rating country is the chosen one, else the TV's region`() {
        assertEquals("GB", RatingRules.country("gb", "AU"))
        assertEquals("AU", RatingRules.country("", "au"))
        assertEquals("US", RatingRules.country(null, null))
    }

    @Test
    fun `highest rating keeps milder films and drops stronger ones`() {
        val pg = spec.copy(maxRating = "PG")
        assertTrue(RatingRules.allowed(film("1", "G"), pg, "AU", lists, true))
        assertTrue(RatingRules.allowed(film("2", "PG"), pg, "AU", lists, true))
        assertFalse(RatingRules.allowed(film("3", "M"), pg, "AU", lists, true))
        assertFalse(RatingRules.allowed(film("4", "R18+"), pg, "AU", lists, true))
    }

    @Test
    fun `films without a rating play unless the channel leaves them out`() {
        assertTrue(RatingRules.allowed(film("1"), spec.copy(maxRating = "PG"), "AU", lists, true))
        assertFalse(RatingRules.allowed(film("1"), spec.copy(maxRating = "PG", skipUnrated = true), "AU", lists, true))
        // A rating code that is not in the country's list counts as no rating.
        assertFalse(RatingRules.allowed(film("1", "X"), spec.copy(maxRating = "PG", skipUnrated = true), "AU", lists, true))
    }

    @Test
    fun `unchecked films only play on channels without rules`() {
        assertTrue(RatingRules.allowed(null, spec, "AU", lists, true))
        assertFalse(RatingRules.allowed(null, spec.copy(maxRating = "PG"), "AU", lists, true))
        assertFalse(RatingRules.allowed(null, spec.copy(minScore = 6.0), "AU", lists, true))
    }

    @Test
    fun `minimum score and adult titles`() {
        val good = spec.copy(minScore = 7.0)
        assertTrue(RatingRules.allowed(film("1", score = 7.4), good, "AU", lists, true))
        assertFalse(RatingRules.allowed(film("2", score = 6.1), good, "AU", lists, true))
        assertTrue(RatingRules.allowed(film("3"), good, "AU", lists, true))
        assertFalse(RatingRules.allowed(film("3"), good.copy(skipUnrated = true), "AU", lists, true))
        assertFalse(RatingRules.allowed(film("4", adult = true), spec, "AU", lists, true))
        assertTrue(RatingRules.allowed(film("4", adult = true), spec, "AU", lists, false))
    }

    @Test
    fun `series use the country's TV rating list`() {
        val s = FilmMeta("9", CatalogType.SERIES, certs = mapOf("AU" to "M"))
        assertFalse(RatingRules.allowed(s, spec.copy(maxRating = "PG"), "AU", lists, true))
        assertEquals(listOf("G", "PG", "M"), lists.of(CatalogType.SERIES, "au").map { it.certification })
    }

    @Test
    fun `filtering keeps the shuffled order stable as more films are checked`() {
        val items = (1..20).map { CatalogItem("$it", "Film $it", CatalogType.FILM, group = "Comedy") }
        val cat = Catalog("p", 0, "x", items)
        val s = spec.copy(shuffle = true, seed = 42)
        val all = PlaylistQueue.films(cat, s).map { it.id }
        val evens = PlaylistQueue.films(cat, s) { it.id.toInt() % 2 == 0 }.map { it.id }
        assertEquals(all.filter { it.toInt() % 2 == 0 }, evens)
        assertEquals(20, PlaylistQueue.candidates(cat, s).size)
    }

    @Test
    fun `provider details are read from get_vod_info and get_series_info`() {
        val vod = """{"info":{"tmdb_id":"1234","movie_image":"http://img/p.jpg","backdrop_path":["http://img/b.jpg"],
            "plot":"A man and a rabbit.","cast":"James Stewart, Josephine Hull","director":"Henry Koster",
            "genre":"Comedy / Drama","releasedate":"1950-10-13","rating":"7.9","youtube_trailer":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","age":"PG"},"movie_data":{}}"""
        val m = XtreamApi.parseInfoMeta(vod, "77", CatalogType.FILM, 5L)!!
        assertEquals(1234L, m.tmdbId)
        assertEquals("http://img/p.jpg", m.poster)
        assertEquals("http://img/b.jpg", m.backdrop)
        assertEquals(listOf("James Stewart", "Josephine Hull"), m.cast)
        assertEquals(listOf("Comedy", "Drama"), m.genres)
        assertEquals("1950", m.year)
        assertEquals(7.9, m.score!!, 0.001)
        assertEquals("dQw4w9WgXcQ", m.trailer)
        assertEquals("PG", m.providerRating)
        val series = """{"info":{"cover":"http://img/c.jpg","rating_5based":"4.5","youtube_trailer":"abcdefghijk","releaseDate":"1975"},"episodes":{}}"""
        val s = XtreamApi.parseInfoMeta(series, "9", CatalogType.SERIES, 5L)!!
        assertEquals(9.0, s.score!!, 0.001)
        assertEquals("abcdefghijk", s.trailer)
        assertNull(s.tmdbId)
        assertNull(XtreamApi.parseInfoMeta("[]", "1", CatalogType.FILM, 0L))
    }

    private val movieJson = """{"id":1234,"title":"Harvey","adult":false,"overview":"Elwood and his friend.","poster_path":"/p.jpg","backdrop_path":"/b.jpg",
        "release_date":"1950-10-13","vote_average":7.9,"vote_count":900,"genres":[{"id":35,"name":"Comedy"}],
        "release_dates":{"results":[{"iso_3166_1":"AU","release_dates":[{"certification":"","type":1},{"certification":"PG","type":3}]},
          {"iso_3166_1":"US","release_dates":[{"certification":"NR","type":4}]}]},
        "videos":{"results":[{"site":"YouTube","key":"teaserkey01","type":"Teaser","official":true},{"site":"YouTube","key":"trailerkey1","type":"Trailer","official":true}]},
        "credits":{"cast":[{"name":"James Stewart"}],"crew":[{"job":"Director","name":"Henry Koster"}]}}"""

    @Test
    fun `tmdb movie details give ratings per country, trailer, cast and images`() {
        val d = TmdbApi.parseDetails(movieJson, CatalogType.FILM)!!
        assertEquals(mapOf("AU" to "PG", "US" to "NR"), d.certs)
        assertEquals("trailerkey1", d.trailer)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", d.poster)
        assertEquals(listOf("James Stewart"), d.cast)
        assertEquals("Henry Koster", d.director)
        assertEquals("1950", d.year)
        val merged = FilmMeta("77", CatalogType.FILM, poster = "http://img/p.jpg", plot = "Provider plot").withTmdb(d, 1234, "id", 9L)
        assertEquals("Elwood and his friend.", merged.plot)
        assertEquals("PG", RatingRules.certOf(merged, "au"))
        assertEquals(9L, merged.tmdbAt)
    }

    @Test
    fun `tmdb tv details use content ratings`() {
        val tv = """{"id":5,"name":"Fawlty Towers","first_air_date":"1975-09-19","content_ratings":{"results":[{"iso_3166_1":"AU","rating":"PG"}]},
            "created_by":[{"name":"John Cleese"}],"videos":{"results":[]}}"""
        val d = TmdbApi.parseDetails(tv, CatalogType.SERIES)!!
        assertEquals(mapOf("AU" to "PG"), d.certs)
        assertEquals("John Cleese", d.director)
        assertNull(d.trailer)
    }

    @Test
    fun `search picks the exact title and year, and provider tags are stripped`() {
        assertEquals("Harvey", TmdbApi.searchTitle("EN - Harvey (1950) [HD]"))
        val body = """{"results":[{"id":1,"title":"Harvey Girls","release_date":"1946-01-01"},{"id":2,"title":"Harvey","release_date":"1996-01-01"},{"id":3,"title":"Harvey","release_date":"1950-10-13"}]}"""
        assertEquals(3L, TmdbApi.parseSearch(body, "Harvey", "1950"))
        assertEquals(2L, TmdbApi.parseSearch(body, "Harvey", null))
        assertNull(TmdbApi.parseSearch("""{"results":[]}""", "Harvey", null))
    }

    @Test
    fun `rating lists are sorted by strength`() {
        val body = """{"certifications":{"AU":[{"certification":"M","meaning":"Mature","order":3},{"certification":"G","meaning":"General","order":1}],"XX":[]}}"""
        val l = TmdbApi.parseCertList(body)
        assertEquals(listOf("G", "M"), l["AU"]!!.map { it.certification })
        assertFalse("XX" in l)
    }

    @Test
    fun `read access tokens go in the header, older keys in the query and never in logs`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.abc.def"
        assertEquals("Bearer $token", TmdbApi.headers(token)["Authorization"])
        assertFalse(TmdbApi.movieUrl(1, token).contains("api_key"))
        val url = TmdbApi.movieUrl(1, "0123456789abcdef0123456789abcdef")
        assertTrue(url.contains("api_key=0123456789abcdef0123456789abcdef"))
        assertFalse(FetchErrors.redact("failed $url").contains("0123456789abcdef"))
    }

    @Test
    fun `client finds the film by title when the provider gave no TMDB id`() {
        val seen = mutableListOf<String>()
        val tmdb = TmdbClient(TextFetcher { url, _ ->
            seen += url
            when {
                url.contains("/search/movie") -> HttpResult(200, """{"results":[{"id":1234,"title":"Harvey","release_date":"1950-10-13"}]}""")
                url.contains("/movie/1234") -> HttpResult(200, movieJson)
                url.contains("/configuration") -> HttpResult(401, "{}")
                else -> HttpResult(404, "{}")
            }
        })
        val m = tmdb.enrich(FilmMeta("77", CatalogType.FILM, year = "1950"), "EN - Harvey (1950)", "eyJkey", 3L)
        assertEquals(1234L, m.tmdbId)
        assertEquals("title", m.matched)
        assertEquals("PG", m.certs["AU"])
        assertTrue(seen.first().contains("query=Harvey"))
        assertTrue(tmdb.check("eyJkey") is TmdbClient.Check.Problem)
    }
}
