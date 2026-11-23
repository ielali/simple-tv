package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class FetchErrorsTest {

    @Test
    fun `redacts xtream credentials in query strings and stream paths`() {
        assertEquals(
            "http://tv.example:8080/get.php?username=***&password=***&type=m3u_plus",
            FetchErrors.redact("http://tv.example:8080/get.php?username=bob&password=hunter2&type=m3u_plus"),
        )
        assertEquals("http://h/live/***/***/101.m3u8", FetchErrors.redact("http://h/live/bob/hunter2/101.m3u8"))
        assertEquals("https://***@h/x", FetchErrors.redact("https://bob:hunter2@h/x"))
    }

    @Test
    fun `explains common failures in plain words`() {
        assertTrue(FetchErrors.forHttp(403).contains("username and password"))
        assertTrue(FetchErrors.forHttp(404).contains("M3U link"))
        assertTrue(FetchErrors.forHttp(503).contains("Try again later"))
        assertTrue(FetchErrors.forNetwork(UnknownHostException("x")).contains("could not be found"))
    }
}
