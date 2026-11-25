package com.ielali.simpletv.data

import com.ielali.simpletv.data.PreviewRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRulesTest {

    private fun live(id: String, sub: String?, host: String = "tv.example.com") =
        Channel(id = id, number = 1, name = id, url = "http://$host/live/alice/s3cret/$id.m3u8", providerId = sub)

    private fun films(id: String, sub: String) =
        Channel(id = id, number = 2, name = id, type = SourceType.FILMS, url = "", playlist = PlaylistSpec(PlaylistMode.FILM, sub))

    private val oneStream: (String) -> Int? = { if (it == "xtream") 1 else null }

    @Test
    fun `nothing playing on the TV means any channel may be previewed`() {
        assertEquals(Verdict.OK, PreviewRules.check(live("a", "xtream"), emptyList(), oneStream))
    }

    @Test
    fun `the channel the TV is showing is never previewed`() {
        assertEquals(Verdict.TV_ON_THIS_CHANNEL, PreviewRules.check(live("a", "xtream"), listOf(live("a", "xtream")), oneStream))
        assertEquals(Verdict.TV_ON_THIS_CHANNEL, PreviewRules.check(live("a", "two"), listOf(live("a", "two")), { 2 }))
    }

    @Test
    fun `a one-stream subscription the TV is using is left to the TV`() {
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "xtream"), listOf(live("a", "xtream")), oneStream))
        // A film channel plays from its subscription too.
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "xtream"), listOf(films("f", "xtream")), oneStream))
    }

    @Test
    fun `a subscription that allows two streams leaves room for a preview`() {
        assertEquals(Verdict.OK, PreviewRules.check(live("b", "two"), listOf(live("a", "two")), { 2 }))
    }

    @Test
    fun `another subscription is another account`() {
        assertEquals(Verdict.OK, PreviewRules.check(live("b", "other"), listOf(live("a", "xtream")), oneStream))
        // Even on the same server.
        assertEquals(Verdict.OK, PreviewRules.check(live("b", "other", "tv.example.com"), listOf(live("a", "xtream", "tv.example.com")), oneStream))
    }

    @Test
    fun `with no limit known, the same server is taken as the same account`() {
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "m3u"), listOf(live("a", "m3u")), oneStream))
        assertEquals(Verdict.OK, PreviewRules.check(live("b", "m3u", "free.example.org"), listOf(live("a", "m3u", "other.example.net")), oneStream))
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "m3u", "TV.Example.com"), listOf(live("a", "m3u", "tv.example.com")), oneStream))
        // A film channel has no address to compare: stay out of its way.
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "m3u"), listOf(films("f", "m3u")), oneStream))
    }

    @Test
    fun `channels added by their link go by the server alone`() {
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", null), listOf(live("a", null)), oneStream))
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", null), listOf(live("a", "xtream")), oneStream))
        assertEquals(Verdict.OK, PreviewRules.check(live("b", null, "free.example.org"), listOf(live("a", "xtream")), oneStream))
    }

    @Test
    fun `a channel from a subscription's list is the TV's channel when it is the same stream`() {
        // The lineup's copy and the list's entry have different ids.
        val onTv = live("lineup-id", "two").copy(sourceId = "41")
        val sameAddress = live("lineup-id", "two").copy(id = "catalog:two:41")
        assertEquals(Verdict.TV_ON_THIS_CHANNEL, PreviewRules.check(sameAddress, listOf(onTv), { 2 }))
        // The provider moved the channel to a new address since it was added: still the same channel.
        val moved = live("moved", "two").copy(id = "catalog:two:41", sourceId = "41")
        assertEquals(Verdict.TV_ON_THIS_CHANNEL, PreviewRules.check(moved, listOf(onTv), { 2 }))
        // Another entry of a two-stream subscription may play.
        assertEquals(Verdict.OK, PreviewRules.check(live("other", "two").copy(id = "catalog:two:42", sourceId = "42"), listOf(onTv), { 2 }))
        // The same number in another subscription's list is another channel.
        assertEquals(Verdict.OK, PreviewRules.check(live("other", "m3u", "free.example.org").copy(sourceId = "41"), listOf(onTv), { 2 }))
    }

    @Test
    fun `the channel in the TV list's preview window counts as well`() {
        val tv = listOf(live("a", "other"), live("c", "xtream"))
        assertEquals(Verdict.TV_USES_SUBSCRIPTION, PreviewRules.check(live("b", "xtream"), tv, oneStream))
        assertEquals(Verdict.TV_ON_THIS_CHANNEL, PreviewRules.check(live("c", "xtream"), tv, oneStream))
    }
}
