package com.ielali.simpletv.config

import com.ielali.simpletv.data.CatalogStatus
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.PlaylistMode
import com.ielali.simpletv.data.PlaylistSpec
import com.ielali.simpletv.data.Provider
import com.ielali.simpletv.data.ProviderKind
import com.ielali.simpletv.data.SourceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiViewsTest {

    private val json = Json { encodeDefaults = true }
    private val secretStream = "http://panel.example:8080/live/alice/s3cret/101.ts"

    private val lineup = listOf(
        Channel(id = "a", number = 2, name = "News", url = secretStream, providerId = "p1", headers = mapOf("User-Agent" to "VLC")),
        Channel(id = "b", number = 1, name = "Sky", type = SourceType.YOUTUBE, url = "UC123"),
        Channel(id = "c", number = 7, name = "Comedy", type = SourceType.FILMS, url = "",
            playlist = PlaylistSpec(PlaylistMode.FILM, "p1", categories = listOf("Comedy"))),
    )

    @Test
    fun `lineup never carries stream addresses or headers, but keeps YouTube sources`() {
        val view = ApiViews.lineup(lineup, mapOf("a" to "missing"))
        val text = json.encodeToString(view)
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("panel.example"))
        assertFalse(text.contains("VLC"))
        assertEquals(listOf(1, 2, 7), view.channels.map { it.number })
        assertEquals("UC123", view.channels.first { it.id == "b" }.source)
        assertNull(view.channels.first { it.id == "a" }.source)
        assertEquals("missing", view.channels.first { it.id == "a" }.flag)
        assertEquals(8, view.nextNumber)
    }

    @Test
    fun `provider view never carries the password or the address`() {
        val p = Provider("p1", "Sunrise", ProviderKind.XTREAM, "http://panel.example:8080", "alice", "s3cret", userAgent = "VLC")
        val view = ApiViews.provider(p, lineup, CatalogStatus(), refreshing = false)
        val text = json.encodeToString(view)
        assertFalse(text.contains("s3cret"))
        assertFalse(text.contains("panel.example"))
        assertTrue(view.hasPassword)
        assertTrue(view.hasUrl)
        assertEquals("alice", view.username)
        // Its live channel and its film channel both go when it is removed.
        assertEquals(listOf(2, 7), view.numbers)
    }

    @Test
    fun `m3u link with an embedded password is not returned`() {
        val p = Provider("p2", "Backup", ProviderKind.M3U, "http://x.example/get.php?username=bob&password=pw9")
        val text = json.encodeToString(ApiViews.provider(p, emptyList(), CatalogStatus(), false))
        assertFalse(text.contains("pw9"))
        assertFalse(text.contains("x.example"))
    }

    @Test
    fun `blank address and password keep the stored ones`() {
        val stored = Provider("p1", "Sunrise", ProviderKind.XTREAM, "http://panel.example:8080", "alice", "s3cret")
        val r = ApiViews.upsert(stored, ApiViews.ProviderInput(id = "p1", name = "Sunrise TV", url = "", username = "alice", password = "")) { "new" }
        val saved = (r as ApiViews.Upsert.Ok).provider
        assertEquals("http://panel.example:8080", saved.url)
        assertEquals("s3cret", saved.password)
        assertEquals("Sunrise TV", saved.name)
        assertEquals("p1", saved.id)
    }

    @Test
    fun `a new password replaces the stored one`() {
        val stored = Provider("p1", "Sunrise", ProviderKind.XTREAM, "http://panel.example:8080", "alice", "old")
        val r = ApiViews.upsert(stored, ApiViews.ProviderInput(id = "p1", name = "Sunrise", password = "new", username = "alice")) { "x" }
        assertEquals("new", (r as ApiViews.Upsert.Ok).provider.password)
    }

    @Test
    fun `new subscriptions need a name, an address and for Xtream a login`() {
        assertTrue(ApiViews.upsert(null, ApiViews.ProviderInput(name = " ", url = "http://a")) { "n" } is ApiViews.Upsert.Invalid)
        assertTrue(ApiViews.upsert(null, ApiViews.ProviderInput(name = "A")) { "n" } is ApiViews.Upsert.Invalid)
        assertTrue(ApiViews.upsert(null, ApiViews.ProviderInput(name = "A", url = "ftp://a", username = "u", password = "p")) { "n" } is ApiViews.Upsert.Invalid)
        assertTrue(ApiViews.upsert(null, ApiViews.ProviderInput(name = "A", url = "http://a")) { "n" } is ApiViews.Upsert.Invalid)
        val ok = ApiViews.upsert(null, ApiViews.ProviderInput(name = "A", kind = ProviderKind.M3U, url = "http://a/list.m3u")) { "n" }
        assertEquals("n", (ok as ApiViews.Upsert.Ok).provider.id)
    }

    @Test
    fun `film and series specs are checked before saving`() {
        assertEquals("Choose a subscription first.", ApiViews.checkPlaylist(PlaylistSpec(PlaylistMode.FILM, "p"), false))
        assertEquals("Choose a series.", ApiViews.checkPlaylist(PlaylistSpec(PlaylistMode.SERIES, "p"), true))
        assertEquals("Choose at least one category or film.", ApiViews.checkPlaylist(PlaylistSpec(PlaylistMode.FILM, "p"), true))
        assertNull(ApiViews.checkPlaylist(PlaylistSpec(PlaylistMode.SERIES, "p", seriesId = "9"), true))
        assertEquals(SourceType.SERIES, ApiViews.filmChannelType(PlaylistSpec(PlaylistMode.SERIES, "p")))
    }

    @Test
    fun `switching how you sign in needs the new address`() {
        val stored = Provider("p1", "Sunrise", ProviderKind.XTREAM, "http://panel.example:8080", "alice", "s3cret")
        val r = ApiViews.upsert(stored, ApiViews.ProviderInput(id = "p1", name = "Sunrise", kind = ProviderKind.M3U)) { "x" }
        assertTrue(r is ApiViews.Upsert.Invalid)
    }
}
