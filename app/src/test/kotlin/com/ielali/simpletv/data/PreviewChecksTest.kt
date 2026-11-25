package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreviewChecksTest {

    private var now = 1_000L

    private fun channel(id: String, sub: String? = null, source: String? = null) =
        Channel(id = id, number = 1, name = id, url = "http://tv.example.com/live/alice/s3cret/$id.m3u8", providerId = sub, sourceId = source)

    @Test
    fun `a mark is the last thing a preview showed`() {
        val checks = PreviewChecks(clock = { now })
        val a = channel("a")
        assertNull(checks.of(a))
        checks.record(a, PreviewResult.FAILED)
        assertEquals(PreviewCheck(PreviewResult.FAILED, 1_000L), checks.of(a))
        now = 2_000L
        checks.record(a, PreviewResult.WORKED)
        assertEquals(PreviewCheck(PreviewResult.WORKED, 2_000L), checks.of(a))
        assertNull(checks.of(channel("b")))
    }

    @Test
    fun `a channel tried in a subscription's list keeps its mark in the lineup`() {
        val checks = PreviewChecks(clock = { now })
        // In the list it has a made-up id; in the lineup, its own.
        checks.record(channel("catalog:m3u:7", "m3u", "7"), PreviewResult.WORKED)
        assertEquals(PreviewResult.WORKED, checks.of(channel("lineup-id", "m3u", "7"))?.result)
        // The same number in another subscription is another channel.
        assertNull(checks.of(channel("x", "other", "7")))
        assertEquals(setOf("m3u/7"), checks.listed().keys)
    }

    @Test
    fun `a channel with no subscription goes by its own id, and stays out of the lists' marks`() {
        val checks = PreviewChecks(clock = { now })
        checks.record(channel("link-1"), PreviewResult.UNPLAYABLE)
        // A subscription's channel imported before source ids were kept has no source id either.
        checks.record(channel("old-import", "xtream"), PreviewResult.FAILED)
        assertEquals(PreviewResult.UNPLAYABLE, checks.of(channel("link-1"))?.result)
        assertEquals(PreviewResult.FAILED, checks.of(channel("old-import", "xtream"))?.result)
        assertTrue(checks.listed().isEmpty())
    }

    @Test
    fun `marks are still there after the TV restarts`() {
        val file = File.createTempFile("preview_checks", ".json")
        try {
            file.delete()
            val first = PreviewChecks(file, clock = { now })
            first.record(channel("a", "m3u", "7"), PreviewResult.WORKED)
            first.record(channel("b"), PreviewResult.FAILED)
            // Nothing in the file says where a stream is.
            val text = file.readText()
            assertFalse(text, text.contains("alice") || text.contains("s3cret") || text.contains("example.com"))

            val again = PreviewChecks(file, clock = { now })
            assertEquals(PreviewCheck(PreviewResult.WORKED, 1_000L), again.of(channel("a", "m3u", "7")))
            assertEquals(PreviewResult.FAILED, again.of(channel("b"))?.result)

            // A file that cannot be read is no marks, not a crash.
            file.writeText("{ not json")
            assertNull(PreviewChecks(file).of(channel("b")))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the oldest marks go when there are too many`() {
        val checks = PreviewChecks(clock = { now })
        for (i in 0 until PreviewChecks.MAX + 5) {
            now = 1_000L + i
            checks.record(channel("c$i", "m3u", "$i"), PreviewResult.WORKED)
        }
        assertEquals(PreviewChecks.MAX, checks.listed().size)
        assertNull(checks.of(channel("x", "m3u", "0")))
        assertNull(checks.of(channel("x", "m3u", "4")))
        assertEquals(PreviewResult.WORKED, checks.of(channel("x", "m3u", "5"))?.result)
        assertEquals(PreviewResult.WORKED, checks.of(channel("x", "m3u", "${PreviewChecks.MAX + 4}"))?.result)
    }
}
