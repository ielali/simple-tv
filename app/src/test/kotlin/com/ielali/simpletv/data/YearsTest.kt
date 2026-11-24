package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YearsTest {

    private fun film(id: String, name: String, year: String? = null, group: String = "Action") =
        CatalogItem(id = id, name = name, type = CatalogType.FILM, group = group, url = "http://x/$id.mp4", year = year)

    @Test
    fun `a title's year comes from the catalog, its details, then its name`() {
        assertEquals(2019, Years.of(film("1", "Heat", year = "2019-05-04")))
        assertEquals(1995, Years.of(film("1", "Heat"), FilmMeta("1", CatalogType.FILM, year = "1995")))
        assertEquals(1995, Years.of(film("1", "Heat (1995)")))
        assertEquals(1995, Years.of(film("1", "Heat - 1995")))
        assertEquals(2001, Years.of(film("1", "Ali [2001] HD")))
        assertNull("a number inside the name is not a year", Years.of(film("1", "Film 2012 Part 2")))
        assertNull(Years.of(film("1", "Heat", year = "0")))
    }

    @Test
    fun `ranges can be open at either end, and unknown years play unless left out`() {
        assertTrue(Years.inRange(1990, null, null))
        assertTrue(Years.inRange(1990, 1980, 1999))
        assertFalse(Years.inRange(2005, 1980, 1999))
        assertTrue(Years.inRange(2024, 2015, null))
        assertFalse(Years.inRange(1970, 1980, null))
        assertTrue(Years.inRange(null, 2015, null))
        assertFalse(Years.inRange(null, 2015, null, skipUnknown = true))
    }

    @Test
    fun `years are kept within reason and in order`() {
        val spec = PlaylistSpec(PlaylistMode.FILM, "p", minYear = 2020, maxYear = 1990, skipNoYear = true)
        val s = Years.sanitize(spec)
        assertEquals(1990, s.minYear); assertEquals(2020, s.maxYear); assertTrue(s.skipNoYear)
        val odd = Years.sanitize(PlaylistSpec(PlaylistMode.FILM, "p", minYear = 15, maxYear = 99999, skipNoYear = true))
        assertNull(odd.minYear); assertNull(odd.maxYear); assertFalse(odd.skipNoYear)
    }

    @Test
    fun `a film channel draws only on films from its years`() {
        val cat = Catalog("p", 0L, "api", listOf(
            film("a", "Old (1975)"), film("b", "New", year = "2021"), film("c", "Unknown"), film("d", "Mid", year = "1999"),
        ))
        val spec = PlaylistSpec(PlaylistMode.FILM, "p", categories = listOf("Action"), minYear = 1990)
        assertEquals(listOf("Mid", "New", "Unknown"), PlaylistQueue.candidates(cat, spec).map { it.name })
        val spec2 = spec.copy(maxYear = 2000)
        assertEquals(listOf("Mid", "Unknown"), PlaylistQueue.candidates(cat, spec2).map { it.name })
    }
}
