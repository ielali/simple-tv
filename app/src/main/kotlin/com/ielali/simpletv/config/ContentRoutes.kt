package com.ielali.simpletv.config

import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.data.Years
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
import com.ielali.simpletv.data.QueuePlayback
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
import kotlinx.coroutines.launch
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
    @Serializable data class QueueView(
        val total: Int, val entries: List<EntryView>, val resume: String? = null, val resumeItemId: String? = null,
        /** Film channels with rating rules: how many of their films have been checked so far. */
        val checked: Int? = null, val toCheck: Int? = null,
        /** Position of the first entry in the whole queue, and how many are left from where it stopped. */
        val start: Int = 0, val remaining: Int = 0,
    )
    @Serializable data class DetailsView(
        val itemId: String, val type: CatalogType, val title: String,
        val poster: String? = null, val backdrop: String? = null, val plot: String? = null,
        val cast: List<String> = emptyList(), val director: String? = null, val genres: List<String> = emptyList(),
        val year: String? = null, val score: Double? = null, val trailer: String? = null,
        /** Official rating in the household's country, and what it means. */
        val rating: String? = null, val ratingMeaning: String? = null, val providerRating: String? = null,
        val country: String, val fromTmdb: Boolean = false, val matchedByTitle: Boolean = false,
        /** The title's IMDb page, when its IMDb id is known. */
        val imdbUrl: String? = null,
    )
    @Serializable data class RatingsView(
        val country: String, val tvRegion: String, val chosen: String, val hasTmdbKey: Boolean,
        val countries: List<String>, val film: List<com.ielali.simpletv.data.Cert>, val series: List<com.ielali.simpletv.data.Cert>,
    )
    @Serializable data class TmdbKey(val key: String = "")
    @Serializable data class Control(val action: String = "")
    @Serializable data class FoundLogo(val off: Boolean = false)
    @Serializable data class GuideNow(val channels: Map<String, com.ielali.simpletv.data.NowNext>, val now: Long)
    @Serializable data class GuideSourceView(
        val id: String, val label: String, val kind: String, val chosen: Boolean,
        val fetchedAt: Long = 0L, val ok: Boolean = true, val error: String? = null, val channels: Int = 0,
        /** Guide links the caregiver typed are shown back; provider and preset addresses are not. */
        val url: String? = null,
    )
    @Serializable data class GuideView(val sources: List<GuideSourceView>, val refreshing: Boolean, val usingDefault: Boolean, val region: String)
    @Serializable data class GuideSources(val sources: List<String>? = null)
    @Serializable data class GuideChannelView(val key: String, val name: String, val source: String)
    @Serializable data class GuideMatchView(val channelId: String, val match: com.ielali.simpletv.data.GuideService.Match, val nowNext: com.ielali.simpletv.data.NowNext? = null)
    @Serializable data class GuideMatchSet(val channelId: String, val key: String? = null)
    @Serializable data class PlayItem(val itemId: String = "")
    @Serializable data class TmdbKeyState(val hasTmdbKey: Boolean)
    @Serializable data class QueuePreview(val playlist: PlaylistSpec, val offset: Int = 0, val limit: Int = QUEUE_PAGE)

    @Serializable data class YouTubeInput(val input: String = "")
    @Serializable data class YouTubeKey(val key: String = "")
    @Serializable data class YtClient(val clientId: String? = null, val clientSecret: String? = null)
    @Serializable data class YtAccountView(
        val hasClient: Boolean, val clientId: String? = null, val connected: Boolean, val accountName: String? = null,
        val pending: com.ielali.simpletv.data.YouTubeSuggestions.Pending? = null, val error: String? = null, val now: Long,
    )
    @Serializable data class YtSuggestion(
        val channelId: String, val title: String, val thumbnail: String? = null,
        val live: Boolean = false, val liveTitle: String? = null, val lineupNumber: Int? = null,
    )
    @Serializable data class YtSuggestions(
        val connected: Boolean, val accountName: String? = null, val items: List<YtSuggestion> = emptyList(),
        val fetchedAt: Long = 0L, val liveCheckedAt: Long = 0L, val checking: Boolean = false, val error: String? = null,
    )
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
        /** Film and series channels: the item on screen and whether it is paused. */
        val itemId: String? = null,
        val paused: Boolean = false,
        /** Film and series channels: the subtitles and soundtracks the item on screen has. */
        val tracks: com.ielali.simpletv.player.TrackPicker.Report? = null,
        val now: Long,
    )

    private val channels get() = app.channels
    private val providers get() = app.providers

    private suspend fun lineup(): ApiViews.Lineup {
        val list = channels.current().channels
        val found = app.logos.found.value
        val base = ApiViews.lineup(
            list, app.catalog.flags(list),
            found.mapNotNull { (k, v) -> v.url?.let { k to it } }.toMap(),
            found.filterValues { it.off }.keys,
        )
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

    /**
     * A page of a channel's queue. [fromResume]: count [offset] from where the channel stopped (series
     * lists show what is still to come).
     */
    private suspend fun queueView(spec: PlaylistSpec, channelId: String?, offset: Int = 0, limit: Int = QUEUE_PAGE, fromResume: Boolean = false): QueueView {
        val q = app.queue.queue(spec)
        val progress = channelId?.let { app.queue.progress(it) }
        val resume = progress?.itemId?.let { id -> q.firstOrNull { it.id == id } }
        val base = if (fromResume && resume != null) q.indexOf(resume).coerceAtLeast(0) else 0
        val from = (base + offset.coerceAtLeast(0)).coerceAtMost(q.size)
        val page = q.drop(from).take(limit.coerceIn(1, 200))
        val check = if (spec.mode == PlaylistMode.FILM && com.ielali.simpletv.data.RatingRules.hasRules(spec)) {
            app.appScope.launch { app.meta.checkSpec(spec) }
            app.meta.progress(spec)
        } else null
        return QueueView(q.size, page.map(::entryView), resume?.title, resume?.id, check?.first, check?.second, start = from, remaining = q.size - base)
    }

    private fun withSeed(spec: PlaylistSpec) = Years.sanitize(
        if (spec.shuffle && spec.seed == 0L) spec.copy(seed = Random.nextLong(1, Long.MAX_VALUE)) else spec,
    )

    fun Route.install() {
        lineupRoutes()
        providerRoutes()
        catalogRoutes()
        filmRoutes()
        youtubeRoutes()
        detailsRoutes()
        guideRoutes()
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
            app.meta.forget(id)
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
                details = { app.meta.cached(pid, it.type, it.id) }, country = app.meta.country(),
                minYear = Years.parse(qp["from"]), maxYear = Years.parse(qp["to"]),
                sort = com.ielali.simpletv.data.CatalogSort.key(qp["sort"]), certOrder = app.meta::certOrder,
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
                if (spec.mode == PlaylistMode.FILM) app.appScope.launch { app.meta.checkSpec(spec) }
                return@post call.respond(Added(ApiViews.channel(updated), lineup()))
            }
            val ch = Channel(
                id = UUID.randomUUID().toString(), number = 0, name = name, type = ApiViews.filmChannelType(spec),
                url = "", providerId = null, playlist = spec,
            )
            val added = call.addChannel(ch, req.number) ?: return@post
            if (spec.mode == PlaylistMode.FILM) app.appScope.launch { app.meta.checkSpec(spec) }
            call.respond(Added(ApiViews.channel(added), lineup()))
        }
        get("/api/channels/{id}/queue") {
            val id = call.parameters["id"].orEmpty()
            val ch = channels.current().channels.firstOrNull { it.id == id && it.playlist != null }
                ?: return@get call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            val qp = call.request.queryParameters
            call.respond(queueView(ch.playlist!!, ch.id, qp["offset"]?.toIntOrNull() ?: 0, qp["limit"]?.toIntOrNull() ?: QUEUE_PAGE, qp["fromResume"] == "1"))
        }
        post("/api/films/queue-preview") {
            val req = call.receive<QueuePreview>()
            val spec = Years.sanitize(req.playlist)
            if (providers.find(spec.providerId) == null) return@post call.fail(HttpStatusCode.NotFound, "Choose a subscription first.")
            call.respond(queueView(spec, null, req.offset, req.limit))
        }
        // A logo the TV found by itself: turn it off for this channel, or look again (ADR-027).
        put("/api/channels/{id}/found-logo") {
            val id = call.parameters["id"].orEmpty()
            if (channels.current().channels.none { it.id == id }) return@put call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            val req = call.receive<FoundLogo>()
            app.logos.setOff(id, req.off)
            call.respond(lineup())
        }
        post("/api/channels/{id}/restart") {
            val id = call.parameters["id"].orEmpty()
            val ch = channels.current().channels.firstOrNull { it.id == id && it.playlist != null }
                ?: return@post call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            app.queue.restart(ch.id)
            call.respond(queueView(ch.playlist!!, ch.id))
        }
    }

    private fun Route.detailsRoutes() {
        get("/api/details/{pid}/{type}/{itemId}") {
            val pid = call.parameters["pid"].orEmpty()
            if (providers.find(pid) == null) return@get call.fail(HttpStatusCode.NotFound, "That subscription isn't on the TV any more.")
            val type = parseType(call.parameters["type"])
            val itemId = call.parameters["itemId"].orEmpty()
            val item = app.catalogs.catalog(pid)?.items?.firstOrNull { it.type == type && it.id == itemId }
                ?: return@get call.fail(HttpStatusCode.NotFound, "That title isn't in the list any more.")
            val m = app.meta.details(pid, item)
            val country = app.meta.country()
            val cert = com.ielali.simpletv.data.RatingRules.certOf(m, country)
            val meaning = cert?.let { c -> app.meta.lists()?.of(type, country)?.firstOrNull { it.certification.equals(c, true) }?.meaning }
            call.respond(
                DetailsView(
                    itemId = item.id, type = type, title = item.name,
                    poster = m?.poster ?: item.logo, backdrop = m?.backdrop, plot = m?.plot, cast = m?.cast.orEmpty(),
                    director = m?.director, genres = m?.genres.orEmpty(), year = m?.year ?: item.year, score = m?.score,
                    trailer = m?.trailer, rating = cert, ratingMeaning = meaning?.takeIf { it.isNotBlank() },
                    providerRating = m?.providerRating, country = country,
                    fromTmdb = (m?.tmdbAt ?: 0L) > 0 && m?.tmdbId != null, matchedByTitle = m?.matched == "title",
                    imdbUrl = m?.imdbId?.let { "https://www.imdb.com/title/$it/" },
                )
            )
        }
        get("/api/ratings") {
            val lists = app.meta.lists()
            val country = app.meta.country()
            call.respond(
                RatingsView(
                    country = country, tvRegion = app.meta.tvRegion(), chosen = app.settings.current().ratingCountry,
                    hasTmdbKey = app.meta.hasKey(),
                    countries = ((lists?.movie?.keys.orEmpty()) + (lists?.tv?.keys.orEmpty())).toSortedSet().toList(),
                    film = lists?.of(CatalogType.FILM, country).orEmpty(), series = lists?.of(CatalogType.SERIES, country).orEmpty(),
                )
            )
        }
        put("/api/tmdb/key") {
            when (val r = app.meta.setKey(call.receive<TmdbKey>().key)) {
                is com.ielali.simpletv.data.TmdbClient.Check.Ok -> call.respond(TmdbKeyState(app.meta.hasKey()))
                is com.ielali.simpletv.data.TmdbClient.Check.Problem -> call.fail(HttpStatusCode.UnprocessableEntity, r.message, "tmdb_key")
            }
        }
    }

    private suspend fun guideView(): GuideView {
        val chosen = app.guide.chosenIds().toSet()
        val inUse = app.guide.sources()
        val views = inUse.map { s ->
            val st = app.guide.status(s.id)
            GuideSourceView(
                id = s.id, label = s.label, kind = if (s.provider) "provider" else if (s.id.startsWith("url:")) "link" else "preset",
                chosen = true, fetchedAt = st?.fetchedAt ?: 0L, ok = st?.ok ?: true, error = st?.error, channels = st?.channels?.size ?: 0,
                url = s.url.takeIf { s.id.startsWith("url:") },
            )
        } + com.ielali.simpletv.data.GuidePresets.ALL.filter { it.id !in chosen }.map { GuideSourceView(it.id, it.label, "preset", chosen = false) }
        return GuideView(views, app.guide.refreshing, app.settings.current().guideSources == null, app.guide.region())
    }

    private fun Route.guideRoutes() {
        get("/api/guide/now") {
            val lineup = channels.current().channels.filter { it.type == SourceType.STREAM }
            call.respond(GuideNow(lineup.mapNotNull { ch -> app.guide.nowNext(ch)?.let { ch.id to it } }.toMap(), System.currentTimeMillis()))
        }
        get("/api/guide") { call.respond(guideView()) }
        put("/api/guide/sources") {
            val req = call.receive<GuideSources>()
            val merged = com.ielali.simpletv.data.SettingsMerge.sanitize(app.settings.current().copy(guideSources = req.sources))
            app.settings.replace(merged)
            app.appScope.launch { app.guide.refresh(force = true) }
            call.respond(guideView())
        }
        post("/api/guide/refresh") {
            app.appScope.launch { app.guide.refresh(force = true) }
            call.respond(HttpStatusCode.Accepted, guideView().copy(refreshing = true))
        }
        get("/api/guide/channels") {
            val q = call.request.queryParameters["q"].orEmpty()
            call.respond(app.guide.search(q).map { GuideChannelView(it.key, it.names.firstOrNull() ?: it.id, app.guide.label(it.source) ?: it.source) })
        }
        get("/api/guide/match/{channelId}") {
            val ch = channels.current().channels.firstOrNull { it.id == call.parameters["channelId"] }
                ?: return@get call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            call.respond(GuideMatchView(ch.id, app.guide.match(ch), app.guide.nowNext(ch)))
        }
        put("/api/guide/match") {
            val req = call.receive<GuideMatchSet>()
            val ch = channels.current().channels.firstOrNull { it.id == req.channelId }
                ?: return@put call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            app.guide.setOverride(ch.id, req.key)
            if (req.key == null) app.appScope.launch { runCatching { app.guide.touch(ch) } }
            call.respond(GuideMatchView(ch.id, app.guide.match(ch), app.guide.nowNext(ch)))
        }
    }

    private fun ytAccount(): YtAccountView {
        val s = app.ytSuggestions
        return YtAccountView(
            hasClient = s.hasClient(),
            // The client ID is not a secret (it shows on Google's sign-in page); the secret never leaves the TV.
            clientId = s.clientId(),
            connected = s.connected(), accountName = s.cache().accountName,
            pending = s.pending, error = s.error, now = System.currentTimeMillis(),
        )
    }

    private fun Route.youtubeRoutes() {
        post("/api/youtube/preview") {
            val input = call.receive<YouTubeInput>().input
            when (val r = withContext(Dispatchers.IO) { youtube.preview(input, app.secrets.current().youtubeApiKey) }) {
                is YouTubeLookup.Result.Found -> call.respond(r.preview)
                is YouTubeLookup.Result.Problem -> call.fail(HttpStatusCode.UnprocessableEntity, r.message, r.code)
            }
        }
        get("/api/youtube/account") { call.respond(ytAccount()) }
        put("/api/youtube/client") {
            val req = call.receive<YtClient>()
            app.ytSuggestions.saveClient(req.clientId, req.clientSecret)
            call.respond(ytAccount())
        }
        post("/api/youtube/connect") {
            if (!app.ytSuggestions.hasClient()) return@post call.fail(HttpStatusCode.BadRequest, "Add the client ID and secret first.")
            app.ytSuggestions.connect()
            call.respond(ytAccount())
        }
        post("/api/youtube/cancel") {
            app.ytSuggestions.cancel()
            call.respond(ytAccount())
        }
        delete("/api/youtube/client") {
            app.ytSuggestions.forgetClient()
            call.respond(ytAccount())
        }
        post("/api/youtube/disconnect") {
            app.ytSuggestions.disconnect()
            call.respond(ytAccount())
        }
        get("/api/youtube/suggestions") {
            val s = app.ytSuggestions
            s.freshen()
            val c = s.cache()
            val live = c.live.groupBy { it.channelId }
            val lineup = channels.current().channels.filter { it.type == SourceType.YOUTUBE }
            fun inLineup(id: String) = lineup.firstOrNull { it.url.contains(id) }?.number
            val items = c.subs.map { sub ->
                val l = live[sub.id]?.firstOrNull()
                YtSuggestion(sub.id, sub.title, sub.thumbnail, l != null, l?.title, inLineup(sub.id))
            }.sortedWith(compareBy({ !it.live }, { it.title.lowercase() }))
            call.respond(YtSuggestions(s.connected(), c.accountName, items, c.fetchedAt, c.liveCheckedAt, s.checking, s.error))
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
                    channel = ch?.let { ApiViews.channel(it, foundLogo = app.logos.logoFor(it.id)) }, title = title, since = tv.since,
                    startedFromApp = tv.startedFromApp, preview = ch != null && ch.number == 0,
                    tracks = app.nowTracks.value?.takeIf { it.first == ch?.id && ch?.playlist != null }?.second,
                    itemId = app.queue.state.value?.takeIf { it.channelId == ch?.id }?.itemId,
                    paused = app.queue.state.value?.takeIf { it.channelId == ch?.id }?.paused ?: false,
                    now = System.currentTimeMillis(),
                )
            )
        }
        // Next, back to the start, previous, pause or carry on, on the film or series channel on screen.
        post("/api/nowplaying/control") {
            val action = com.ielali.simpletv.player.QueueKeys.fromName(call.receive<Control>().action)
                ?: return@post call.fail(HttpStatusCode.BadRequest, "Choose next, restart, previous, pause or resume.")
            val ch = app.tv.current.value
            if (ch?.playlist == null) return@post call.fail(HttpStatusCode.Conflict, "The TV isn't on a film or series channel right now.")
            if (!app.queue.send(QueuePlayback.Command(ch.id, action))) return@post call.fail(HttpStatusCode.ServiceUnavailable, "The TV is busy. Try again.")
            call.respond(HttpStatusCode.Accepted, Control(action.name.lowercase()))
        }
        // "Play this one now": the channel carries on from the chosen film or episode.
        post("/api/channels/{id}/play-item") {
            val id = call.parameters["id"].orEmpty()
            val ch = channels.current().channels.firstOrNull { it.id == id && it.playlist != null }
                ?: return@post call.fail(HttpStatusCode.NotFound, "That channel isn't on the TV any more.")
            val itemId = call.receive<PlayItem>().itemId
            if (app.queue.queue(ch.playlist!!).none { it.id == itemId }) return@post call.fail(HttpStatusCode.NotFound, "That one isn't in the channel any more.")
            app.queue.choose(ch.id, itemId)
            if (app.tv.current.value?.id == ch.id) app.queue.send(QueuePlayback.Command(ch.id, null, reload = true))
            else onTv { app.tv.playFromApp(ch) }
            call.respond(HttpStatusCode.Accepted, PlayItem(itemId))
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
                    channel = ApiViews.channel(channel, foundLogo = app.logos.logoFor(channel.id)), since = app.tv.since, startedFromApp = true,
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
