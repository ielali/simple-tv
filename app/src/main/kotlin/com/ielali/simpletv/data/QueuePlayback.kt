package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Film and series channels: turns a channel's [PlaylistSpec] into the film or episode to play now,
 * moves on when one ends, and remembers where it stopped (so a series resumes at the right episode).
 */
class QueuePlayback(
    context: Context,
    private val catalog: CatalogService,
    private val catalogRepo: CatalogRepository,
    private val providers: ProviderRepository,
) {
    @Serializable
    data class Progress(val itemId: String? = null, val positionMs: Long = 0L, val updatedAt: Long = 0L)

    @Serializable
    data class ProgressFile(val byChannel: Map<String, Progress> = emptyMap())

    data class Playable(val itemId: String, val title: String, val url: String, val headers: Map<String, String>, val startPositionMs: Long)

    private val store = JsonStore(File(context.filesDir, "queue_progress.json"), ProgressFile.serializer()) { ProgressFile() }

    /** Title of the film or episode now playing, for "On the TV now". */
    private val _nowTitle = MutableStateFlow<Pair<String, String>?>(null)
    val nowTitle: StateFlow<Pair<String, String>?> = _nowTitle.asStateFlow()

    suspend fun queue(spec: PlaylistSpec): List<QueueEntry> = when (spec.mode) {
        PlaylistMode.FILM -> PlaylistQueue.films(catalogRepo.catalog(spec.providerId) ?: catalog.ensureCatalog(spec.providerId), spec)
        PlaylistMode.SERIES -> {
            val id = spec.seriesId
            if (id == null) emptyList() else {
                val ua = catalogRepo.catalog(spec.providerId)?.items?.firstOrNull { it.type == CatalogType.SERIES && it.id == id }?.headers.orEmpty()
                PlaylistQueue.episodes(catalog.episodes(spec.providerId, id), spec, ua)
            }
        }
    }

    /** The queue from what is already on the TV, never downloading (for listing the lineup). */
    suspend fun queueCached(spec: PlaylistSpec): List<QueueEntry> = when (spec.mode) {
        PlaylistMode.FILM -> PlaylistQueue.films(catalogRepo.catalog(spec.providerId), spec)
        PlaylistMode.SERIES -> spec.seriesId?.let { catalogRepo.episodes(spec.providerId, it) }
            ?.let { PlaylistQueue.episodes(it, spec) }.orEmpty()
    }

    fun progress(channelId: String): Progress = store.current().byChannel[channelId] ?: Progress()

    suspend fun current(channel: Channel): Playable? {
        val spec = channel.playlist ?: return null
        val q = queue(spec).ifEmpty { return null }
        val p = progress(channel.id)
        val i = PlaylistQueue.resumeIndex(q, p.itemId)
        val start = if (q[i].id == p.itemId) p.positionMs else 0L
        return playable(channel, spec, q[i], start)
    }

    suspend fun advance(channel: Channel): Playable? {
        val spec = channel.playlist ?: return null
        val q = queue(spec).ifEmpty { return null }
        val next = PlaylistQueue.nextIndex(q, PlaylistQueue.resumeIndex(q, progress(channel.id).itemId))
        save(channel.id, q[next].id, 0L)
        return playable(channel, spec, q[next], 0L)
    }

    suspend fun save(channelId: String, itemId: String, positionMs: Long) {
        val f = store.current()
        store.replace(f.copy(byChannel = f.byChannel + (channelId to Progress(itemId, positionMs, System.currentTimeMillis()))))
    }

    suspend fun restart(channelId: String) {
        val f = store.current()
        store.replace(f.copy(byChannel = f.byChannel - channelId))
    }

    /** "Stopped at season 1, episode 4" / the film that plays next, for the editor. */
    suspend fun resumeLabel(channel: Channel): String? {
        val spec = channel.playlist ?: return null
        val q = queue(spec).ifEmpty { return null }
        val p = progress(channel.id)
        if (p.itemId == null) return null
        return q.getOrNull(PlaylistQueue.resumeIndex(q, p.itemId))?.title
    }

    private fun playable(channel: Channel, spec: PlaylistSpec, e: QueueEntry, start: Long): Playable? {
        val provider = providers.find(spec.providerId) ?: return null
        val user = provider.username.orEmpty()
        val pass = provider.password.orEmpty()
        val url = when (spec.mode) {
            PlaylistMode.FILM -> XtreamApi.movieUrl(provider.url, user, pass, e.id, e.ext.orEmpty())
            PlaylistMode.SERIES -> XtreamApi.episodeUrl(provider.url, user, pass, e.id, e.ext.orEmpty())
        }
        _nowTitle.value = channel.id to e.title
        return Playable(e.id, e.title, url, provider.authHeaders() + e.headers, start)
    }
}
