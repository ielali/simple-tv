package com.ielali.simpletv.data

/**
 * Sort orders for film and series lists on the page, and for a film channel's play order (ADR-025).
 * Titles without the value being sorted on (no year, no score, no rating, no date) go last, and ties
 * go by name, so the order is stable from one page of results to the next.
 */
object CatalogSort {
    enum class Key(val id: String) {
        NAME("name"),
        NEWEST("newest"),
        OLDEST("oldest"),
        SCORE("score"),
        /** Official age rating, youngest audience first (G before PG before M…). */
        RATING("rating"),
        /** When the provider added it, latest first. */
        ADDED("added"),
    }

    fun key(id: String?): Key? = Key.entries.firstOrNull { it.id == id?.trim()?.lowercase() }

    /** A title's score out of 10: TMDB's when the TV has checked it, else the provider's own. */
    fun scoreOf(item: CatalogItem, meta: FilmMeta?): Double? = meta?.score ?: item.score

    fun sort(
        items: List<CatalogItem>,
        key: Key,
        meta: (CatalogItem) -> FilmMeta? = { null },
        certOrder: (CatalogItem, FilmMeta?) -> Int? = { _, _ -> null },
    ): List<CatalogItem> {
        val byName = compareBy<CatalogItem> { it.name.lowercase() }
        if (key == Key.NAME) return items.sortedWith(byName)
        // Each title's value is worked out once; null = unknown, which sorts last.
        val value: (CatalogItem) -> Double? = when (key) {
            Key.NEWEST -> { it -> Years.of(it, meta(it))?.let { y -> -y.toDouble() } }
            Key.OLDEST -> { it -> Years.of(it, meta(it))?.toDouble() }
            Key.SCORE -> { it -> scoreOf(it, meta(it))?.let { s -> -s } }
            Key.RATING -> { it -> certOrder(it, meta(it))?.toDouble() }
            Key.ADDED -> { it -> it.added?.let { a -> -a.toDouble() } }
            Key.NAME -> { _ -> null }
        }
        val keyed = items.map { it to value(it) }
        return keyed.sortedWith(
            compareBy<Pair<CatalogItem, Double?>> { it.second == null }
                .thenBy { it.second ?: 0.0 }
                .thenBy { it.first.name.lowercase() },
        ).map { it.first }
    }
}
