package com.ielali.simpletv.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every subscription's catalog fresh: once a day at the caregiver's chosen time (catching up
 * after the TV was off) and on demand. A failed refresh keeps the last good copy. After a good one,
 * lineup channels get fresh addresses (new password, new User-Agent) and dropped channels are flagged.
 */
class CatalogService(
    private val scope: CoroutineScope,
    private val providers: ProviderRepository,
    private val channels: ChannelRepository,
    private val settings: SettingsRepository,
    private val repo: CatalogRepository,
    private val loader: ProviderLoader,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Channels that failed to play last time (memory only; cleared when they play). */
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failedChannels: StateFlow<Set<String>> = _failed.asStateFlow()

    fun isRunning(providerId: String) = providerId in running

    fun nextRefresh(): Long = RefreshSchedule.nextDue(clock(), settings.current().catalogRefreshTime, zone())

    /** Starts a refresh in the background; false when one is already running for this provider. */
    fun refreshAsync(providerId: String): Boolean {
        if (providerId in running) return false
        scope.launch { refresh(providerId) }
        return true
    }

    suspend fun refresh(providerId: String): CatalogStatus? {
        val provider = providers.find(providerId) ?: return null
        if (!running.add(providerId)) return repo.status(providerId)
        try {
            val old = repo.catalog(providerId)
            return when (val result = withContext(Dispatchers.IO) { loader.loadCatalog(provider, clock()) }) {
                is ProviderLoader.CatalogResult.Failed -> repo.updateStatus(providerId) {
                    it.record(RefreshRun(clock(), ok = false, message = result.message), null, null)
                }
                is ProviderLoader.CatalogResult.Loaded -> {
                    val diff = CatalogDiff.live(old, result.catalog)
                    repo.save(result.catalog)
                    val current = channels.current()
                    val repaired = LineupRepair.refresh(current.channels, provider, result.catalog)
                    if (repaired != current.channels) channels.replaceAll(current.copy(channels = repaired))
                    repo.updateStatus(providerId) {
                        it.record(RefreshRun(clock(), ok = true, added = diff.added, removed = diff.removed), result.catalog, result.account)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${FetchErrors.redact(e.toString())}")
            return repo.updateStatus(providerId) {
                it.record(RefreshRun(clock(), ok = false, message = "The channel list could not be updated."), null, null)
            }
        } finally {
            running.remove(providerId)
        }
    }

    suspend fun refreshAll() {
        for (p in providers.current().providers) refresh(p.id)
    }

    /** The cached catalog, downloading it first if this provider was never refreshed. */
    suspend fun ensureCatalog(providerId: String): Catalog? = repo.catalog(providerId) ?: run {
        refresh(providerId)
        repo.catalog(providerId)
    }

    suspend fun episodes(providerId: String, seriesId: String): List<Episode> {
        repo.episodes(providerId, seriesId)?.let { return it }
        val provider = providers.find(providerId) ?: return emptyList()
        val ua = repo.catalog(providerId)?.items?.firstOrNull { it.type == CatalogType.SERIES && it.id == seriesId }?.headers?.get("User-Agent")
        val list = withContext(Dispatchers.IO) { loader.loadEpisodes(provider, seriesId, ua) } ?: return emptyList()
        if (list.isNotEmpty()) repo.saveEpisodes(providerId, seriesId, list)
        return list
    }

    suspend fun flags(lineup: List<Channel>): Map<String, String> {
        val catalogs = lineup.mapNotNull { it.providerId }.distinct().mapNotNull { id -> repo.catalog(id)?.let { id to it } }.toMap()
        return LineupFlags.compute(lineup, catalogs, _failed.value)
    }

    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun reportPlayed(channelId: String) {
        failedAt.remove(channelId)
        if (channelId in _failed.value) _failed.value = _failed.value - channelId
    }
    fun reportFailed(channelId: String) {
        failedAt[channelId] = System.currentTimeMillis()
        _failed.value = _failed.value + channelId
    }

    /** Failed within the last 10 minutes: channel up/down steps over it; after that it gets another try. */
    fun recentlyFailed(channelId: String, now: Long = System.currentTimeMillis()): Boolean =
        failedAt[channelId]?.let { now - it < 10 * 60_000L } == true

    /** Checks every minute whether the daily refresh is due; the first check waits for the TV to settle. */
    fun startScheduler() = scope.launch {
        delay(90_000)
        while (true) {
            val now = clock()
            if (providers.current().providers.isNotEmpty() &&
                RefreshSchedule.isDue(now, repo.lastAutoRun(), settings.current().catalogRefreshTime, zone())
            ) {
                repo.setLastAutoRun(now)
                runCatching { refreshAll() }
            }
            delay(60_000)
        }
    }

    suspend fun forget(providerId: String) = repo.delete(providerId)

    private companion object { const val TAG = "CatalogService" }
}
