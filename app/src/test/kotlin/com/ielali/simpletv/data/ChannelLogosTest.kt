package com.ielali.simpletv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLogosTest {

    private fun objs(json: String): Sequence<JsonObject> = Json.parseToJsonElement(json).jsonArray.asSequence().map { it.jsonObject }

    private val logos = objs("""[
      {"channel":"ABCTV.au","feed":null,"in_use":true,"width":512,"height":512,"format":"PNG","url":"https://l/abc.png"},
      {"channel":"ABCTV.au","feed":null,"in_use":true,"width":2000,"height":800,"format":"SVG","url":"https://l/abc.svg"},
      {"channel":"ABCTV.au","feed":"Sydney","in_use":true,"width":900,"height":400,"format":"PNG","url":"https://l/abc-syd.png"},
      {"channel":"SBS.au","feed":null,"in_use":false,"width":800,"height":400,"format":"PNG","url":"https://l/sbs-old.png"},
      {"channel":"SBS.au","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/sbs.png"},
      {"channel":"Nine.au","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/nine-au.png"},
      {"channel":"Nine.uk","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/nine-uk.png"},
      {"channel":"Gem.au","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/gem-au.png"},
      {"channel":"Gem.nz","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/gem-nz.png"},
      {"channel":"Gem.uk","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/gem-uk.png"},
      {"channel":"Adult.xx","feed":null,"in_use":true,"width":300,"height":150,"format":"PNG","url":"https://l/x.png"}
    ]""")

    private val channels = objs("""[
      {"id":"ABCTV.au","name":"ABC TV","alt_names":["ABC"],"country":"AU","is_nsfw":false,"closed":null},
      {"id":"SBS.au","name":"SBS","alt_names":[],"country":"AU","is_nsfw":false},
      {"id":"Nine.au","name":"Nine","country":"AU"},
      {"id":"Nine.uk","name":"Nine","country":"UK"},
      {"id":"Gem.au","name":"Gem","country":"AU"},
      {"id":"Gem.nz","name":"Gem","country":"NZ"},
      {"id":"Gem.uk","name":"Gem","country":"UK"},
      {"id":"Adult.xx","name":"Adult Channel","country":"US","is_nsfw":true},
      {"id":"NoLogo.au","name":"No Logo TV","country":"AU"}
    ]""")

    private val best = ChannelLogos.bestLogos(logos)
    private val idx = ChannelLogos.build(channels, best, 1L)
    private fun ch(name: String, epg: String? = null) = Channel(id = name, number = 1, name = name, url = "http://x", epgId = epg)

    @Test
    fun `the best logo is a current, main, drawable picture`() {
        assertEquals("raster beats a bigger SVG; main beats a feed", "https://l/abc.png", best["abctv.au"])
        assertEquals("a logo in use beats a bigger old one", "https://l/sbs.png", best["sbs.au"])
    }

    @Test
    fun `found by guide id, then by name, also its other names and with HD and country tags`() {
        assertEquals("https://l/abc.png", ChannelLogos.find(ch("Something else", epg = "abctv.au"), idx, "AU"))
        assertEquals("https://l/abc.png", ChannelLogos.find(ch("ABC HD"), idx, "AU"))
        assertEquals("https://l/sbs.png", ChannelLogos.find(ch("SBS"), idx, "GB"))
    }

    @Test
    fun `an ambiguous name is settled by the TV's country, or not at all`() {
        assertEquals("https://l/nine-au.png", ChannelLogos.find(ch("Nine"), idx, "AU"))
        assertEquals("https://l/gem-nz.png", ChannelLogos.find(ch("Gem"), idx, "nz"))
        assertNull("three Gems and none in the TV's country", ChannelLogos.find(ch("Gem"), idx, "US"))
    }

    @Test
    fun `adult and logo-less channels are left out`() {
        assertNull(ChannelLogos.find(ch("Adult Channel"), idx, "US"))
        assertNull(ChannelLogos.find(ch("No Logo TV"), idx, "AU"))
        assertNull(ChannelLogos.find(ch("Totally unknown"), idx, "AU"))
    }

    @Test
    fun `the picker finds channels by part of their name, the TV's country first`() {
        val gems = ChannelLogos.search("gem", idx, "NZ")
        assertEquals("https://l/gem-nz.png", gems.first().url)
        assertEquals(3, gems.size)
        assertEquals("Gem (NZ)", gems.first().label)
        assertEquals(listOf("https://l/abc.png"), ChannelLogos.search("ABC", idx, "AU").map { it.url })
        assertEquals(emptyList<ChannelLogos.Choice>(), ChannelLogos.search("x", idx, "AU"))
    }
}
