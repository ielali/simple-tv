package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CatalogRulesTest {

    private fun live(id: String, name: String = "Ch $id", group: String? = "News") =
        CatalogItem(id = id, name = name, type = CatalogType.LIVE, group = group, url = "http://p/live/u/p/$id.m3u8")
    private fun film(id: String, name: String, group: String) = CatalogItem(id = id, name = name, type = CatalogType.FILM, group = group)
    private fun catalog(vararg items: CatalogItem) = Catalog("p1", 0, "Xtream API", items.toList())

    // ---- refresh schedule
    private val zone = ZoneId.of("Australia/Sydney")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `refresh runs once per scheduled moment and catches up after the TV was off`() {
        val time = "21:00"
        val before = ms(2026, 11, 23, 20, 59)
        val after = ms(2026, 11, 23, 21, 1)
        val lastRunYesterday = ms(2026, 11, 22, 21, 0)
        assertFalse(RefreshSchedule.isDue(before, lastRunYesterday, time, zone))
        assertTrue(RefreshSchedule.isDue(after, lastRunYesterday, time, zone))
        assertFalse(RefreshSchedule.isDue(after, after, time, zone))
        // switched off for two days: due as soon as it is back
        assertTrue(RefreshSchedule.isDue(ms(2026, 11, 25, 8, 0), lastRunYesterday, time, zone))
        assertEquals(ms(2026, 11, 24, 21, 0), RefreshSchedule.nextDue(after, time, zone))
        assertEquals(ms(2026, 11, 23, 21, 0), RefreshSchedule.nextDue(before, time, zone))
    }

    @Test
    fun `refresh time is validated`() {
        assertEquals("07:05", RefreshSchedule.normalise("7:05"))
        assertEquals("04:00", RefreshSchedule.normalise("25:00"))
        assertEquals("04:00", RefreshSchedule.normalise(null))
    }

    // ---- status and diff
    @Test
    fun `status keeps the last good copy information when a refresh fails`() {
        val old = catalog(live("1"), live("2"), live("3"))
        val new = catalog(live("2"), live("3"), live("4"), live("5"))
        val diff = CatalogDiff.live(old, new)
        assertEquals(2 to 1, diff.added to diff.removed)
        val ok = CatalogStatus().record(RefreshRun(100, true, diff.added, diff.removed), new, null)
        val failed = ok.record(RefreshRun(200, false, message = "The provider didn't answer."), null, null)
        assertEquals(100L, failed.lastGood)
        assertEquals(200L, failed.lastRefresh)
        assertFalse(failed.ok)
        assertEquals("The provider didn't answer.", failed.error)
        assertEquals(4, failed.counts[CatalogType.LIVE])
        assertEquals(2, failed.added)
        assertEquals(listOf(200L, 100L), failed.history.map { it.at })
    }

    @Test
    fun `history is capped`() {
        var s = CatalogStatus()
        repeat(15) { s = s.record(RefreshRun(it.toLong(), true), null, null) }
        assertEquals(CatalogStatus.MAX_HISTORY, s.history.size)
        assertEquals(14L, s.history.first().at)
    }

    // ---- queries
    private val lineup = listOf(
        Channel(id = "a", number = 4, name = "Two", url = "x", providerId = "p1", sourceId = "2"),
        Channel(id = "b", number = 9, name = "Other", url = "y", providerId = "p2", sourceId = "1"),
    )

    @Test
    fun `categories are counted largest first with uncategorised named`() {
        val c = catalog(live("1", group = "UK"), live("2", group = "News"), live("3", group = "News"), live("4", group = null))
        assertEquals(listOf("News" to 2, "No category" to 1, "UK" to 1), CatalogQuery.categories(c, CatalogType.LIVE).map { it.name to it.count })
        assertEquals("", CatalogQuery.categories(c, CatalogType.LIVE)[1].id)
    }

    @Test
    fun `items page, search across categories and mark what is in the lineup`() {
        val c = catalog(*(1..120).map { live("$it", name = if (it % 2 == 0) "BBC News $it" else "Sport $it", group = if (it <= 60) "UK" else "World") }.toTypedArray())
        val page = CatalogQuery.items(c, CatalogType.LIVE, null, "bbc news", 0, 50, lineup, "p1")
        assertEquals(60, page.total)
        assertEquals(50, page.items.size)
        val inLineup = page.items.first { it.id == "2" }
        assertTrue(inLineup.inLineup)
        assertEquals(4, inLineup.lineupNumber)
        assertFalse(page.items.first { it.id == "4" }.inLineup) // id 1 of p2 must not count for p1
        val world = CatalogQuery.items(c, CatalogType.LIVE, "World", null, 50, 50, lineup, "p1")
        assertEquals(60, world.total)
        assertEquals(10, world.items.size)
    }

    @Test
    fun `item views never carry addresses`() {
        val fields = CatalogQuery.ItemView::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("url", ignoreCase = true) || it == "headers" })
    }

    // ---- flags, edits, repair, import
    @Test
    fun `channels the provider dropped are flagged, failed ones too`() {
        val c = catalog(live("1"))
        val chans = listOf(
            Channel(id = "keep", number = 1, name = "A", url = "x", providerId = "p1", sourceId = "1"),
            Channel(id = "gone", number = 2, name = "B", url = "x", providerId = "p1", sourceId = "9"),
            Channel(id = "yt", number = 3, name = "C", url = "UC", type = SourceType.YOUTUBE),
            Channel(id = "nocache", number = 4, name = "D", url = "x", providerId = "p2", sourceId = "9"),
        )
        val flags = LineupFlags.compute(chans, mapOf("p1" to c), setOf("yt"))
        assertEquals(mapOf("gone" to LineupFlags.MISSING, "yt" to LineupFlags.FAILED), flags)
    }

    @Test
    fun `edits keep stored addresses, follow the new order and drop removed rows`() {
        val stored = listOf(
            Channel(id = "1", number = 1, name = "A", url = "secret-a", headers = mapOf("User-Agent" to "x")),
            Channel(id = "2", number = 2, name = "B", url = "secret-b"),
            Channel(id = "3", number = 3, name = "C", url = "secret-c"),
        )
        val out = LineupEdit.apply(stored, listOf(LineupEdit.Row("3", 1, "Cee"), LineupEdit.Row("1", 2, "  "), LineupEdit.Row("evil", 3, "X")))
        assertEquals(listOf("3", "1"), out.map { it.id })
        assertEquals(listOf("secret-c", "secret-a"), out.map { it.url })
        assertEquals(listOf("Cee", "A"), out.map { it.name })
        assertEquals("x", out[1].headers["User-Agent"])
    }

    @Test
    fun `a refresh repairs addresses and backfills source ids`() {
        val provider = Provider(id = "p1", name = "P", url = "h", username = "u", password = "new")
        val c = catalog(live("1").copy(url = "http://p/live/u/new/1.m3u8", headers = mapOf("User-Agent" to "VLC")), live("2"))
        val chans = listOf(
            Channel(id = "a", number = 1, name = "A", url = "http://p/live/u/old/1.m3u8", providerId = "p1", sourceId = "1"),
            Channel(id = "b", number = 2, name = "B", url = "http://p/live/u/p/2.m3u8", providerId = "p1"),
            Channel(id = "c", number = 3, name = "C", url = "other", providerId = "p9", sourceId = "1"),
        )
        val out = LineupRepair.refresh(chans, provider, c)
        assertEquals("http://p/live/u/new/1.m3u8", out[0].url)
        assertEquals("VLC", out[0].headers["User-Agent"])
        assertEquals("2", out[1].sourceId)
        assertEquals("other", out[2].url)
    }

    @Test
    fun `catalog import skips channels already added by source id and reports numbers`() {
        val provider = Provider(id = "p1", name = "P", url = "h")
        val existing = listOf(Channel(id = "a", number = 7, name = "A", url = "old-address", providerId = "p1", sourceId = "1"))
        val r = ProviderImport.merge(existing, ProviderImport.fromCatalog(listOf(live("1"), live("2"), live("3"))), provider)
        assertEquals(2, r.added)
        assertEquals(1, r.skipped)
        assertEquals(listOf(8, 9), r.numbers)
        assertTrue(r.channels.drop(1).all { it.providerId == "p1" && it.sourceId != null })
    }

    // ---- queues
    private val films = catalog(film("f1", "Harvey", "Comedy"), film("f2", "Apartment", "Comedy"), film("f3", "Sabrina", "Drama"), film("f4", "Ben-Hur", "Epic"))

    @Test
    fun `film queue from categories is alphabetical, or shuffled stably`() {
        val spec = PlaylistSpec(PlaylistMode.FILM, "p1", categories = listOf("Comedy", "Drama"))
        assertEquals(listOf("f2", "f1", "f3"), PlaylistQueue.films(films, spec).map { it.id })
        val s1 = PlaylistQueue.films(films, spec.copy(shuffle = true, seed = 42)).map { it.id }
        val s2 = PlaylistQueue.films(films, spec.copy(shuffle = true, seed = 42)).map { it.id }
        assertEquals(s1, s2)
        assertEquals(setOf("f1", "f2", "f3"), s1.toSet())
    }

    @Test
    fun `hand-picked titles keep their order, pinned go first, excluded go out`() {
        val spec = PlaylistSpec(PlaylistMode.FILM, "p1", titles = listOf("f4", "f1", "f3", "missing"), first = listOf("f3"), excluded = listOf("f1"))
        assertEquals(listOf("f3", "f4"), PlaylistQueue.films(films, spec).map { it.id })
    }

    @Test
    fun `series queue labels episodes, resumes and wraps`() {
        val eps = listOf(Episode("e1", 1, 1, "Pilot", "mp4", 30), Episode("e2", 1, 2, "", "mp4", null), Episode("e3", 2, 1, "Back"))
        val q = PlaylistQueue.episodes(eps, PlaylistSpec(PlaylistMode.SERIES, "p1", seriesId = "s"))
        assertEquals("Season 1, episode 1 · Pilot", q[0].title)
        assertEquals("Season 1, episode 2", q[1].title)
        assertEquals("30 min", q[0].detail)
        assertEquals(1, PlaylistQueue.resumeIndex(q, "e2"))
        assertEquals(0, PlaylistQueue.resumeIndex(q, "gone"))
        assertEquals(0, PlaylistQueue.nextIndex(q, 2))
        assertEquals("2 h 5 min", PlaylistQueue.formatMinutes(125))
    }

    @Test
    fun `xtream vod parsing tolerates missing fields`() {
        val items = XtreamApi.parseVodStreams("""[{"stream_id":1},{"name":"x"},{"stream_id":"2","name":"Gone (2001)"}]""", emptyMap())
        assertEquals(listOf("Film 1", "Gone (2001)"), items.map { it.name })
        assertEquals("2001", items[1].year)
        assertNull(items[0].group)
    }
}
