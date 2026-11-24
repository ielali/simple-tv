package com.ielali.simpletv.data

import android.content.Context
import android.util.Log
import com.ielali.simpletv.youtube.YouTubeEmbed
import com.ielali.simpletv.youtube.YouTubeLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Finds logos for lineup channels the provider gave none (ADR-027). Live channels: the TV guide's
 * icon for the matched guide channel, else iptv-org's public channel database by guide id or name,
 * else Wikidata by name or brand. YouTube channels: the channel's picture, from the household's
 * subscriptions or the YouTube Data API. The caregiver can also choose one, or turn one off.
 * Found logos are kept apart from the channels, so saving the lineup never writes them in.
 */
class LogoService(
    context: Context,
    private val scope: CoroutineScope,
    private val channels: ChannelRepository,
    private val guide: GuideService,
    private val secrets: SecretsStore,
    private val youtube: YouTubeSuggestions,
    private val http: TextFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [source]: guide, iptv-org, wikidata, youtube or chosen (by the caregiver). */
    @Serializable
    data class Found(val url: String? = null, val source: String = "", val off: Boolean = false)

    /** An online look-up's answer (a logo or none) and when it was asked, so misses aren't asked daily. */
    @Serializable
    data class Tried(val url: String? = null, val at: Long = 0L)

    @Serializable
    data class Online(val wiki: Map<String, Tried> = emptyMap(), val yt: Map<String, Tried> = emptyMap())

    private val dir = File(context.filesDir, "logos").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val foundSer = MapSerializer(String.serializer(), Found.serializer())
    private val lock = Mutex()
    private val onlineLock = Mutex()
    @Volatile private var index: ChannelLogos.Index? = null
    @Volatile private var online: Online = load(File(dir, "online.json"), Online.serializer()) ?: Online()

    private val _found = MutableStateFlow(load(File(dir, "found.json"), foundSer).orEmpty())
    /** Channel id -> what was found or chosen (or that the caregiver turned it off). */
    val found: StateFlow<Map<String, Found>> = _found.asStateFlow()

    /** The logo screens show for a channel without its own: not when turned off. */
    fun logoFor(id: String): String? = _found.value[id]?.takeIf { !it.off }?.url

    private fun <T> load(f: File, ser: kotlinx.serialization.KSerializer<T>): T? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ser, f.readText()) }.getOrNull()

    private fun index(): ChannelLogos.Index? = index ?: load(File(dir, "index.json"), ChannelLogos.Index.serializer())?.also { index = it }

    private fun region() = Locale.getDefault().country

    private fun subPictures(): Map<String, String> = youtube.cache().subs.mapNotNull { s -> s.thumbnail?.let { s.id to it } }.toMap()

    /** Assigns logos from what the TV already has; never waits on the network. */
    suspend fun assignAll() = lock.withLock {
        val idx = index()
        val region = region()
        val subs = subPictures()
        val on = online
        val old = _found.value
        val next = HashMap<String, Found>()
        for (ch in channels.current().channels) {
            val was = old[ch.id]
            if (was?.off == true || was?.source == CHOSEN) { next[ch.id] = was; continue }
            if (!ch.logoUrl.isNullOrBlank()) continue
            val f = when (ch.type) {
                SourceType.STREAM -> guide.iconFor(ch)?.let { Found(it, "guide") }
                    ?: idx?.let { ChannelLogos.find(ch, it, region) }?.let { Found(it, "iptv-org") }
                    ?: on.wiki[nameKey(ch)]?.url?.let { Found(it, "wikidata") }
                SourceType.YOUTUBE -> ytChannelId(ch)?.let(subs::get)?.let { Found(it, "youtube") }
                    ?: on.yt[ch.url]?.url?.let { Found(it, "youtube") }
                else -> null
            } ?: was?.takeIf { it.url != null } // keep what was found while the sources are unavailable
            if (f != null) next[ch.id] = f
        }
        if (next != old) save(next)
    }

    private suspend fun save(next: Map<String, Found>) {
        _found.value = next
        withContext(Dispatchers.IO) { File(dir, "found.json").writeText(json.encodeToString(foundSer, next)) }
    }

    private fun nameKey(ch: Channel) = GuideMatch.normalise(ch.name)
    private fun ytChannelId(ch: Channel) = (YouTubeEmbed.parse(ch.url) as? YouTubeEmbed.Target.ChannelLive)?.channelId

    /** The caregiver turns a found logo off (or back on, which looks again). */
    suspend fun setOff(channelId: String, off: Boolean) {
        lock.withLock {
            val cur = _found.value[channelId] ?: Found()
            save(_found.value + (channelId to if (off) cur.copy(off = true) else Found()))
        }
        if (!off) assignAll()
    }

    /** The caregiver chooses a logo (from the picker or a picture address). */
    suspend fun choose(channelId: String, url: String) = lock.withLock {
        save(_found.value + (channelId to Found(url, CHOSEN)))
    }

    /** Asks Wikidata and YouTube about channels nothing else found a logo for. */
    suspend fun lookOnline() = onlineLock.withLock {
        val now = clock()
        val lineup = channels.current().channels
        val have = _found.value
        val missing = lineup.filter { it.logoUrl.isNullOrBlank() && have[it.id]?.let { f -> f.off || f.url != null } != true }
        var wiki = online.wiki
        var yt = online.yt
        fun due(t: Tried?) = t == null || now - t.at > (if (t.url == null) 7 * DAY else 30 * DAY)

        // YouTube: the channel's picture (subscriptions first, they cost nothing).
        val subs = subPictures()
        val ytWanted = lineup.filter { it.type == SourceType.YOUTUBE && it.logoUrl.isNullOrBlank() && (ytChannelId(it)?.let(subs::containsKey) != true) && due(yt[it.url]) }
        val auth = ytAuth()
        if (ytWanted.isNotEmpty() && auth != null) {
            val targets = ytWanted.mapNotNull { ch -> YouTubeEmbed.parse(ch.url)?.let { ch.id to it } }.toMap()
            val pics = withContext(Dispatchers.IO) { YouTubeLookup(http).pictures(targets, auth) }
            yt = yt + ytWanted.associate { it.url to Tried(pics[it.id], now) }
        }

        // Live channels: Wikidata by name, then by brand. A few per run, to be polite.
        val names = missing.filter { it.type == SourceType.STREAM }.map(::nameKey).filter { it.isNotEmpty() && due(wiki[it]) }.distinct().take(25)
        for (key in names) {
            val ch = missing.first { nameKey(it) == key }
            val url = runCatching { withContext(Dispatchers.IO) { wikiLogo(ch.name) } }.getOrElse { if (it is IOException) return@withLock else null }
            wiki = wiki + (key to Tried(url, now))
        }
        // YouTube data is kept at most 30 days (API policy); old entries are dropped.
        online = Online(wiki, yt.filterValues { now - it.at < 30 * DAY })
        withContext(Dispatchers.IO) { File(dir, "online.json").writeText(json.encodeToString(Online.serializer(), online)) }
    }

    private suspend fun ytAuth(): Map<String, String>? {
        secrets.current().youtubeApiKey?.takeIf { it.isNotBlank() }?.let { return mapOf("X-Goog-Api-Key" to it) }
        return youtube.bearer()?.let { mapOf("Authorization" to "Bearer $it", "Accept" to "application/json") }
    }

    private fun wikiGet(url: String): String? {
        val r = http.get(url, mapOf("User-Agent" to UA, "Api-User-Agent" to UA, "Accept" to "application/json"))
        return r.body.takeIf { r.ok }
    }

    private fun wikiLogo(name: String): String? {
        for (q in WikiLogos.queries(name)) {
            val body = wikiGet(WikiLogos.searchUrl(q)) ?: continue
            for (id in WikiLogos.pick(body, q)) {
                val file = wikiGet(WikiLogos.claimsUrl(id))?.let(WikiLogos::logoFile) ?: continue
                return WikiLogos.imageUrl(file)
            }
        }
        return null
    }

    /** Logos the caregiver can pick for a channel: iptv-org's and Wikidata's matches for [q], and its YouTube picture. */
    suspend fun choices(channelId: String, q: String): List<ChannelLogos.Choice> = withContext(Dispatchers.IO) {
        val out = ArrayList<ChannelLogos.Choice>()
        val ch = channels.current().channels.firstOrNull { it.id == channelId }
        if (ch?.type == SourceType.YOUTUBE) {
            (ytChannelId(ch)?.let { subPictures()[it] } ?: online.yt[ch.url]?.url)?.let { out += ChannelLogos.Choice(it, "Its YouTube picture", "youtube") }
        }
        index()?.let { out += ChannelLogos.search(q, it, region(), 9) }
        runCatching {
            val body = wikiGet(WikiLogos.searchUrl(q.trim()))
            if (body != null) for ((id, label) in WikiLogos.choices(body)) {
                val file = wikiGet(WikiLogos.claimsUrl(id))?.let(WikiLogos::logoFile) ?: continue
                out += ChannelLogos.Choice(WikiLogos.imageUrl(file), label, "wikidata")
            }
        }
        out.distinctBy { it.url }
    }

    /** Downloads iptv-org's logos and channels (two big JSON lists) and keeps a small index. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun refreshIndex() = withContext(Dispatchers.IO) {
        val logos = open(LOGOS_URL).use { s -> ChannelLogos.bestLogos(json.decodeToSequence(s, JsonObject.serializer())) }
        if (logos.isEmpty()) return@withContext
        val idx = open(CHANNELS_URL).use { s -> ChannelLogos.build(json.decodeToSequence(s, JsonObject.serializer()), logos, clock()) }
        index = idx
        File(dir, "index.json").writeText(json.encodeToString(ChannelLogos.Index.serializer(), idx))
    }

    private fun open(url: String): java.io.InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", UA)
        }
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        val raw = BufferedInputStream(conn.inputStream)
        raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    /** Weekly index, online look-ups every 6 hours, and a new look whenever the lineup or guide changes. */
    @OptIn(FlowPreview::class)
    fun start() = scope.launch {
        launch {
            merge(channels.channels.drop(1).map { }, guide.version.drop(1).map { })
                .debounce(5_000)
                .collect { runCatching { assignAll(); lookOnline(); assignAll() } }
        }
        delay(90_000)
        while (true) {
            val idx = index()
            if (idx == null || clock() - idx.builtAt > 7 * DAY) {
                runCatching { refreshIndex() }.onFailure { Log.w(TAG, "Logo index failed: ${it.javaClass.simpleName}") }
            }
            runCatching { assignAll() }
            runCatching { lookOnline() }.onFailure { Log.w(TAG, "Online logos failed: ${it.javaClass.simpleName}") }
            runCatching { assignAll() }
            delay(6 * 60 * 60_000L)
        }
    }

    private companion object {
        const val TAG = "LogoService"
        const val CHOSEN = "chosen"
        const val DAY = 24 * 60 * 60_000L
        const val UA = "Telly/1 (Android TV app)"
        const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"
        const val CHANNELS_URL = "https://iptv-org.github.io/api/channels.json"
    }
}
