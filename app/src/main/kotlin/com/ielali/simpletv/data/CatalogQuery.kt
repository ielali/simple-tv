package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

/** Category list and searchable, paged item views for the config page. Never carries stream addresses. */
object CatalogQuery {

    @Serializable
    data class Category(val id: String, val name: String, val count: Int)

    @Serializable
    data class ItemView(
        val id: String,
        val name: String,
        val logo: String? = null,
        val group: String? = null,
        val year: String? = null,
        val minutes: Int? = null,
        val inLineup: Boolean = false,
        val lineupNumber: Int? = null,
        /** Films and series whose details the TV already has. */
        val poster: String? = null,
        val rating: String? = null,
        val score: Double? = null,
    )

    @Serializable
    data class Page(val total: Int, val offset: Int, val items: List<ItemView>)

    const val MAX_LIMIT = 200

    /** Categories of one type, largest first; uncategorised items under id "" ("No category"). */
    fun categories(catalog: Catalog?, type: CatalogType): List<Category> =
        catalog?.of(type).orEmpty()
            .groupingBy { it.group.orEmpty() }.eachCount()
            .map { (name, count) -> Category(id = name, name = name.ifEmpty { "No category" }, count = count) }
            .sortedWith(compareByDescending<Category> { it.count }.thenBy { it.name.lowercase() })

    /**
     * Items of one type, optionally in one category ([category] is a category id) and matching [q]
     * (case-insensitive, every word must appear, across all categories when no category is given).
     */
    fun items(
        catalog: Catalog?,
        type: CatalogType,
        category: String?,
        q: String?,
        offset: Int,
        limit: Int,
        lineup: List<Channel>,
        providerId: String,
        details: (CatalogItem) -> FilmMeta? = { null },
        country: String = "",
        minYear: Int? = null,
        maxYear: Int? = null,
    ): Page {
        val words = q.orEmpty().lowercase().split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        val years = minYear != null || maxYear != null
        val matching = catalog?.of(type).orEmpty().filter { item ->
            (category == null || item.group.orEmpty() == category) &&
                words.all { w -> item.name.lowercase().contains(w) || item.group.orEmpty().lowercase().contains(w) } &&
                // With a year range, the search shows only titles known to be from those years.
                (!years || Years.of(item, details(item)).let { it != null && Years.inRange(it, minYear, maxYear) })
        }
        val inLineup = lineup.filter { it.providerId == providerId && it.sourceId != null }
            .associate { it.sourceId!! to it.number }
        val from = offset.coerceIn(0, matching.size)
        val page = matching.drop(from).take(limit.coerceIn(1, MAX_LIMIT)).map {
            ItemView(
                id = it.id, name = it.name, logo = it.logo, group = it.group, year = it.year, minutes = it.minutes,
                inLineup = it.id in inLineup, lineupNumber = inLineup[it.id],
            ).withDetails(details(it), country)
        }
        return Page(total = matching.size, offset = from, items = page)
    }

    private fun ItemView.withDetails(m: FilmMeta?, country: String) =
        if (m == null) this else copy(poster = m.poster ?: logo, rating = RatingRules.certOf(m, country) ?: m.providerRating, score = m.score, year = year ?: m.year)
}

/** Channels the caregiver should look at, shown as warnings in the lineup (never deleted silently). */
object LineupFlags {
    const val MISSING = "missing"
    const val FAILED = "failed"

    /**
     * [catalogs] by provider id (only successfully loaded ones); [failedChannelIds] from playback.
     * A live channel is "missing" when its provider's catalog no longer lists its source id.
     */
    fun compute(channels: List<Channel>, catalogs: Map<String, Catalog>, failedChannelIds: Set<String>): Map<String, String> {
        val liveIds = catalogs.mapValues { (_, c) -> c.of(CatalogType.LIVE).map { it.id }.toHashSet() }
        val flags = LinkedHashMap<String, String>()
        for (ch in channels) {
            val ids = ch.providerId?.let(liveIds::get)
            if (ch.type == SourceType.STREAM && ch.sourceId != null && ids != null && ch.sourceId !in ids) {
                flags[ch.id] = MISSING
            } else if (ch.id in failedChannelIds) {
                flags[ch.id] = FAILED
            }
        }
        return flags
    }
}

/** Applying the config page's edited lineup, which carries ids, numbers and names but no addresses. */
object LineupEdit {
    @Serializable
    data class Row(val id: String, val number: Int, val name: String, val logoUrl: String? = null)

    /**
     * Keeps stored channels whose id appears in [rows], in that order, with the edited number, name and
     * logo. Channels missing from [rows] are removed. Unknown ids are ignored: new channels only arrive
     * through the add endpoints, so the page can never inject an address.
     */
    fun apply(stored: List<Channel>, rows: List<Row>): List<Channel> {
        val byId = stored.associateBy { it.id }
        return rows.mapNotNull { r ->
            byId[r.id]?.copy(
                number = r.number,
                name = r.name.trim().ifEmpty { byId.getValue(r.id).name },
                logoUrl = r.logoUrl?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** Repairs lineup channels after a refresh: fresh addresses (new password, new User-Agent) by source id. */
object LineupRepair {
    fun refresh(channels: List<Channel>, provider: Provider, catalog: Catalog): List<Channel> {
        val live = catalog.of(CatalogType.LIVE)
        val byId = live.associateBy { it.id }
        val byUrl = live.filter { it.url != null }.associateBy { it.url!! }
        val auth = provider.authHeaders()
        return channels.map { ch ->
            if (ch.providerId != provider.id || ch.type != SourceType.STREAM) return@map ch
            val item = ch.sourceId?.let(byId::get) ?: byUrl[ch.url]
            if (item?.url == null) ch
            else ch.copy(sourceId = item.id, url = item.url, headers = auth + item.headers, epgId = item.epgId ?: ch.epgId)
        }
    }
}
