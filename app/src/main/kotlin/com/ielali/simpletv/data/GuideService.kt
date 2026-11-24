package com.ielali.simpletv.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * The TV guide (ADR-020). Live channels from an Xtream provider get now/next from the provider's
 * per-channel guide, fetched when tuned and swept every few minutes for the lineup. M3U playlists
 * that name an XMLTV guide, and the free guides the caregiver picked, are downloaded at most twice a
 * day, read as a stream (they can be huge) and trimmed to the lineup's channels for the next 36 hours.
 */
class GuideService(
    context: Context,
    private val scope: CoroutineScope,
    private val providers: ProviderRepository,
    private val channels: ChannelRepository,
    private val catalogs: CatalogRepository,
    private val settings: SettingsRepository,
    private val fetcher: TextFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class SourceData(
        val id: String,
        val label: String,
        val fetchedAt: Long = 0L,
        val ok: Boolean = true,
        val error: String? = null,
        val channels: List<GuideChannel> = emptyList(),
        /** Programmes by guide key, for the lineup's matched channels only. */
        val programmes: Map<String, List<Programme>> = emptyMap(),
    )

    /** One guide source as the caregiver sees it: a free guide, a guide link they added, or a playlist's own guide. */
    data class Source(val id: String, val label: String, val url: String, val headers: Map<String, String> = emptyMap(), val provider: Boolean = false)

    /** Where a channel's now/next comes from, for the page. */
    @Serializable
    data class Match(val key: String? = null, val name: String? = null, val source: String? = null, val how: String = "none")

    private val dir = File(context.filesDir, "guide").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val data = ConcurrentHashMap<String, SourceData>()
    private val short = ConcurrentHashMap<String, Pair<Long, List<Programme>>>()
    private val overrides = ConcurrentHashMap<String, String>()
    private val refreshLock = Mutex()
    @Volatile private var index: GuideMatch.Index? = null
    @Volatile var refreshing = false
        private set

    /** Bumped whenever guide data changes, so the TV screen redraws the banner. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        dir.listFiles { f -> f.name.startsWith("src_") && f.name.endsWith(".json") }?.forEach { f ->
            runCatching { json.decodeFromString(SourceData.serializer(), f.readText()) }.getOrNull()?.let { data[it.id] = it }
        }
        runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), File(dir, "overrides.json").readText()) }
            .getOrNull()?.let(overrides::putAll)
        rebuildIndex()
    }

    fun region(): String = Locale.getDefault().country.uppercase()

    /** The free guides chosen in TV settings (null = the default for the TV's region), then custom links. */
    fun chosenIds(): List<String> = settings.current().guideSources ?: GuidePresets.defaultFor(region())

    /** Every source in use: each M3U playlist's own guide, then the chosen free guides and links. */
    suspend fun sources(): List<Source> {
        val out = ArrayList<Source>()
        for (p in providers.current().providers) {
            val ua = "User-Agent" to ProviderLoader.agentsFor(p).first()
            // Xtream providers publish their whole guide; it names channels by the ids their streams carry.
            val user = p.username; val pass = p.password
            if (p.kind == ProviderKind.XTREAM && !user.isNullOrBlank() && !pass.isNullOrEmpty()) {
                out += Source("xt:${p.id}", "${p.name}'s guide", XtreamApi.xmltvUrl(p.url, user, pass), mapOf(ua), provider = true)
                continue
            }
            val url = catalogs.catalog(p.id)?.guideUrl ?: continue
            out += Source("m3u:${p.id}", "${p.name}'s guide", url, p.authHeaders() + ua, provider = true)
        }
        for (id in chosenIds()) {
            val preset = GuidePresets.find(id)
            out += when {
                preset != null -> Source(preset.id, preset.label, preset.url)
                id.startsWith("http://", true) || id.startsWith("https://", true) -> Source("url:" + id.hashCode().toUInt().toString(16), hostOf(id), id)
                else -> continue
            }
        }
        return out
    }

    private fun hostOf(url: String) = runCatching { URL(url).host }.getOrNull()?.removePrefix("www.") ?: "Guide link"

    fun status(id: String): SourceData? = data[id]

    /** Free guides and links: at most twice a day, or when asked. */
    suspend fun refresh(force: Boolean = false) = refreshLock.withLock {
        refreshing = true
        try {
            val srcs = sources()
            // Forget sources no longer chosen.
            data.keys.filter { k -> srcs.none { it.id == k } }.forEach { k -> data.remove(k); File(dir, "src_${safe(k)}.json").delete() }
            for (s in srcs) {
                val old = data[s.id]
                if (!force && old != null && old.ok && clock() - old.fetchedAt < 12 * HOUR) continue
                data[s.id] = withContext(Dispatchers.IO) { download(s, old) }
                persist(s.id)
            }
            rebuildIndex()
            _version.value++
        } finally {
            refreshing = false
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun persist(id: String) {
        val d = data[id] ?: return
        runCatching { File(dir, "src_${safe(id)}.json").writeText(json.encodeToString(SourceData.serializer(), d)) }
    }

    /** Lineup channels by tidied name and guide id, so only their programmes are kept. */
    private fun wanted(): Pair<Set<String>, Set<String>> {
        val live = channels.current().channels.filter { it.type == SourceType.STREAM }
        return live.mapNotNull { it.epgId?.lowercase() }.toSet() to live.map { GuideMatch.normalise(it.name) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun download(s: Source, old: SourceData?): SourceData {
        val (ids, names) = wanted()
        val pinned = overrides.values.filter { it.startsWith(s.id + "|") }.map { it.substringAfter('|') }.toSet()
        val now = clock()
        return try {
            val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000
                setRequestProperty("Accept-Encoding", "gzip")
                (mapOf("User-Agent" to "SimpleTV/0.1 (Android TV)") + s.headers).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code !in 200..299) return failed(s, old, FetchErrors.forHttp(code))
            val channelsSeen = ArrayList<GuideChannel>()
            val icons = HashMap<String, String>()
            val keepIds = HashSet<String>()
            val progs = HashMap<String, MutableList<Programme>>()
            openStream(conn).use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, null)
                XmltvReader.read(
                    parser, now - HOUR, now + 36 * HOUR,
                    keep = { id -> id in keepIds },
                    onChannel = { id, n ->
                        channelsSeen += GuideChannel(s.id + "|" + id, id, n, s.id)
                        if (id.lowercase() in ids || id in pinned || n.any { GuideMatch.normalise(it) in names }) keepIds += id
                    },
                    onProgramme = { id, p -> progs.getOrPut(s.id + "|" + id) { ArrayList() } += p },
                    onIcon = { id, url -> icons[id] = url },
                )
            }
            if (channelsSeen.isEmpty()) return failed(s, old, "That guide had no channels in it.")
            // Logos only for the channels the lineup uses, so the saved guide stays small.
            val withIcons = channelsSeen.map { c -> if (c.id in keepIds) c.copy(icon = icons[c.id]) else c }
            SourceData(s.id, s.label, now, true, null, withIcons, progs.mapValues { (_, v) -> v.sortedBy { it.start } })
        } catch (e: Exception) {
            Log.w(TAG, "Guide ${s.id} failed: ${FetchErrors.redact(e.toString())}")
            failed(s, old, if (e is java.io.IOException) FetchErrors.forNetwork(e) else "The guide could not be read.")
        }
    }

    private fun failed(s: Source, old: SourceData?, message: String) =
        (old ?: SourceData(s.id, s.label)).copy(label = s.label, ok = false, error = message.replace(Regex("""https?://\S+"""), "the guide"))

    private fun openStream(conn: HttpURLConnection): InputStream {
        val raw = BufferedInputStream(conn.inputStream)
        raw.mark(2)
        val b1 = raw.read(); val b2 = raw.read()
        raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    private fun rebuildIndex() {
        val all = data.values.sortedBy { if (it.id.startsWith("m3u:") || it.id.startsWith("xt:")) 0 else 1 }.flatMap { it.channels }
        index = GuideMatch.Index(all)
    }

    private fun channelNamed(key: String): GuideChannel? = data[key.substringBefore('|')]?.channels?.firstOrNull { it.key == key }

    /** Which guide channel feeds [ch], and how it was chosen. */
    fun match(ch: Channel): Match {
        overrides[ch.id]?.let { k ->
            if (k.isEmpty()) return Match(how = "off")
            val gc = channelNamed(k)
            return Match(k, gc?.names?.firstOrNull() ?: gc?.id, gc?.let { data[it.source]?.label }, "override")
        }
        val provider = ch.providerId?.let(providers::find)
        if (provider?.kind == ProviderKind.XTREAM && XtreamGuide.streamId(ch.sourceId, ch.url) != null && short[ch.id]?.second?.isNotEmpty() != false) {
            return Match(null, null, "${provider.name}'s guide", "provider")
        }
        val idx = index ?: return Match()
        val k = GuideMatch.match(ch, idx) ?: return Match()
        val gc = channelNamed(k)
        return Match(k, gc?.names?.firstOrNull() ?: gc?.id, gc?.let { data[it.source]?.label }, if (ch.epgId != null && idx.byId[ch.epgId.lowercase()] == k) "id" else "name")
    }

    /** The logo the TV guide gives the guide channel matched to [ch], if any (ADR-027). */
    fun iconFor(ch: Channel): String? {
        if (ch.type != SourceType.STREAM) return null
        val k = match(ch).key ?: return null
        return channelNamed(k)?.icon
    }

    /** Now and next for a lineup channel from what the TV already has (never waits on the network). */
    fun nowNext(ch: Channel): NowNext? {
        if (ch.type != SourceType.STREAM) return null
        val m = match(ch)
        val at = clock()
        if (m.how == "provider") short[ch.id]?.let { (_, list) -> if (list.isNotEmpty()) return NowNext.of(list, at, m.source) }
        val k = m.key ?: return null
        val list = data[k.substringBefore('|')]?.programmes?.get(k) ?: return NowNext(source = m.source)
        return NowNext.of(list, at, m.source)
    }

    /** Fetch the provider's now/next for [ch] when what the TV has is old or has run out. */
    suspend fun touch(ch: Channel) {
        if (ch.type != SourceType.STREAM || overrides[ch.id] != null) return
        val provider = ch.providerId?.let(providers::find) ?: return
        val streamId = XtreamGuide.streamId(ch.sourceId, ch.url) ?: return
        if (provider.kind != ProviderKind.XTREAM) return
        val cached = short[ch.id]
        val at = clock()
        val stillGood = cached != null && at - cached.first < 30 * MINUTE &&
            (cached.second.isEmpty() || cached.second.any { it.start <= at && at < it.stop })
        if (stillGood) return
        val list = withContext(Dispatchers.IO) {
            runCatching {
                fetcher.get(
                    XtreamGuide.url(provider.url, provider.username.orEmpty(), provider.password.orEmpty(), streamId),
                    mapOf("User-Agent" to (ch.headers["User-Agent"] ?: ProviderLoader.agentsFor(provider).first())),
                ).takeIf { it.ok }?.let { XtreamGuide.parse(it.body) }
            }.getOrNull()
        } ?: return
        short[ch.id] = at to list
        _version.value++
    }

    /** "Automatic" (null), "no guide" (""), or a guide channel key. */
    suspend fun setOverride(channelId: String, key: String?) {
        if (key == null) overrides.remove(channelId) else overrides[channelId] = key
        withContext(Dispatchers.IO) {
            File(dir, "overrides.json").writeText(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), HashMap(overrides)))
        }
        _version.value++
        // A guide channel picked by hand needs its programmes: read that guide again.
        if (!key.isNullOrEmpty() && data[key.substringBefore('|')]?.programmes?.containsKey(key) != true) scope.launch { refresh(force = true) }
    }

    /** Guide channels whose names contain every typed word, for the page's "pick the guide channel". */
    fun search(q: String, limit: Int = 30): List<GuideChannel> {
        val words = GuideMatch.normalise(q).split(' ').filter { it.isNotEmpty() }
        return data.values.flatMap { it.channels }.filter { c ->
            val n = c.names.joinToString(" ") { GuideMatch.normalise(it) } + " " + c.id.lowercase()
            words.all { n.contains(it) }
        }.take(limit)
    }

    fun label(sourceId: String): String? = data[sourceId]?.label

    /** Sweeps the lineup's provider guides every 10 minutes and the XMLTV guides twice a day. */
    fun start() = scope.launch {
        delay(45_000)
        var lastLineup = channels.current().channels.map { it.id }.toSet()
        var lastXml = 0L
        while (true) {
            val lineup = channels.current().channels
            val ids = lineup.map { it.id }.toSet()
            // New channels in the lineup may need programmes the trimmed guides left out.
            val lineupChanged = ids != lastLineup && clock() - lastXml > 30 * MINUTE
            if (lineupChanged || clock() - lastXml > 12 * HOUR) {
                runCatching { refresh(force = lineupChanged) }
                lastXml = clock(); lastLineup = ids
            }
            for (ch in lineup) { runCatching { touch(ch) }; delay(150) }
            delay(10 * MINUTE)
        }
    }

    private companion object {
        const val TAG = "GuideService"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
