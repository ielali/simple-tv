package com.ielali.simpletv.config

import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.data.CatalogQuery
import com.ielali.simpletv.data.CatalogType
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.LineupEdit
import com.ielali.simpletv.data.PlaylistMode
import com.ielali.simpletv.data.PlaylistQueue
import com.ielali.simpletv.data.PlaylistSpec
import com.ielali.simpletv.data.Provider
import com.ielali.simpletv.data.ProviderImport
import com.ielali.simpletv.data.ProviderList
import com.ielali.simpletv.data.ProviderLoader
import com.ielali.simpletv.data.QueueEntry
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.HttpFetcher
import com.ielali.simpletv.youtube.YouTubeEmbed
import com.ielali.simpletv.youtube.YouTubeLookup
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

/**
 * Lineup, subscriptions, catalog, film and series channels, YouTube previews and "Play on TV now".
 * Every response is built from [ApiViews] or [CatalogQuery], neither of which carries an address.
 */
class ContentRoutes(private val app: SimpleTvApp) {

    private val youtube = YouTubeLookup(HttpFetcher(readTimeoutMs = 15_000))

    @Serializable data class LineupSave(val rows: List<LineupEdit.Row>)
    @Serializable data class YouTubeAdd(val source: String, val name: String, val number: Int? = null, val logoUrl: String? = null)
    @Serializable data class LinkAdd(val url: String, val name: String, val number: Int? = null, val logoUrl: String? = null)
    @Serializable data class CatalogAdd(val providerId: String, val itemIds: List<String> = emptyList(), val categories: List<String> = emptyList())
    @Serializable data class AddResult(val added: Int, val skipped: Int, val numbers: List<Int>, val lineup: ApiViews.Lineup)
    @Serializable data class Added(val channel: ApiViews.ChannelView, val lineup: ApiViews.Lineup)

    @Serializable data class ProvidersResponse(val providers: List<ApiViews.ProviderView>, val refreshTime: String, val nextRefresh: Long)
    @Serializable data class ProviderSaved(val provider: ApiViews.ProviderView, val providers: ProvidersResponse)
    @Serializable data class TestResult(val ok: Boolean, val message: String, val expiresAt: Long? = null, val source: String? = null)

    @Serializable data class CategoriesResponse(val loaded: Boolean, val refreshing: Boolean, val categories: List<CatalogQuery.Category>, val status: ApiViews.StatusView)
    @Serializable data class ItemsResponse(val loaded: Boolean, val refreshing: Boolean, val page: CatalogQuery.Page)
    @Serializable data class EntryView(val id: String, val title: String, val detail: String? = null)
    @Serializable data class SeriesInfo(val seasons: Int, val episodes: Int, val entries: List<EntryView>)
    @Serializable data class QueueView(val total: Int, val entries: List<EntryView>, val resume: String? = null, val resumeItemId: String? = null)
    @Serializable data class QueuePreview(val playlist: PlaylistSpec)

    @Serializable data class YouTubeInput(val input: String = "")
    @Serializable data class YouTubeKey(val key: String = "")
    @Serializable data class KeyState(val hasYoutubeKey: Boolean)

    @Serializable
    data class PlayRequest(
        val channelId: String? = null,
        /** A YouTube link or channel id not in the lineup yet. */
        val youtube: String? = null,
        /** A catalog item not in the lineup yet. */
        val providerId: String? = null,
        val itemId: String? = null,
        /** A single stream address from the "From a link" tab. */
        val url: String? = null,
        /** An unsaved film or series channel ("Play first on TV"). */
        val playlist: PlaylistSpec? = null,
        val name: String? = null,
    )

    @Serializable
    data class NowPlaying(
        val channel: ApiViews.ChannelView? = null,
        /** Film or episode playing on a film/series channel. */
        val title: String? = null,
        val since: Long = 0L,
        val startedFromApp: Boolean = false,
        /** Played from the page without being in the lineup. */
        val preview: Boolean = false,
        val now: Long,
    )

    private val channels get() = app.channels
    private val providers get() = app.providers

