package com.ielali.simpletv.config

import com.ielali.simpletv.data.HlsRewrite
import com.ielali.simpletv.data.PreviewSessions
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a previewed channel's stream for the page (ADR-029). The browser asks the TV for a made-up
 * name; the TV asks the provider for what is behind it and passes it on: a playlist with its addresses
 * swapped for more made-up names, anything else byte for byte as it arrives, never a whole segment
 * held in memory. The same timeouts as the TV's own player (ADR-028). Android-free.
 */
class PreviewRelay(private val sessions: PreviewSessions, private val userAgent: String) {

    sealed interface Reply {
        /** A playlist, already rewritten. */
        class Playlist(val text: String) : Reply

        /** Video (or a key): [copyTo] passes it on and closes the connection to the provider. */
        class Stream internal constructor(
            val contentType: String?,
            private val body: InputStream,
            private val connection: HttpURLConnection,
            private val stillWanted: () -> Boolean,
        ) : Reply {
            /** Stops as soon as the preview has ended, so the provider's connection is let go at once. */
            fun copyTo(out: OutputStream) {
                try {
                    val buffer = ByteArray(BUFFER)
                    while (stillWanted()) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    out.flush()
                } finally {
                    close()
                }
            }

            fun close() {
                runCatching { body.close() }
                connection.disconnect()
            }
        }

        /** No such preview: it ended, or the name was never handed out. */
        object Gone : Reply

        /** The provider refused or could not be reached. Never carries its answer: that may name the address. */
        object Failed : Reply
    }

    fun fetch(token: String, name: String): Reply {
        val session = sessions.find(token) ?: return Reply.Gone
        val address = sessions.addressOf(session, name) ?: return Reply.Gone
        val connection = try {
            open(address, session.channel.headers)
        } catch (e: Exception) {
            return Reply.Failed
        } ?: return Reply.Failed
        try {
            val body = BufferedInputStream(connection.inputStream, BUFFER)
            body.mark(HEAD)
            val head = ByteArray(HEAD)
            var read = 0
            while (read < HEAD) {
                val n = body.read(head, read, HEAD - read)
                if (n < 0) break
                read += n
            }
            body.reset()
            if (!HlsRewrite.isPlaylist(head, read)) {
                return Reply.Stream(connection.contentType, body, connection) { sessions.find(token) != null }
            }
            val text = body.use { readText(it) }
            connection.disconnect()
            // Relative addresses are worked out against where the playlist really came from, after redirects.
            return Reply.Playlist(HlsRewrite.rewrite(text, connection.url.toString()) { sessions.nameFor(session, it) })
        } catch (e: Exception) {
            connection.disconnect()
            return Reply.Failed
        }
    }

    /** Opens [address], following redirects itself so none is ever passed to the browser. Null when refused. */
    private fun open(address: String, headers: Map<String, String>): HttpURLConnection? {
        var url = URL(address)
        repeat(MAX_REDIRECTS) {
            if (url.protocol != "http" && url.protocol != "https") return null
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", headers["User-Agent"] ?: userAgent)
            for ((k, v) in headers) if (!k.equals("User-Agent", ignoreCase = true)) c.setRequestProperty(k, v)
            val status = c.responseCode
            if (status in 300..399) {
                val to = c.getHeaderField("Location")
                c.disconnect()
                url = URL(url, to ?: return null)
            } else if (status in 200..299) {
                return c
            } else {
                c.disconnect()
                return null
            }
        }
        return null
    }

    private fun readText(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_PLAYLIST) throw IllegalStateException("playlist too long")
        }
        return out.toString("UTF-8")
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val BUFFER = 64 * 1024
        const val HEAD = 16
        const val MAX_REDIRECTS = 5
        const val MAX_PLAYLIST = 2 * 1024 * 1024
    }
}
