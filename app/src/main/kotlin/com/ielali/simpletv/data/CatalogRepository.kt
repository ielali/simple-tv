package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Each subscription's catalog on the TV: one file per provider (it can hold 10,000+ live channels and
 * thousands of films), a small status file, and cached series episodes. Files are written whole via a
 * temporary file, so a crash mid-write keeps the last good copy.
 */
class CatalogRepository(context: Context) {

    @Serializable
    data class Meta(val statuses: Map<String, CatalogStatus> = emptyMap(), val lastAutoRun: Long = 0L)

    private val dir = File(context.filesDir, "catalog").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Catalog>()
    private val episodes = ConcurrentHashMap<String, List<Episode>>()
    private val metaLock = Mutex()
    private val meta = JsonStore(File(dir, "status.json"), Meta.serializer()) { Meta() }

    private fun catalogFile(providerId: String) = File(dir, "catalog_${safe(providerId)}.json")
    private fun episodesFile(providerId: String, seriesId: String) = File(dir, "episodes_${safe(providerId)}_${safe(seriesId)}.json")
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    suspend fun catalog(providerId: String): Catalog? = cache[providerId] ?: withContext(Dispatchers.IO) {
        val f = catalogFile(providerId)
        if (!f.exists()) null else runCatching { json.decodeFromString(Catalog.serializer(), f.readText()) }.getOrNull()
    }?.also { cache[providerId] = it }

    suspend fun save(catalog: Catalog) {
        withContext(Dispatchers.IO) { writeAtomically(catalogFile(catalog.providerId), json.encodeToString(Catalog.serializer(), catalog)) }
        cache[catalog.providerId] = catalog
    }

    fun status(providerId: String): CatalogStatus = meta.current().statuses[providerId] ?: CatalogStatus()

    suspend fun updateStatus(providerId: String, change: (CatalogStatus) -> CatalogStatus): CatalogStatus = metaLock.withLock {
        val m = meta.current()
        val updated = change(m.statuses[providerId] ?: CatalogStatus())
        meta.replace(m.copy(statuses = m.statuses + (providerId to updated)))
        updated
    }

    fun lastAutoRun(): Long = meta.current().lastAutoRun

    suspend fun setLastAutoRun(at: Long) = metaLock.withLock { meta.replace(meta.current().copy(lastAutoRun = at)) }

    suspend fun episodes(providerId: String, seriesId: String): List<Episode>? =
        episodes["$providerId/$seriesId"] ?: withContext(Dispatchers.IO) {
            val f = episodesFile(providerId, seriesId)
            if (!f.exists()) null else runCatching { json.decodeFromString(ListSerializer(Episode.serializer()), f.readText()) }.getOrNull()
        }?.also { episodes["$providerId/$seriesId"] = it }

    suspend fun saveEpisodes(providerId: String, seriesId: String, list: List<Episode>) {
        withContext(Dispatchers.IO) { writeAtomically(episodesFile(providerId, seriesId), json.encodeToString(ListSerializer(Episode.serializer()), list)) }
        episodes["$providerId/$seriesId"] = list
    }

    suspend fun delete(providerId: String) {
        cache.remove(providerId)
        episodes.keys.removeIf { it.startsWith("$providerId/") }
        withContext(Dispatchers.IO) {
            catalogFile(providerId).delete()
            dir.listFiles { f -> f.name.startsWith("episodes_${safe(providerId)}_") }?.forEach { it.delete() }
        }
        metaLock.withLock { meta.replace(meta.current().let { it.copy(statuses = it.statuses - providerId) }) }
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
