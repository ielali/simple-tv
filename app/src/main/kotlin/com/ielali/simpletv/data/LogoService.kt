package com.ielali.simpletv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Finds logos for lineup channels the provider gave none (ADR-027): the TV guide's icon for the
 * matched guide channel, else iptv-org's public channel database by guide id or name. Found logos are
 * kept apart from the channels (saving the lineup never writes them in), and the caregiver can turn
 * one off per channel. The iptv-org index is downloaded at most weekly and read as a stream.
 */
class LogoService(
    context: Context,
    private val scope: CoroutineScope,
    private val channels: ChannelRepository,
    private val guide: GuideService,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Found(val url: String? = null, val source: String = "", val off: Boolean = false)

    private val dir = File(context.filesDir, "logos").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val foundSer = MapSerializer(String.serializer(), Found.serializer())
    private val lock = Mutex()
    @Volatile private var index: ChannelLogos.Index? = null

    private val _found = MutableStateFlow(load(File(dir, "found.json"), foundSer).orEmpty())
    /** Channel id -> what was found (or that the caregiver turned it off). */
    val found: StateFlow<Map<String, Found>> = _found.asStateFlow()

    /** Channel id -> logo address, for screens: only channels without their own logo, and not turned off. */
    fun logoFor(id: String): String? = _found.value[id]?.takeIf { !it.off }?.url

    private fun <T> load(f: File, ser: kotlinx.serialization.KSerializer<T>): T? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ser, f.readText()) }.getOrNull()

    private fun index(): ChannelLogos.Index? = index ?: load(File(dir, "index.json"), ChannelLogos.Index.serializer())?.also { index = it }

    /** Looks for logos for every lineup channel that has none of its own. */
    suspend fun assignAll() = lock.withLock {
        val idx = index()
        val region = Locale.getDefault().country
        val lineup = channels.current().channels
        val old = _found.value
        val next = HashMap<String, Found>()
        for (ch in lineup) {
            val was = old[ch.id]
            if (was?.off == true) { next[ch.id] = was; continue }
            if (!ch.logoUrl.isNullOrBlank()) continue
            val fromGuide = guide.iconFor(ch)
            val f = when {
                fromGuide != null -> Found(fromGuide, "guide")
                idx != null && ch.type == SourceType.STREAM -> ChannelLogos.find(ch, idx, region)?.let { Found(it, "iptv-org") }
                else -> null
            } ?: was?.takeIf { it.url != null } // keep what was found while the sources are unavailable
            if (f != null) next[ch.id] = f
        }
        if (next != old) {
            _found.value = next
            withContext(Dispatchers.IO) { File(dir, "found.json").writeText(json.encodeToString(foundSer, next)) }
        }
    }

    /** The caregiver turns a found logo off (or back on) for one channel. */
    suspend fun setOff(channelId: String, off: Boolean) {
        lock.withLock {
            val cur = _found.value[channelId] ?: Found()
            val next = _found.value + (channelId to cur.copy(off = off))
            _found.value = next
            withContext(Dispatchers.IO) { File(dir, "found.json").writeText(json.encodeToString(foundSer, next)) }
        }
        if (!off) assignAll()
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
            setRequestProperty("User-Agent", "Telly/1 (Android TV)")
        }
        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
        val raw = BufferedInputStream(conn.inputStream)
        raw.mark(2); val b1 = raw.read(); val b2 = raw.read(); raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
    }

    /** Weekly index, and a new look whenever the lineup or the TV guide changes. */
    @OptIn(FlowPreview::class)
    fun start() = scope.launch {
        launch {
            merge(channels.channels.drop(1).map { }, guide.version.drop(1).map { })
                .debounce(5_000)
                .collect { runCatching { assignAll() } }
        }
        delay(90_000)
        while (true) {
            val idx = index()
            if (idx == null || clock() - idx.builtAt > 7 * DAY) {
                runCatching { refreshIndex() }.onFailure { Log.w(TAG, "Logo index failed: ${it.javaClass.simpleName}") }
            }
            runCatching { assignAll() }
            delay(6 * 60 * 60_000L)
        }
    }

    private companion object {
        const val TAG = "LogoService"
        const val DAY = 24 * 60 * 60_000L
        const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"
        const val CHANNELS_URL = "https://iptv-org.github.io/api/channels.json"
    }
}