    private suspend fun lineup(): ApiViews.Lineup {
        val list = channels.current().channels
        val base = ApiViews.lineup(list, app.catalog.flags(list))
        // Film and series rows say how big the queue is and where it stopped (from the cached catalog).
        return base.copy(channels = base.channels.map { v ->
            val spec = v.playlist ?: return@map v
            val q = runCatching { app.queue.queueCached(spec) }.getOrDefault(emptyList())
            val at = app.queue.progress(v.id).itemId?.let { id -> q.firstOrNull { it.id == id } }
            v.copy(queueSize = q.size, resume = at?.title)
        })
    }

    private fun providersResponse(): ProvidersResponse {
        val lineup = channels.current().channels
        return ProvidersResponse(
            providers = providers.current().providers.map {
                ApiViews.provider(it, lineup, app.catalogs.status(it.id), app.catalog.isRunning(it.id))
            },
            refreshTime = app.settings.current().catalogRefreshTime,
            nextRefresh = app.catalog.nextRefresh(),
        )
    }

    private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String, code: String? = null) =
        respond(status, ConfigServer.ErrorBody(message, code))

    /** Adds one channel at [requested] (or the next free number). Null when that number is taken. */
    private suspend fun ApplicationCall.addChannel(channel: Channel, requested: Int?): Channel? {
        val current = channels.current()
        val number = requested ?: ApiViews.nextNumber(current.channels)
        if (number < 1 || number > 9999) {
            fail(HttpStatusCode.BadRequest, "Pick a number from 1 to 9999.")
            return null
        }
        current.channels.firstOrNull { it.number == number }?.let {
            fail(HttpStatusCode.Conflict, "Number $number is already ${it.name}. Pick another number.", "number_taken")
            return null
        }
        val added = channel.copy(number = number)
        channels.replaceAll(current.copy(channels = current.channels + added))
        return added
    }

    private suspend fun onTv(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun parseType(raw: String?): CatalogType = when (raw?.lowercase()) {
        "film", "films", "movie", "movies" -> CatalogType.FILM
        "series" -> CatalogType.SERIES
        else -> CatalogType.LIVE
    }

    private fun entryView(e: QueueEntry) = EntryView(e.id, e.title, e.detail)

    private suspend fun queueView(spec: PlaylistSpec, channelId: String?): QueueView {
        val q = app.queue.queue(spec)
        val progress = channelId?.let { app.queue.progress(it) }
        val resume = progress?.itemId?.let { id -> q.firstOrNull { it.id == id } }
        return QueueView(q.size, q.take(QUEUE_PAGE).map(::entryView), resume?.title, resume?.id)
    }

    private fun withSeed(spec: PlaylistSpec) =
        if (spec.shuffle && spec.seed == 0L) spec.copy(seed = Random.nextLong(1, Long.MAX_VALUE)) else spec

    fun Route.install() {
        lineupRoutes()
        providerRoutes()
        catalogRoutes()
        filmRoutes()
        youtubeRoutes()
        playRoutes()
    }

    private fun Route.lineupRoutes() {
        get("/api/channels") { call.respond(lineup()) }
        put("/api/channels") {
            val rows = call.receive<LineupSave>().rows
            val dup = rows.groupBy { it.number }.entries.firstOrNull { it.value.size > 1 }
            if (dup != null) return@put call.fail(HttpStatusCode.BadRequest, "Two channels have number ${dup.key}. Give each its own number.")
            if (rows.any { it.number < 1 || it.number > 9999 }) return@put call.fail(HttpStatusCode.BadRequest, "Numbers go from 1 to 9999.")
            val current = channels.current()
            channels.replaceAll(current.copy(channels = LineupEdit.apply(current.channels, rows)))
            call.respond(lineup())
        }
        post("/api/channels/youtube") {
            val req = call.receive<YouTubeAdd>()
            val source = req.source.trim()
            if (YouTubeEmbed.parse(source) == null) return@post call.fail(HttpStatusCode.BadRequest, "That doesn't look like a YouTube link or channel ID.")
            val name = req.name.trim().ifEmpty { return@post call.fail(HttpStatusCode.BadRequest, "Give the channel a name.") }
            val ch = Channel(
                id = UUID.randomUUID().toString(), number = 0, name = name, type = SourceType.YOUTUBE, url = source,
                logoUrl = req.logoUrl?.trim()?.takeIf { it.isNotEmpty() },
            )
            val added = call.addChannel(ch, req.number) ?: return@post
            call.respond(Added(ApiViews.channel(added), lineup()))
        }
        post("/api/channels/link") {
            val req = call.receive<LinkAdd>()
            val url = req.url.trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return@post call.fail(HttpStatusCode.BadRequest, "The address should start with http:// or https://.")
            }
            val name = req.name.trim().ifEmpty { return@post call.fail(HttpStatusCode.BadRequest, "Give the channel a name.") }
            val ch = Channel(
                id = UUID.randomUUID().toString(), number = 0, name = name, type = SourceType.STREAM, url = url,
                logoUrl = req.logoUrl?.trim()?.takeIf { it.isNotEmpty() },
            )
            val added = call.addChannel(ch, req.number) ?: return@post
            call.respond(Added(ApiViews.channel(added), lineup()))
        }
        post("/api/channels/catalog") {
            val req = call.receive<CatalogAdd>()
            val provider = providers.find(req.providerId) ?: return@post call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val catalog = app.catalogs.catalog(provider.id)
                ?: return@post call.fail(HttpStatusCode.Conflict, "The channel list is still downloading. Try again in a minute.", "loading")
            val ids = req.itemIds.toHashSet()
            val groups = req.categories.toHashSet()
            val items = catalog.of(CatalogType.LIVE).filter { it.id in ids || it.group.orEmpty() in groups }
            if (items.isEmpty()) return@post call.fail(HttpStatusCode.BadRequest, "Choose at least one channel.")
            val current = channels.current()
            val merged = ProviderImport.merge(current.channels, ProviderImport.fromCatalog(items), provider)
            channels.replaceAll(current.copy(channels = merged.channels))
            call.respond(AddResult(merged.added, merged.skipped, merged.numbers, lineup()))
        }
    }

    private fun Route.providerRoutes() {
        get("/api/providers") { call.respond(providersResponse()) }
        post("/api/providers") {
            val input = call.receive<ApiViews.ProviderInput>()
            val existing = input.id?.let(providers::find)
            val provider = when (val r = ApiViews.upsert(existing, input) { UUID.randomUUID().toString() }) {
                is ApiViews.Upsert.Invalid -> return@post call.fail(HttpStatusCode.BadRequest, r.message)
                is ApiViews.Upsert.Ok -> r.provider
            }
            val list = providers.current().providers
            providers.replaceAll(
                ProviderList(providers = if (existing == null) list + provider else list.map { if (it.id == provider.id) provider else it })
            )
            // New login details or a new User-Agent: fetch the catalog again (this also repairs lineup addresses).
            if (existing == null || existing.url != provider.url || existing.username != provider.username ||
                existing.password != provider.password || existing.userAgent != provider.userAgent || existing.kind != provider.kind
            ) app.catalog.refreshAsync(provider.id)
            val response = providersResponse()
            call.respond(ProviderSaved(response.providers.first { it.id == provider.id }, response))
        }
        post("/api/providers/test") {
            val input = call.receive<ApiViews.ProviderInput>()
            val existing = input.id?.let(providers::find)
            val provider = when (val r = ApiViews.upsert(existing, input.copy(name = input.name.ifBlank { "Test" })) { "test" }) {
                is ApiViews.Upsert.Invalid -> return@post call.respond(TestResult(false, r.message))
                is ApiViews.Upsert.Ok -> r.provider
            }
            val result = withContext(Dispatchers.IO) { app.loader.test(provider) }
            call.respond(
                when (result) {
                    is ProviderLoader.Result.Failed -> TestResult(false, result.message)
                    is ProviderLoader.Result.Loaded -> TestResult(true, "It works.", result.account?.expiresAt, result.source)
                }
            )
        }
        post("/api/providers/refresh-all") {
            providers.current().providers.forEach { app.catalog.refreshAsync(it.id) }
            call.respond(HttpStatusCode.Accepted, providersResponse())
        }
        post("/api/providers/{id}/refresh") {
            val id = call.parameters["id"].orEmpty()
            if (providers.find(id) == null) return@post call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            app.catalog.refreshAsync(id)
            call.respond(HttpStatusCode.Accepted, providersResponse())
        }
        delete("/api/providers/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (providers.find(id) == null) return@delete call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val current = channels.current()
            channels.replaceAll(current.copy(channels = current.channels.filterNot { it.providerId == id || it.playlist?.providerId == id }))
            providers.replaceAll(ProviderList(providers = providers.current().providers.filterNot { it.id == id }))
            app.catalog.forget(id)
            call.respond(providersResponse())
        }
    }

    private fun Route.catalogRoutes() {
        get("/api/catalog/{pid}/categories") {
            val pid = call.parameters["pid"].orEmpty()
            if (providers.find(pid) == null) return@get call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val catalog = app.catalogs.catalog(pid)
            if (catalog == null && !app.catalog.isRunning(pid)) app.catalog.refreshAsync(pid)
            call.respond(
                CategoriesResponse(
                    loaded = catalog != null,
                    refreshing = app.catalog.isRunning(pid),
                    categories = CatalogQuery.categories(catalog, parseType(call.request.queryParameters["type"])),
                    status = ApiViews.status(app.catalogs.status(pid)),
                )
            )
        }
        get("/api/catalog/{pid}/items") {
            val pid = call.parameters["pid"].orEmpty()
            if (providers.find(pid) == null) return@get call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val qp = call.request.queryParameters
            val catalog = app.catalogs.catalog(pid)
            val page = CatalogQuery.items(
                catalog, parseType(qp["type"]), qp["category"], qp["q"],
                qp["offset"]?.toIntOrNull() ?: 0, qp["limit"]?.toIntOrNull() ?: 50,
                channels.current().channels, pid,
            )
            call.respond(ItemsResponse(catalog != null, app.catalog.isRunning(pid), page))
        }
        get("/api/catalog/{pid}/series/{sid}") {
            val pid = call.parameters["pid"].orEmpty()
            val sid = call.parameters["sid"].orEmpty()
            if (providers.find(pid) == null) return@get call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val episodes = app.catalog.episodes(pid, sid)
            val entries = PlaylistQueue.episodes(episodes, PlaylistSpec(PlaylistMode.SERIES, pid, seriesId = sid))
            call.respond(SeriesInfo(episodes.map { it.season }.distinct().size, episodes.size, entries.take(QUEUE_PAGE).map(::entryView)))
        }
    }

    private fun Route.filmRoutes() {
        post("/api/channels/film") {
            val req = call.receive<ApiViews.FilmChannelInput>()
            val spec = withSeed(req.playlist)
            ApiViews.checkPlaylist(spec, providers.find(spec.providerId) != null)?.let { return@post call.fail(HttpStatusCode.BadRequest, it) }
            val name = req.name.trim().ifEmpty { return@post call.fail(HttpStatusCode.BadRequest, "Give the channel a name.") }
            val existing = req.id?.let { id -> channels.current().channels.firstOrNull { it.id == id && it.playlist != null } }
            if (existing != null) {
                val current = channels.current()
                val number = req.number ?: existing.number
                current.channels.firstOrNull { it.number == number && it.id != existing.id }?.let {
                    return@post call.fail(HttpStatusCode.Conflict, "Number $number is already ${it.name}. Pick another number.", "number_taken")
                }
                val updated = existing.copy(name = name, number = number, playlist = spec, type = ApiViews.filmChannelType(spec))
                channels.replaceAll(current.copy(channels = current.channels.map { if (it.id == existing.id) updated else it }))
                // A different series or film list starts from the top.
                if (existing.playlist?.seriesId != spec.seriesId || existing.playlist?.mode != spec.mode) app.queue.restart(existing.id)
                return@post call.respond(Added(ApiViews.channel(updated), lineup()))
            }
            val ch = Channel(
                id = UUID.randomUUID().toString(), number = 0, name = name, type = ApiViews.filmChannelType(spec),
                url = "", providerId = null, playlist = spec,
            )
            val added = call.addChannel(ch, req.number) ?: return@post
            call.respond(Added(ApiViews.channel(added), lineup()))
        }
        get("/api/channels/{id}/queue") {
            val id = call.parameters["id"].orEmpty()
            val ch = channels.current().channels.firstOrNull { it.id == id && it.playlist != null }
                ?: return@get call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            call.respond(queueView(ch.playlist!!, ch.id))
        }
        post("/api/films/queue-preview") {
            val spec = call.receive<QueuePreview>().playlist
            if (providers.find(spec.providerId) == null) return@post call.fail(HttpStatusCode.NotFound, "Choose a subscription first.")
            call.respond(queueView(spec, null))
        }
        post("/api/channels/{id}/restart") {
            val id = call.parameters["id"].orEmpty()
            val ch = channels.current().channels.firstOrNull { it.id == id && it.playlist != null }
                ?: return@post call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            app.queue.restart(ch.id)
            call.respond(queueView(ch.playlist!!, ch.id))
        }
    }

    private fun Route.youtubeRoutes() {
        post("/api/youtube/preview") {
            val input = call.receive<YouTubeInput>().input
            when (val r = withContext(Dispatchers.IO) { youtube.preview(input, app.secrets.current().youtubeApiKey) }) {
                is YouTubeLookup.Result.Found -> call.respond(r.preview)
                is YouTubeLookup.Result.Problem -> call.fail(HttpStatusCode.UnprocessableEntity, r.message, r.code)
            }
        }
        put("/api/youtube/key") {
            app.secrets.setYoutubeKey(call.receive<YouTubeKey>().key)
            call.respond(KeyState(!app.secrets.current().youtubeApiKey.isNullOrEmpty()))
        }
    }

    private fun Route.playRoutes() {
        get("/api/nowplaying") {
            val tv = app.tv
            val ch = tv.current.value
            val title = app.queue.nowTitle.value?.takeIf { it.first == ch?.id && ch?.playlist != null }?.second
            call.respond(
                NowPlaying(
                    channel = ch?.let { ApiViews.channel(it) }, title = title, since = tv.since,
                    startedFromApp = tv.startedFromApp, preview = ch != null && ch.number == 0, now = System.currentTimeMillis(),
                )
            )
        }
        post("/api/play") {
            val req = call.receive<PlayRequest>()
            val channel: Channel = when {
                req.channelId != null -> channels.current().channels.firstOrNull { it.id == req.channelId }
                    ?: return@post call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
                req.youtube != null -> {
                    val source = req.youtube.trim()
                    if (YouTubeEmbed.parse(source) == null) return@post call.fail(HttpStatusCode.BadRequest, "That doesn't look like a YouTube link or channel ID.")
                    preview(req.name ?: "YouTube", SourceType.YOUTUBE, source)
                }
                req.providerId != null && req.itemId != null -> {
                    val provider: Provider = providers.find(req.providerId)
                        ?: return@post call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
                    val item = app.catalogs.catalog(provider.id)?.items?.firstOrNull { it.type == CatalogType.LIVE && it.id == req.itemId && it.url != null }
                        ?: return@post call.fail(HttpStatusCode.NotFound, "That channel isn't in the list any more.")
                    preview(item.name, SourceType.STREAM, item.url!!).copy(
                        headers = provider.authHeaders() + item.headers, providerId = provider.id, sourceId = item.id, logoUrl = item.logo,
                    )
                }
                req.url != null -> {
                    val url = req.url.trim()
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                        return@post call.fail(HttpStatusCode.BadRequest, "The address should start with http:// or https://.")
                    }
                    preview(req.name ?: "Preview", SourceType.STREAM, url)
                }
                req.playlist != null -> {
                    val spec = withSeed(req.playlist)
                    ApiViews.checkPlaylist(spec, providers.find(spec.providerId) != null)?.let { return@post call.fail(HttpStatusCode.BadRequest, it) }
                    app.queue.restart(PREVIEW_ID)
                    preview(req.name ?: "Preview", ApiViews.filmChannelType(spec), "").copy(playlist = spec)
                }
                else -> return@post call.fail(HttpStatusCode.BadRequest, "Choose something to play.")
            }
            onTv { app.tv.playFromApp(channel) }
            call.respond(
                NowPlaying(
                    channel = ApiViews.channel(channel), since = app.tv.since, startedFromApp = true,
                    preview = channel.number == 0, now = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun preview(name: String, type: SourceType, url: String) =
        Channel(id = PREVIEW_ID, number = 0, name = name.trim().ifEmpty { "Preview" }, type = type, url = url)

    private companion object {
        const val PREVIEW_ID = "preview"
        const val QUEUE_PAGE = 50
    }
}
