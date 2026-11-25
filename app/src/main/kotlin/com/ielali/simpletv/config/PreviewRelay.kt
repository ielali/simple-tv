package com.ielali.simpletv.config

import com.ielali.simpletv.data.HlsRewrite
import com.ielali.simpletv.data.PreviewSessions
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches a previewed channel's stream for the page (ADR-029). The browser asks the TV for a made-up
 * name; the TV asks the provider for what is behind it and passes it on: a playlist with its addresses
 * swapped for more made-up names, anything else byte for byte as it arrives, never a whole segment
 * held in memory. The same timeouts as the TV's own player (ADR-028). Android-free.
 *
 * [log] gets the first few timings of each preview, for the playback log; never an address or a name.
 */
class PreviewRelay(
    private val sessions: PreviewSessions,
    private val userAgent: String,
    private val log: (String) -> Unit = {},
) {

    sealed interface Reply {
        /** A playlist, already rewritten. */
        class Playlist(val text: String) : Reply

        /** Video (or a key): [copyTo] passes it on and closes the connection to the provider. */
        class Stream internal constructor(
            val contentType: String?,
            internal val token: String,
            private val body: InputStream,
            private val connection: HttpURLConnection,
            private val stillWanted: () -> Boolean,
            private val onClose: (Stream) -> Unit,
            private val onPassed: (bytes: Long) -> Unit,
        ) : Reply {
            /** Stops as soon as the preview has ended, so the provider's connection is let go at once. */
            fun copyTo(out: OutputStream) {
                try {
                    val buffer = ByteArray(BUFFER)
                    var bytes = 0L
                    while (stillWanted()) {
                        val n = body.read(buffer)
                        if (n < 0) {
                            out.flush()
                            onPassed(bytes)
                            break
                        }
                        out.write(buffer, 0, n)
                        bytes += n
                    }
                    out.flush()
                } finally {
                    close()
                }
            }

            fun close() {
                runCatching { body.close() }
                connection.disconnect()
                onClose(this)
            }

            /** From another thread: cuts the provider's connection, and the transfer in hand then ends by itself. */
            internal fun drop() {
                connection.disconnect()
                onClose(this)
            }
        }

        /** No such preview: it ended, or the name was never handed out. */
        object Gone : Reply

        /** The provider refused or could not be reached. Never carries its answer: that may name the address. */
        object Failed : Reply

        /** The channel is not an HLS stream, so no browser player here can show it. The preview has ended. */
        object Unplayable : Reply
    }

    private val open = ConcurrentHashMap.newKeySet<Reply.Stream>()
    private val noted = Any()
    private var notedFor: String? = null
    private var notedLines = 0

    fun fetch(token: String, name: String): Reply {
        val session = sessions.find(token) ?: return Reply.Gone
        val address = sessions.addressOf(session, name) ?: return Reply.Gone
        val asked = System.nanoTime()
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
            val firstByteMs = msSince(asked)
            if (!HlsRewrite.isPlaylist(head, read)) {
                if (name == PreviewSessions.INDEX) {
                    // The channel's own address gave video, not a playlist: a browser cannot play that.
                    connection.disconnect()
                    sessions.end(token, PreviewSessions.Ended.UNPLAYABLE)
                    note(token) { "the channel is not HLS, no preview" }
                    return Reply.Unplayable
                }
                val stream = Reply.Stream(
                    connection.contentType, token, body, connection,
                    stillWanted = { sessions.find(token) != null },
                    onClose = { open.remove(it) },
                    onPassed = { bytes ->
                        sessions.pictureStarted(token)
                        val ms = msSince(asked)
                        note(token) {
                            String.format(Locale.ROOT, "video %.1f MB, first byte %d ms, all %d ms, %.1f Mbit/s", bytes / 1e6, firstByteMs, ms, bytes * 8 / 1e3 / ms.coerceAtLeast(1))
                        }
                    },
                )
                open.add(stream)
                // Ended while the provider was answering: nobody would close it otherwise.
                if (!sessions.isLive(token)) {
                    stream.close()
                    return Reply.Gone
                }
                return stream
            }
            val text = body.use { readText(it) }
            connection.disconnect()
            note(token) { "playlist in ${msSince(asked)} ms" }
            // Relative addresses are worked out against where the playlist really came from, after redirects.
            return Reply.Playlist(HlsRewrite.rewrite(text, connection.url.toString()) { sessions.nameFor(session, it) })
        } catch (e: Exception) {
            connection.disconnect()
            return Reply.Failed
        }
    }

    /**
     * Lets go of the provider for every preview that has ended, even one whose browser has stopped
     * reading: the TV must find its subscription free the moment it needs it. Closes sockets, so not
     * for the main thread.
     */
    fun dropEnded() {
        for (stream in open.toList()) if (!sessions.isLive(stream.token)) stream.drop()
    }

    /** The first [LOG_LINES] lines of each preview: enough to see how it started, never a flood. */
    private fun note(token: String, line: () -> String) {
        synchronized(noted) {
            if (notedFor != token) {
                notedFor = token
                notedLines = 0
            }
            if (notedLines >= LOG_LINES) return
            notedLines++
        }
        log("preview: ${line()}")
    }

    private fun msSince(start: Long): Long = (System.nanoTime() - start) / 1_000_000

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
        const val LOG_LINES = 12
    }
}
