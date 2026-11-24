package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogType { LIVE, FILM, SERIES }

/**
 * One entry of a subscription's catalog as cached on the TV. [url] and [headers] are used to play or
 * import LIVE items; they can embed the account password and are never sent to the config page.
 */
@Serializable
data class CatalogItem(
    val id: String,
    val name: String,
    val type: CatalogType,
    val group: String? = null,
    val logo: String? = null,
    val year: String? = null,
    val minutes: Int? = null,
    /** Container extension for films (mp4, mkv…). */
    val ext: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Live channels: the provider's TV guide id. */
    val epgId: String? = null,
)

@Serializable
data class Episode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String = "",
    val ext: String? = null,
    val minutes: Int? = null,
)

/** A subscription's full offer, saved on the TV after each successful refresh. */
@Serializable
data class Catalog(
    val providerId: String,
    val loadedAt: Long,
    val source: String,
    val items: List<CatalogItem> = emptyList(),
    /** An M3U playlist's own XMLTV guide link (`x-tvg-url` / `url-tvg`), when it names one. */
    val guideUrl: String? = null,
) {
    fun of(type: CatalogType): List<CatalogItem> = items.filter { it.type == type }
}

@Serializable
data class RefreshRun(
    val at: Long,
    val ok: Boolean,
    val added: Int = 0,
    val removed: Int = 0,
    val message: String? = null,
)

/** How a subscription's last refreshes went. Kept separately from the (large) catalog. */
@Serializable
data class CatalogStatus(
    val lastRefresh: Long = 0L,
    val lastGood: Long = 0L,
    val ok: Boolean = true,
    val added: Int = 0,
    val removed: Int = 0,
    val error: String? = null,
    /** Account expiry, epoch seconds, from the provider when it reports one. */
    val expiresAt: Long? = null,
    val maxConnections: Int? = null,
    val counts: Map<CatalogType, Int> = emptyMap(),
    val history: List<RefreshRun> = emptyList(),
) {
    fun record(run: RefreshRun, catalog: Catalog?, account: XtreamApi.Account?): CatalogStatus = copy(
        lastRefresh = run.at,
        lastGood = if (run.ok) run.at else lastGood,
        ok = run.ok,
        added = if (run.ok) run.added else added,
        removed = if (run.ok) run.removed else removed,
        error = if (run.ok) null else run.message,
        expiresAt = account?.expiresAt ?: expiresAt,
        maxConnections = account?.maxConnections ?: maxConnections,
        counts = catalog?.let { c -> CatalogType.entries.associateWith { t -> c.items.count { it.type == t } } } ?: counts,
        history = (listOf(run) + history).take(MAX_HISTORY),
    )

    companion object { const val MAX_HISTORY = 10 }
}

/** What changed between two catalogs, counted on live channels (what the lineup is made of). */
object CatalogDiff {
    data class Counts(val added: Int, val removed: Int)

    fun live(old: Catalog?, new: Catalog): Counts {
        if (old == null) return Counts(0, 0)
        val before = old.of(CatalogType.LIVE).map { it.id }.toSet()
        val after = new.of(CatalogType.LIVE).map { it.id }.toSet()
        return Counts(added = (after - before).size, removed = (before - after).size)
    }
}
