package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Real sockets via java.net.ServerSocket (available in android.jar, unlike the JDK HTTP server). */
class HttpFetcherTest {

    /** Serves one raw HTTP response and records the request headers it received. */
    private fun serveOnce(status: String, body: String, seen: MutableList<String>): Int {
        val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            socket.use { s ->
                s.accept().use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        synchronized(seen) { seen += line }
                    }
                    val bytes = body.toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        return socket.localPort
    }

    @Test
    fun `returns body and sends headers on success`() {
        val seen = mutableListOf<String>()
        val port = serveOnce("200 OK", "#EXTM3U", seen)
        val r = HttpFetcher(2_000, 5_000).get("http://127.0.0.1:$port/list.m3u", mapOf("User-Agent" to "UA-test"))
        assertTrue(r.ok)
        assertEquals("#EXTM3U", r.body)
        assertTrue(synchronized(seen) { seen.any { it.equals("User-Agent: UA-test", ignoreCase = true) } })
    }

    @Test
    fun `error statuses are results, not exceptions`() {
        val port = serveOnce("403 Forbidden", "blocked", mutableListOf())
        val r = HttpFetcher(2_000, 5_000).get("http://127.0.0.1:$port/get.php", emptyMap())
        assertFalse(r.ok)
        assertEquals(403, r.code)
        assertEquals("blocked", r.body)
    }

    @Test(expected = IOException::class)
    fun `unreachable servers throw`() {
        val free = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        HttpFetcher(1_000, 1_000).get("http://127.0.0.1:$free/", emptyMap())
    }
}
