package com.ielali.simpletv.data

import java.security.SecureRandom

/**
 * The page's preview player (ADR-029): one preview at a time, held under a random token the browser
 * uses in place of the stream's address. A preview ends when the page closes it, when its picture has
 * played for the time the caregiver chose (none, when they chose no limit), when the browser has asked
 * for nothing for [idleMs] (the tab was closed or went to sleep) or when the TV needs the subscription.
 * Pure; the clock and the tokens are passed in for tests.
 */
class PreviewSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = ::randomToken,
    val idleMs: Long = IDLE_MS,
) {
    enum class Ended { CLOSED, REPLACED, TIMED_OUT, IDLE, TV_NEEDS_IT, UNPLAYABLE }

    /** [maxMs]: how long the picture may play, null for no limit. */
    class Session internal constructor(val token: String, val channel: Channel, val startedAt: Long, val maxMs: Long?) {
        internal var lastSeen = startedAt
        internal var pictureAt: Long? = null

        /** Made-up name to address, the least recently used first: what a long preview no longer asks for goes first. */
        internal val addresses = LinkedHashMap<String, String>(64, 0.75f, true)
        internal val names = HashMap<String, String>()
        internal var next = 0
    }

    private var current: Session? = null
    private var last: Pair<String, Ended>? = null

    /** Starts a preview of [channel]; a preview already running ends. */
    @Synchronized
    fun start(channel: Channel, maxMs: Long? = DEFAULT_MAX_MS): Session {
        current?.let { last = it.token to Ended.REPLACED }
        return Session(newToken(), channel, clock(), maxMs).also {
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

    /**
     * The first video has reached the browser. The time limit counts from here, not from the ask: a
     * channel that takes long to load still plays for the whole time chosen.
     */
    @Synchronized
    fun pictureStarted(token: String) {
        val s = live()?.takeIf { it.token == token } ?: return
        if (s.pictureAt == null) s.pictureAt = clock()
    }

    /** True once [token]'s preview has passed video on. */
    @Synchronized
    fun hasPicture(token: String): Boolean = live()?.takeIf { it.token == token }?.pictureAt != null

    /** Seconds until [token]'s preview stops by itself; null when it has no limit or is not running. */
    @Synchronized
    fun secondsLeft(token: String): Int? {
        val s = live()?.takeIf { it.token == token } ?: return null
        val max = s.maxMs ?: return null
        return ((max - (clock() - (s.pictureAt ?: s.startedAt)) + 999) / 1000).toInt().coerceAtLeast(0)
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
    fun nameFor(session: Session, address: String): String {
        session.names[address]?.let { known ->
            session.addresses[known] // still in a playlist: counts as used
            return known
        }
        if (session.addresses.size >= MAX_NAMES) {
            val oldest = session.addresses.entries.first { it.key != INDEX }
            val (name, gone) = oldest.key to oldest.value
            session.addresses.remove(name)
            session.names.remove(gone)
        }
        val name = "${session.next++}${extension(address)}"
        session.names[address] = name
        session.addresses[name] = address
        return name
    }

    /** The address behind a made-up [name], or null when this preview never handed that name out. */
    @Synchronized
    fun addressOf(session: Session, name: String): String? = session.addresses[name]

    private fun live(): Session? {
        val s = current ?: return null
        val now = clock()
        val why = when {
            // Until there is a picture the limit counts from the ask, so a channel that never loads ends too.
            s.maxMs != null && now - (s.pictureAt ?: s.startedAt) >= s.maxMs -> Ended.TIMED_OUT
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
        const val IDLE_MS = 30_000L

        /** The setting's default, in minutes of picture. */
        const val DEFAULT_MINUTES = 15

        /** The longest limit the setting takes; 0 there means no limit. */
        const val MAX_MINUTES = 240
        const val DEFAULT_MAX_MS = DEFAULT_MINUTES * 60_000L

        /**
         * Names kept per preview. A preview with no time limit names new video for hours, so the least
         * recently used name goes when this many are held; a playlist the player keeps reloading stays.
         */
        const val MAX_NAMES = 600

        /** The session's limit for the setting's [minutes]: null (no limit) for 0 or less. */
        fun limitMs(minutes: Int): Long? = if (minutes <= 0) null else minutes.coerceAtMost(MAX_MINUTES) * 60_000L

        private val random = SecureRandom()
        fun randomToken(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
