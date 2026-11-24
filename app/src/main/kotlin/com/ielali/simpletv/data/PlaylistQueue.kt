package com.ielali.simpletv.data

import kotlin.random.Random

/** One thing a film or series channel will play, as shown in the editor's "What will play". */
data class QueueEntry(
    val id: String,
    val title: String,
    val detail: String,
    val ext: String?,
    val minutes: Int?,
    val headers: Map<String, String> = emptyMap(),
)

/** Builds film and series queues from the cached catalog. Pure, so order rules are unit tested. */
object PlaylistQueue {

    /** The films a film channel draws from, before rating rules (for checking their details). */
    fun candidates(catalog: Catalog?, spec: PlaylistSpec): List<CatalogItem> {
        val all = catalog?.of(CatalogType.FILM).orEmpty()
        return if (spec.titles.isNotEmpty()) {
            val byId = all.associateBy { it.id }
            spec.titles.mapNotNull(byId::get)
        } else {
            // Films from outside the channel's years are left out here; unknown years wait for the details.
            all.filter { it.group.orEmpty() in spec.categories && Years.inRange(Years.of(it), spec.minYear, spec.maxYear) }
                .sortedBy { it.name.lowercase() }
        }
    }

    /**
     * [allow] applies the channel's rating rules. It runs after shuffling, so the order stays the
     * same while more films get checked.
     */
    fun films(catalog: Catalog?, spec: PlaylistSpec, allow: (CatalogItem) -> Boolean = { true }): List<QueueEntry> {
        val base = candidates(catalog, spec)
        val ordered = if (spec.shuffle && spec.titles.isEmpty()) base.shuffled(Random(spec.seed)) else base
        return finish(ordered.filter(allow).map { it.toEntry() }, spec)
    }

    fun episodes(all: List<Episode>, spec: PlaylistSpec, headers: Map<String, String> = emptyMap()): List<QueueEntry> =
        all.map { e ->
            QueueEntry(
                id = e.id,
                title = "Season ${e.season}, episode ${e.episode}" + if (e.title.isNotBlank()) " · ${e.title}" else "",
                detail = e.minutes?.let { "$it min" }.orEmpty(),
                ext = e.ext,
                minutes = e.minutes,
                headers = headers,
            )
        }.filterNot { it.id in spec.excluded }

    /** Where to start: the item that was playing last, or the first one. */
    fun resumeIndex(queue: List<QueueEntry>, lastItemId: String?): Int =
        queue.indexOfFirst { it.id == lastItemId }.coerceAtLeast(0)

    /** After the last item the channel starts again from the top. */
    fun nextIndex(queue: List<QueueEntry>, current: Int): Int = if (queue.isEmpty()) 0 else (current + 1) % queue.size

    /** The one before, wrapping to the last item from the first. */
    fun previousIndex(queue: List<QueueEntry>, current: Int): Int = if (queue.isEmpty()) 0 else (current - 1 + queue.size) % queue.size

    private fun finish(entries: List<QueueEntry>, spec: PlaylistSpec): List<QueueEntry> {
        val byId = entries.associateBy { it.id }
        val pinned = spec.first.mapNotNull(byId::get)
        val pinnedIds = pinned.map { it.id }.toSet()
        return (pinned + entries.filterNot { it.id in pinnedIds }).filterNot { it.id in spec.excluded }
    }

    private fun CatalogItem.toEntry() = QueueEntry(
        id = id,
        title = name,
        detail = listOfNotNull(year, minutes?.let(::formatMinutes)).joinToString(" · "),
        ext = ext,
        minutes = minutes,
        headers = headers,
    )

    fun formatMinutes(m: Int): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
