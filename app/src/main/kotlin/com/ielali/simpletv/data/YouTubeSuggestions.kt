package com.ielali.simpletv.data

import android.content.Context
import com.ielali.simpletv.youtube.YouTubeAccount
import com.ielali.simpletv.youtube.YouTubeAccount.Outcome
import com.ielali.simpletv.youtube.YouTubeAccount.Poll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Suggestions for the "From YouTube" tab (ADR-021): the household's YouTube subscriptions and which
 * are live now. The TV signs in with the device flow using the household's own OAuth client; the
 * refresh token stays in SecretsStore and is never returned. Subscriptions are refreshed daily and
 * "live now" at most every 30 minutes, using the uploads playlists (1 quota unit each), never the
 * expensive search call. Cached YouTube data older than 30 days is deleted (API policy).
 */
class YouTubeSuggestions(
    context: Context,
    private val scope: CoroutineScope,
    private val secrets: SecretsStore,
    private val api: YouTubeAccount,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Cache(
        val accountName: String? = null,
        val subs: List<YouTubeAccount.SubChannel> = emptyList(),
        val fetchedAt: Long = 0L,
        val uploads: Map<String, String> = emptyMap(),
        val uploadsAt: Long = 0L,
        val live: List<YouTubeAccount.LiveVideo> = emptyList(),
        val liveCheckedAt: Long = 0L,
    )

    @Serializable
    data class Pending(val userCode: String, val verificationUrl: String, val expiresAt: Long)

    private val store = JsonStore(File(context.filesDir, "youtube_account.json"), Cache.serializer()) { Cache() }
    private val lock = Mutex()
    @Volatile private var access: Pair<String, Long>? = null
    @Volatile var pending: Pending? = null
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var checking: Boolean = false
        private set
    private var pollJob: Job? = null

    fun cache(): Cache = store.current().let { c -> if (c.fetchedAt > 0 && clock() - c.fetchedAt > MAX_AGE) Cache(accountName = c.accountName) else c }
    fun hasClient() = !secrets.current().ytClientId.isNullOrBlank() && !secrets.current().ytClientSecret.isNullOrBlank()
    fun connected() = !secrets.current().ytRefreshToken.isNullOrBlank()
    fun clientId(): String? = secrets.current().ytClientId

    suspend fun saveClient(id: String?, secret: String?) {
        val s = secrets.current()
        secrets.setYouTubeClient(id?.trim()?.ifEmpty { null } ?: s.ytClientId, secret?.ifEmpty { null } ?: s.ytClientSecret)
        error = null
    }

    /** Starts "go to google.com/device and enter the code"; the TV then waits for the phone. */
    suspend fun connect(): Boolean {
        val id = secrets.current().ytClientId ?: return false.also { error = "Add the client ID and secret first." }
        val secret = secrets.current().ytClientSecret ?: return false
        return when (val r = withContext(Dispatchers.IO) { api.start(id) }) {
            is Outcome.Problem -> { error = r.message; false }
            is Outcome.Ok -> {
                val dc = r.value
                pending = Pending(dc.userCode, dc.verificationUrl, clock() + dc.expiresIn * 1000L)
                error = null
                pollJob?.cancel()
                pollJob = scope.launch {
                    var interval = dc.interval.coerceAtLeast(5) * 1000L
                    while (pending != null && clock() < pending!!.expiresAt) {
                        delay(interval)
                        when (val p = withContext(Dispatchers.IO) { api.poll(id, secret, dc.deviceCode) }) {
                            is Poll.Pending -> Unit
                            is Poll.SlowDown -> interval += 5000
                            is Poll.Stop -> { error = p.message; pending = null }
                            is Poll.Signed -> {
                                access = p.accessToken to (clock() + (p.expiresIn - 60) * 1000L)
                                p.refreshToken?.let { secrets.setYouTubeRefresh(it) }
                                pending = null
                                runCatching { refreshSubscriptions(force = true) }
                            }
                        }
                    }
                    if (pending != null) { pending = null; error = "The code ran out of time. Press Connect for a new one." }
                }
                true
            }
        }
    }

    /** Signs the TV out of YouTube and deletes everything it kept. */
    suspend fun disconnect() {
        pollJob?.cancel(); pending = null
        secrets.current().ytRefreshToken?.let { t -> withContext(Dispatchers.IO) { api.revoke(t) } }
        secrets.setYouTubeRefresh(null)
        access = null
        store.replace(Cache())
        error = null
    }

    private suspend fun token(): String? {
        access?.let { (t, until) -> if (clock() < until) return t }
        val s = secrets.current()
        val rt = s.ytRefreshToken ?: return null
        return when (val r = withContext(Dispatchers.IO) { api.refresh(s.ytClientId.orEmpty(), s.ytClientSecret.orEmpty(), rt) }) {
            is Outcome.Ok -> { access = r.value.first to (clock() + (r.value.second - 60) * 1000L); r.value.first }
            is Outcome.Problem -> {
                error = r.message
                if (r.signedOut) { secrets.setYouTubeRefresh(null); store.replace(Cache()) }
                null
            }
        }
    }

    /** Subscriptions once a day (or now, after signing in). */
    suspend fun refreshSubscriptions(force: Boolean = false) = lock.withLock {
        val c = store.current()
        if (!force && clock() - c.fetchedAt < DAY) return@withLock
        val t = token() ?: return@withLock
        val name = (withContext(Dispatchers.IO) { api.accountName(t) } as? Outcome.Ok)?.value ?: c.accountName
        when (val r = withContext(Dispatchers.IO) { api.subscriptions(t) }) {
            is Outcome.Problem -> error = r.message
            is Outcome.Ok -> { store.replace(c.copy(accountName = name, subs = r.value, fetchedAt = clock())); error = null }
        }
    }

    /** Which subscriptions are live now: at most every 30 minutes, for the first [MAX_LIVE_CHECK] channels. */
    suspend fun checkLive(force: Boolean = false) = lock.withLock {
        val c = store.current()
        if (c.subs.isEmpty() || (!force && clock() - c.liveCheckedAt < 30 * MINUTE)) return@withLock
        val t = token() ?: return@withLock
        checking = true
        try {
            val ids = c.subs.take(MAX_LIVE_CHECK).map { it.id }
            var uploads = c.uploads
            if (clock() - c.uploadsAt > 7 * DAY || ids.any { it !in uploads }) {
                when (val r = withContext(Dispatchers.IO) { api.uploadsPlaylists(t, ids) }) {
                    is Outcome.Ok -> uploads = r.value
                    is Outcome.Problem -> { error = r.message; return@withLock }
                }
            }
            val videos = ArrayList<String>()
            for (id in ids) {
                val pl = uploads[id] ?: continue
                (withContext(Dispatchers.IO) { api.latestVideos(t, pl) } as? Outcome.Ok)?.value?.let(videos::addAll)
            }
            when (val r = withContext(Dispatchers.IO) { api.liveNow(t, videos) }) {
                is Outcome.Ok -> { store.replace(store.current().copy(uploads = uploads, uploadsAt = if (uploads === c.uploads) c.uploadsAt else clock(), live = r.value, liveCheckedAt = clock())); error = null }
                is Outcome.Problem -> error = r.message
            }
        } finally {
            checking = false
        }
    }

    /** Called when the page shows suggestions: answers from the cache, and freshens it in the background. */
    fun freshen() {
        if (!connected()) return
        scope.launch { runCatching { refreshSubscriptions(); checkLive() } }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
        const val MAX_AGE = 30 * DAY
        const val MAX_LIVE_CHECK = 100
    }
}
