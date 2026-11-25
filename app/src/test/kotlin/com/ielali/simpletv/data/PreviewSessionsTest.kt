package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSessionsTest {

    private var now = 1_000_000L
    private var made = 0
    private val sessions = PreviewSessions(clock = { now }, newToken = { "token${made++}" })

    private fun channel(id: String) = Channel(id = id, number = 1, name = id, url = "http://tv.example.com/live/alice/s3cret/$id.m3u8")

    @Test
    fun `a preview is found by its token and by no other`() {
        val s = sessions.start(channel("a"))
        assertSame(s, sessions.find("token0"))
        assertNull(sessions.find("token1"))
        assertNull(sessions.find(""))
        assertEquals("http://tv.example.com/live/alice/s3cret/a.m3u8", sessions.addressOf(s, PreviewSessions.INDEX))
    }

    @Test
    fun `only one preview runs at a time`() {
        sessions.start(channel("a"))
        val b = sessions.start(channel("b"))
        assertNull(sessions.find("token0"))
        assertEquals(PreviewSessions.Ended.REPLACED, sessions.whyEnded("token0"))
        assertSame(b, sessions.active())
    }

    @Test
    fun `the same address keeps its name, and a name leads back to its address only`() {
        val s = sessions.start(channel("a"))
        val one = sessions.nameFor(s, "http://tv.example.com/live/alice/s3cret/a_1.ts?t=9")
        val two = sessions.nameFor(s, "http://tv.example.com/live/alice/s3cret/hd/index.m3u8")
        assertEquals("0.ts", one)
        assertEquals("1.m3u8", two)
        assertEquals(one, sessions.nameFor(s, "http://tv.example.com/live/alice/s3cret/a_1.ts?t=9"))
        assertEquals("http://tv.example.com/live/alice/s3cret/a_1.ts?t=9", sessions.addressOf(s, one))
        assertNull(sessions.addressOf(s, "7.ts"))
        assertNull(sessions.addressOf(s, "../channels.json"))
        // An ending that is not a plain file ending is left off.
        assertEquals("2", sessions.nameFor(s, "http://tv.example.com/play/alice/s3cret/41"))
    }

    @Test
    fun `a preview stops after its time is up, however busy the browser is`() {
        sessions.start(channel("a"), maxMs = 3 * 60_000L)
        repeat(17) { now += 10_000; assertTrue(sessions.find("token0") != null) }
        now += 10_000
        assertNull(sessions.find("token0"))
        assertEquals(PreviewSessions.Ended.TIMED_OUT, sessions.whyEnded("token0"))
        assertNull(sessions.active())
    }

    @Test
    fun `the time chosen counts from the first picture, not from the wait before it`() {
        sessions.start(channel("a"), maxMs = 3 * 60_000L)
        assertFalse(sessions.hasPicture("token0"))
        assertEquals(180, sessions.secondsLeft("token0"))
        // The channel took 50 seconds to show anything.
        repeat(5) { now += 10_000; sessions.find("token0") }
        assertEquals(130, sessions.secondsLeft("token0"))
        sessions.pictureStarted("token0")
        assertTrue(sessions.hasPicture("token0"))
        assertEquals(180, sessions.secondsLeft("token0"))
        // More video arriving does not start the clock again.
        now += 10_000
        sessions.find("token0")
        sessions.pictureStarted("token0")
        assertEquals(170, sessions.secondsLeft("token0"))
        repeat(16) { now += 10_000; assertTrue(sessions.find("token0") != null) }
        assertEquals(10, sessions.secondsLeft("token0"))
        now += 10_000
        assertNull(sessions.find("token0"))
        assertEquals(PreviewSessions.Ended.TIMED_OUT, sessions.whyEnded("token0"))
        assertNull(sessions.secondsLeft("token0"))
    }

    @Test
    fun `a channel that never shows a picture still ends when the time is up`() {
        sessions.start(channel("a"), maxMs = 60_000L)
        repeat(5) { now += 10_000; assertTrue(sessions.find("token0") != null) }
        now += 10_000
        assertNull(sessions.find("token0"))
        assertEquals(PreviewSessions.Ended.TIMED_OUT, sessions.whyEnded("token0"))
    }

    @Test
    fun `with no limit a preview plays until it is closed, but not after the browser has gone`() {
        sessions.start(channel("a"), maxMs = null)
        sessions.pictureStarted("token0")
        // Eight hours of a browser asking every ten seconds.
        repeat(8 * 360) { now += 10_000; assertTrue(sessions.find("token0") != null) }
        assertNull(sessions.secondsLeft("token0"))
        now += 30_000
        assertFalse(sessions.isLive("token0"))
        assertEquals(PreviewSessions.Ended.IDLE, sessions.whyEnded("token0"))
    }

    @Test
    fun `the setting's minutes become the limit, and none for no limit`() {
        assertEquals(15, AppSettings().previewMinutes)
        assertEquals(15 * 60_000L, PreviewSessions.limitMs(15))
        assertNull(PreviewSessions.limitMs(0))
        assertNull(PreviewSessions.limitMs(-3))
        assertEquals(240 * 60_000L, PreviewSessions.limitMs(100_000))
        assertEquals(15 * 60_000L, sessions.start(channel("a")).maxMs)
    }

    @Test
    fun `a long preview keeps the playlist it reloads and forgets video it has long played`() {
        val s = sessions.start(channel("a"), maxMs = null)
        val list = sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/hd.m3u8")
        val key = sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/key.bin")
        val first = sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/seg0.ts")
        // Hours of new segments: the player reloads the playlist, and the playlist names its key, each time.
        for (i in 1..5_000) {
            assertEquals("http://tv.example.com/hls/alice/s3cret/a/hd.m3u8", sessions.addressOf(s, list))
            assertEquals(key, sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/key.bin"))
            val name = sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/seg$i.ts")
            assertEquals("http://tv.example.com/hls/alice/s3cret/a/seg$i.ts", sessions.addressOf(s, name))
        }
        assertEquals("http://tv.example.com/live/alice/s3cret/a.m3u8", sessions.addressOf(s, PreviewSessions.INDEX))
        assertEquals("http://tv.example.com/hls/alice/s3cret/a/key.bin", sessions.addressOf(s, key))
        // What was played hours ago no longer leads anywhere.
        assertNull(sessions.addressOf(s, first))
        // An address that comes back after being forgotten gets a new name, never another's.
        val again = sessions.nameFor(s, "http://tv.example.com/hls/alice/s3cret/a/seg0.ts")
        assertNotEquals(first, again)
        assertEquals("http://tv.example.com/hls/alice/s3cret/a/seg0.ts", sessions.addressOf(s, again))
    }

    @Test
    fun `a preview the browser stopped asking for is let go`() {
        sessions.start(channel("a"))
        now += 29_000
        assertTrue(sessions.isLive("token0"))
        now += 1_000
        // Looking does not count as the browser asking.
        assertFalse(sessions.isLive("token0"))
        assertEquals(PreviewSessions.Ended.IDLE, sessions.whyEnded("token0"))
    }

    @Test
    fun `the page is told why its preview ended`() {
        sessions.start(channel("a"))
        assertNull(sessions.whyEnded("token0"))
        sessions.end(PreviewSessions.Ended.TV_NEEDS_IT)
        assertNull(sessions.find("token0"))
        assertEquals(PreviewSessions.Ended.TV_NEEDS_IT, sessions.whyEnded("token0"))
        // Ending again changes nothing.
        sessions.end(PreviewSessions.Ended.CLOSED)
        assertEquals(PreviewSessions.Ended.TV_NEEDS_IT, sessions.whyEnded("token0"))
    }

    @Test
    fun `ending a preview by its token leaves another one alone`() {
        sessions.start(channel("a"))
        val b = sessions.start(channel("b"))
        sessions.end("token0", PreviewSessions.Ended.CLOSED)
        assertSame(b, sessions.active())
        assertEquals(PreviewSessions.Ended.REPLACED, sessions.whyEnded("token0"))
        sessions.end("token1", PreviewSessions.Ended.CLOSED)
        assertNull(sessions.active())
        assertEquals(PreviewSessions.Ended.CLOSED, sessions.whyEnded("token1"))
    }

    @Test
    fun `tokens are long, random and never the same twice`() {
        val a = PreviewSessions.randomToken()
        val b = PreviewSessions.randomToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, b)
    }
}
