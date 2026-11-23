package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Film and series details (posters, plot, trailer, official ratings) for the config page and for
 * film channels' rating rules (ADR-018). Fetched once per title from the provider, then TMDB when
 * the household has a key, and cached on the TV per subscription. Film channels with rating rules
 * get all their films checked in the background, gently, so the provider is never flooded.
 */
class MetadataService(
    context: Context,
    private val scope: CoroutineScope,
    private val providers: ProviderRepository,
    private val catalogs: CatalogRepository,
    private val channels: ChannelRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretsStore,
    private val loader: ProviderLoader,
    private val tmdb: TmdbClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dir = File(context.filesDir, "meta").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), FilmMeta.serializer())
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, FilmMeta>>()
    private val writeLock = Mutex()
    private val fetchLock = Mutex()
    private val work = Queue<Pair<String, CatalogItem>>(Queue.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var certLists: CertLists? = null

    /** The household's rating country: chosen on the page, else the TV's own region. */
    fun country(): String = RatingRules.country(settings.current().ratingCountry, Locale.getDefault().country)
    fun tvRegion(): String = Locale.getDefault().country.uppercase()
    fun hasKey(): Boolean = !secrets.current().tmdbKey.isNullOrBlank()
    fun lists(): CertLists? = certLists ?: load(File(dir, "certs.json"), CertLists.serializer())?.also { certLists = it }

    private fun file(pid: String) = File(dir, "meta_${pid.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json")

    private fun all(pid: String): ConcurrentHashMap<String, FilmMeta> = cache.getOrPut(pid) {
        ConcurrentHashMap(load(file(pid), mapSer).orEmpty())
    }

    fun cached(pid: String, type: CatalogType, itemId: String): FilmMeta? = all(pid)[FilmMeta.key(type, itemId)]

    private fun <T> load(f: File, ser: kotlinx.serialization.KSerializer<T>): T? =
        if (!f.exists()) null else runCatching { json.decodeFromString(ser, f.readText()) }.getOrNull()

    private suspend fun persist(pid: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val f = file(pid); val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(mapSer, HashMap(all(pid))))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Details for one title; downloads them when missing, or when TMDB can now add to them. */
    suspend fun details(pid: String, item: CatalogItem, save: Boolean = true): FilmMeta? = fetchLock.withLock {
        val key = secrets.current().tmdbKey
        val have = cached(pid, item.type, item.id)
        val fresh = have != null && clock() - have.fetchedAt < MAX_AGE_MS && (key.isNullOrBlank() || have.tmdbAt > 0)
        if (fresh) return have
        val provider = providers.find(pid) ?: return have
        val base = have?.takeIf { clock() - it.fetchedAt < MAX_AGE_MS }
            ?: withContext(Dispatchers.IO) { loader.loadInfo(provider, item.type, item.id, item.headers["User-Agent"], clock()) }
            ?: FilmMeta(item.id, item.type, poster = item.logo, year = item.year, fetchedAt = clock())
        val withYear = if (base.year == null) base.copy(year = item.year) else base
        val result = if (key.isNullOrBlank()) withYear else withContext(Dispatchers.IO) { tmdb.enrich(withYear, item.name, key, clock()) }
        all(pid)[result.key] = result
        if (save) persist(pid)
        result
    }

    suspend fun details(pid: String, type: CatalogType, itemId: String): FilmMeta? {
        val item = catalogs.catalog(pid)?.items?.firstOrNull { it.type == type && it.id == itemId } ?: return cached(pid, type, itemId)
        return details(pid, item)
    }

    /** May this film play on a channel with [spec]'s rules? */
    fun allowed(spec: PlaylistSpec, item: CatalogItem): Boolean =
        RatingRules.allowed(cached(spec.providerId, item.type, item.id), spec, country(), lists(), settings.current().hideAdult)

    /** How many of a film channel's films have been checked (for "Checking ratings: 340 of 1,172"). */
    suspend fun progress(spec: PlaylistSpec): Pair<Int, Int> {
        val items = PlaylistQueue.candidates(catalogs.catalog(spec.providerId), spec)
        val map = all(spec.providerId)
        return items.count { map.containsKey(FilmMeta.key(it.type, it.id)) && (!hasKey() || map[FilmMeta.key(it.type, it.id)]!!.tmdbAt > 0) } to items.size
    }

    /** Queues every film of the rule-based film channels that still needs checking. */
    suspend fun checkChannels() {
        val key = hasKey()
        for (ch in channels.current().channels) {
            val spec = ch.playlist ?: continue
            if (spec.mode != PlaylistMode.FILM || !(RatingRules.hasRules(spec) || settings.current().hideAdult && key)) continue
            checkSpec(spec)
        }
    }

    suspend fun checkSpec(spec: PlaylistSpec) {
        val map = all(spec.providerId); val key = hasKey()
        for (item in PlaylistQueue.candidates(catalogs.catalog(spec.providerId), spec)) {
            val m = map[FilmMeta.key(item.type, item.id)]
            if (m != null && (!key || m.tmdbAt > 0) && clock() - m.fetchedAt < MAX_AGE_MS) continue
            if (queued.add(spec.providerId + "|" + item.id)) work.trySend(spec.providerId to item)
        }
    }

    /** New or changed key: check it, fetch the rating lists, then fill in details the TV already has. */
    suspend fun setKey(key: String?): TmdbClient.Check {
        val k = key?.trim().orEmpty()
        if (k.isEmpty()) {
            secrets.setTmdbKey(null)
            return TmdbClient.Check.Ok
        }
        val r = withContext(Dispatchers.IO) { tmdb.check(k) }
        if (r is TmdbClient.Check.Ok) {
            secrets.setTmdbKey(k)
            refreshLists()
            checkChannels()
        }
        return r
    }

    suspend fun refreshLists() {
        val key = secrets.current().tmdbKey ?: return
        val l = withContext(Dispatchers.IO) { tmdb.certLists(key, clock()) } ?: return
        certLists = l
        withContext(Dispatchers.IO) { File(dir, "certs.json").writeText(json.encodeToString(CertLists.serializer(), l)) }
    }

    suspend fun forget(pid: String) {
        cache.remove(pid)
        withContext(Dispatchers.IO) { file(pid).delete() }
    }

    /** One title every ~400 ms, saving every 25; checks channels again daily after the refresh. */
    fun start() = scope.launch {
        launch {
            delay(60_000)
            while (true) {
                if (hasKey() && (lists() == null || clock() - (lists()?.fetchedAt ?: 0) > 30 * DAY_MS)) runCatching { refreshLists() }
                runCatching { checkChannels() }
                delay(6 * 60 * 60_000L)
            }
        }
        val unsaved = HashSet<String>()
        var done = 0
        while (true) {
            val (pid, item) = work.receive()
            runCatching { details(pid, item, save = false) }
            queued.remove(pid + "|" + item.id)
            unsaved += pid
            if (queued.isEmpty() || ++done % 25 == 0) {
                unsaved.forEach { runCatching { persist(it) } }
                unsaved.clear()
            }
            delay(400)
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val MAX_AGE_MS = 30 * DAY_MS
    }
}
