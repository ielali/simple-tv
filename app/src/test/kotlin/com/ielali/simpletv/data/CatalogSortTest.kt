package com.ielali.simpletv.data

import com.ielali.simpletv.data.CatalogSort.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogSortTest {

    private fun film(id: String, name: String, year: String? = null, score: Double? = null, added: Long? = null) =
        CatalogItem(id = id, name = name, type = CatalogType.FILM, group = "Drama", url = "http://x/$id", year = year, score = score, added = added)

    private val items = listOf(
        film("a", "Casablanca", year = "1942", score = 8.5, added = 300),
        film("b", "Amelie", year = "2001", score = 8.3, added = 100),
        film("c", "Unknown film"),
        film("d", "Baraka (1992)", score = 7.8, added = 200),
    )

    private fun names(key: Key, meta: (CatalogItem) -> FilmMeta? = { null }, cert: (CatalogItem, FilmMeta?) -> Int? = { _, _ -> null }) =
        CatalogSort.sort(items, key, meta, cert).map { it.name }

    @Test
    fun `by name, year, score and date added, with unknowns last`() {
        assertEquals(listOf("Amelie", "Baraka (1992)", "Casablanca", "Unknown film"), names(Key.NAME))
        assertEquals(listOf("Amelie", "Baraka (1992)", "Casablanca", "Unknown film"), names(Key.NEWEST))
        assertEquals(listOf("Casablanca", "Baraka (1992)", "Amelie", "Unknown film"), names(Key.OLDEST))
        assertEquals(listOf("Casablanca", "Amelie", "Baraka (1992)", "Unknown film"), names(Key.SCORE))
        assertEquals(listOf("Casablanca", "Baraka (1992)", "Amelie", "Unknown film"), names(Key.ADDED))
    }

    @Test
    fun `checked details win over the provider's own score and year`() {
        val meta = { it: CatalogItem -> if (it.id == "c") FilmMeta("c", CatalogType.FILM, year = "2020", score = 9.1) else null }
        assertEquals("Unknown film", names(Key.SCORE, meta).first())
        assertEquals("Unknown film", names(Key.NEWEST, meta).first())
    }

    @Test
    fun `age rating puts the youngest audience first, unrated last, ties by name`() {
        val order = mapOf("a" to 1, "b" to 0, "d" to 1)
        assertEquals(listOf("Amelie", "Baraka (1992)", "Casablanca", "Unknown film"), names(Key.RATING, cert = { it, _ -> order[it.id] }))
    }

    @Test
    fun `sort names from the page`() {
        assertEquals(Key.NEWEST, CatalogSort.key("newest"))
        assertEquals(Key.ADDED, CatalogSort.key(" Added "))
        assertNull(CatalogSort.key("bogus"))
        assertNull(CatalogSort.key(null))
    }

    @Test
    fun `a film channel can play newest first, and shuffle still wins`() {
        val cat = Catalog("p", 0L, "api", items)
        val spec = PlaylistSpec(PlaylistMode.FILM, "p", categories = listOf("Drama"), sort = "newest")
        assertEquals(listOf("b", "d", "a", "c"), PlaylistQueue.films(cat, spec).map { it.id })
        assertEquals(listOf("a", "d", "b", "c"), PlaylistQueue.films(cat, spec.copy(sort = "oldest")).map { it.id })
        assertEquals("unknown sort plays by name", listOf("b", "d", "a", "c"), PlaylistQueue.films(cat, spec.copy(sort = "bogus")).map { it.id })
        val shuffled = PlaylistQueue.films(cat, spec.copy(sort = "oldest", shuffle = true, seed = 7)).map { it.id }
        assertEquals(PlaylistQueue.films(cat, spec.copy(sort = null, shuffle = true, seed = 7)).map { it.id }, shuffled)
    }

    @Test
    fun `provider scores and dates are read from the catalog`() {
        val body = """[{"stream_id":"1","name":"A","category_id":"1","rating":"7.4","added":"1700000000"},
            {"stream_id":"2","name":"B","category_id":"1","rating":"0","rating_5based":"4.5","added":"x"}]"""
        val films = XtreamApi.parseVodStreams(body, mapOf("1" to "Drama"))
        assertEquals(7.4, films[0].score!!, 0.001)
        assertEquals(1_700_000_000_000L, films[0].added)
        assertEquals(9.0, films[1].score!!, 0.001)
        assertNull(films[1].added)
    }
}
