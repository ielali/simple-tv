package com.ielali.simpletv.data

import java.security.SecureRandom

/**
 * The page's preview player (ADR-029): one preview at a time, held under a random token the browser
 * uses in place of the stream's address. A preview ends when the page closes it, when it has run
 * [maxMs], when the browser has asked for nothing for [idleMs] (the tab was closed or went to sleep)
 * or when the TV needs the subscription. Pure; the clock and the tokens are passed in for tests.
 */
class PreviewSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = ::randomToken,
    val maxMs: Long = MAX_MS,
    val idleMs: Long = IDLE_MS,
) {
    enum class Ended { CLOSED, REPLACED, TIMED_OUT, IDLE, TV_NEEDS_IT }

    class Session internal constructor(val token: String, val channel: Channel, val startedAt: Long) {
        internal var lastSeen = startedAt
        internal val names = LinkedHashMap<String, String>()
        internal val addresses = HashMap<String, String>()
        internal var next = 0
    }

    private var current: Session? = null
    private var last: Pair<String, Ended>? = null

    /** Starts a preview of [channel]; a preview already running ends. */
    @Synchronized
    fun start(channel: Channel): Session {
        current?.let { last = it.token to Ended.REPLACED }
        return Session(newToken(), channel, clock()).also {
            it.names[channel.url] = INDEX
            it.addresses[INDEX] = channel.url
            current = it
        }
    }

    /** The running preview, or null when none is. */
    @Synchronized
    fun active(): Session? = live()

    /** The running preview when [token] is its token. Counts as the browser still being there. */
    @Synchronized
    fun find(token: String): Session? = live()?.takeIf { it.token == token }?.also { it.lastSeen = clock() }

    /** True while [token]'s preview is still running; does not count as the browser asking. */
    @Synchronized
    fun isLive(token: String): Boolean = live()?.token == token

    /** Why [token]'s preview ended, when it was the last one to end. */
    @Synchronized
    fun whyEnded(token: String): Ended? {
        live()
        return last?.takeIf { it.first == token }?.second
    }

    @Synchronized
    fun end(why: Ended) {
        val s = live() ?: return
        last = s.token to why
        current = null
    }

    /** Ends [token]'s preview only: a page closing an old preview must not end the one that replaced it. */
    @Synchronized
    fun end(token: String, why: Ended) {
        if (live()?.token == token) end(why)
    }

    /** The made-up name the browser gets for [address]; the same address always gets the same name. */
    @Synchronized
    fun nameFor(session: Session, address: String): String = session.names.getOrPut(address) {
        if (session.names.size >= MAX_NAMES) {
            val oldest = session.names.entries.first { it.value != INDEX }
            session.names.remove(oldest.key)
            session.addresses.remove(oldest.value)
        }
        val name = "${session.next++}${extension(address)}"
        session.addresses[name] = address
        name
    }

    /** The address behind a made-up [name], or null when this preview never handed that name out. */
    @Synchronized
    fun addressOf(session: Session, name: String): String? = session.addresses[name]

    private fun live(): Session? {
        val s = current ?: return null
        val now = clock()
        val why = when {
            now - s.startedAt >= maxMs -> Ended.TIMED_OUT
            now - s.lastSeen >= idleMs -> Ended.IDLE
            else -> return s
        }
        last = s.token to why
        current = null
        return null
    }

    /** Players tell a playlist from video by the name's ending, so the made-up name keeps it. */
    private fun extension(address: String): String {
        val file = address.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }

    companion object {
        const val INDEX = "index.m3u8"
        const val MAX_MS = 3 * 60_000L
        const val IDLE_MS = 30_000L
        /** Far more than a live playlist names in [MAX_MS]; only a guard against a playlist that never repeats. */
        const val MAX_NAMES = 600

        private val random = SecureRandom()
        fun randomToken(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
