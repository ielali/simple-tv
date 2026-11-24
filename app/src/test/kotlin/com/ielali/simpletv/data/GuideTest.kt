package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class GuideTest {

    private fun t(s: String) = XmltvTime.parse(s)!!

    @Test
    fun `xmltv times honour their offset and default to UTC`() {
        assertEquals(t("20261124083000 +0000"), t("20261124193000 +1100"))
        assertEquals(t("20261124083000 +0000"), t("20261124083000"))
        assertEquals(t("20261124083000 +0000"), t("20261124113000 +03:00"))
        assertNull(XmltvTime.parse("soon"))
    }

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="ABC.au"><display-name>ABC TV Sydney</display-name><display-name>ABC</display-name></channel>
          <channel id="Seven.au"><display-name>7 Sydney</display-name></channel>
          <channel id="Other.us"><display-name>Other</display-name></channel>
          <programme start="20261124180000 +1100" stop="20261124190000 +1100" channel="ABC.au">
            <title lang="en">ABC News</title><desc>The day's news.</desc><category>News</category>
          </programme>
          <programme start="20261124190000 +1100" stop="20261124193000 +1100" channel="ABC.au">
            <title>Gardening Australia</title><episode-num system="xmltv_ns">35.3.0/1</episode-num><icon src="x"/>
          </programme>
          <programme start="20261130190000 +1100" stop="20261130193000 +1100" channel="ABC.au"><title>Too far ahead</title></programme>
          <programme start="20261124180000 +1100" stop="20261124190000 +1100" channel="Other.us"><title>Not wanted</title></programme>
        </tv>"""

    @Test
    fun `xmltv reader keeps wanted channels inside the window`() {
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val channels = mutableMapOf<String, List<String>>()
        val progs = mutableListOf<Pair<String, Programme>>()
        XmltvReader.read(parser, t("20261124060000"), t("20261126000000"), { it != "Other.us" }, { id, n -> channels[id] = n }, { c, p -> progs += c to p })
        assertEquals(listOf("ABC TV Sydney", "ABC"), channels["ABC.au"])
        assertEquals(3, channels.size)
        assertEquals(listOf("ABC News", "Gardening Australia"), progs.map { it.second.title })
        assertEquals("News", progs[0].second.category)
        assertEquals("The day's news.", progs[0].second.desc)
        assertEquals("S36 E4", progs[1].second.episode)
    }

    @Test
    fun `now and next`() {
        val list = listOf(Programme(100, 200, "A"), Programme(200, 300, "B"), Programme(300, 400, "C"))
        val nn = NowNext.of(list, 250, "guide")
        assertEquals("B", nn.now?.title); assertEquals("C", nn.next?.title)
        val gap = NowNext.of(list, 50, null)
        assertNull(gap.now); assertEquals("A", gap.next?.title)
    }

    @Test
    fun `names are tidied and matched`() {
        assertEquals("7", GuideMatch.normalise("AU: Seven HD (Sydney)"))
        assertEquals("abc", GuideMatch.normalise("ABC TV Sydney"))
        assertEquals("al jazeera", GuideMatch.normalise("AR | Al Jazeera Arabic FHD"))
        assertEquals("9", GuideMatch.normalise("Channel 9"))
        val index = GuideMatch.Index(listOf(
            GuideChannel("g|ABC.au", "ABC.au", listOf("ABC TV Sydney"), "g"),
            GuideChannel("g|Seven.au", "Seven.au", listOf("7 Sydney"), "g"),
        ))
        val ch = Channel("c1", 1, "Seven HD", url = "x")
        assertEquals("g|Seven.au", GuideMatch.match(ch, index))
        assertEquals("g|ABC.au", GuideMatch.match(ch.copy(name = "Something", epgId = "abc.AU"), index))
        assertNull(GuideMatch.match(ch.copy(name = "Unknown channel"), index))
    }

    @Test
    fun `xtream short guide decodes base64 and uses timestamps`() {
        val body = """{"epg_listings":[
            {"id":"1","title":"QUJDIE5ld3M=","description":"VGhlIGRheSdzIG5ld3Mu","start":"2026-11-24 18:00:00","end":"2026-11-24 19:00:00","start_timestamp":"1795935600","stop_timestamp":"1795939200"},
            {"id":"2","title":"Plain title","description":"","start_timestamp":1795939200,"stop_timestamp":1795941000}]}"""
        val list = XtreamGuide.parse(body)
        assertEquals("ABC News", list[0].title)
        assertEquals("The day's news.", list[0].desc)
        assertEquals(1795935600000L, list[0].start)
        assertEquals("Plain title", list[1].title)
        assertNull(list[1].desc)
        assertTrue(XtreamGuide.parse("[]").isEmpty())
        // Words that happen to look like base64 but decode to junk stay as they are.
        assertEquals("News", XtreamGuide.maybeBase64("News"))
    }

    @Test
    fun `m3u keeps guide ids and the playlist's guide link`() {
        val text = "#EXTM3U x-tvg-url=\"http://guide.example/epg.xml.gz,http://other\"\n#EXTINF:-1 tvg-id=\"ABC.au\" group-title=\"AU\",ABC\nhttp://s/1.m3u8\n"
        assertEquals("http://guide.example/epg.xml.gz", M3uParser.guideUrl(text))
        assertEquals("ABC.au", M3uParser.parse(text).single().epgId)
        assertNull(M3uParser.guideUrl("#EXTM3U\n"))
    }

    @Test
    fun `presets and defaults`() {
        assertEquals(listOf("epgshare01-au"), GuidePresets.defaultFor("au"))
        assertTrue(GuidePresets.defaultFor("FR").isEmpty())
        assertTrue(GuidePresets.ALL.all { it.url.startsWith("https://") })
    }
}
