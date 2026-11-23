package com.ielali.simpletv.data

/** Pure merge logic for importing a provider's playlist into the existing channel list. */
object ProviderImport {

    data class Result(val channels: List<Channel>, val added: Int, val skipped: Int, val numbers: List<Int> = emptyList())

    /**
     * Adds channels from [parsed] that are not already present for [provider] (matched by stream URL),
     * tagging them with the provider id, its auth headers and its icon as a fallback logo.
     * Existing channels and their numbers are untouched; new ones get numbers after the highest.
     */
    fun merge(existing: List<Channel>, parsed: List<Channel>, provider: Provider): Result {
        val mine = existing.filter { it.providerId == provider.id }
        val knownUrls = mine.map { it.url }.toHashSet()
        val knownIds = mine.mapNotNull { it.sourceId }.toHashSet()
        var next = (existing.maxOfOrNull { it.number } ?: 0) + 1
        val headers = provider.authHeaders()
        val added = mutableListOf<Channel>()
        var skipped = 0
        for (c in parsed) {
            val duplicate = (c.sourceId != null && !knownIds.add(c.sourceId)) || !knownUrls.add(c.url)
            if (duplicate) { skipped++; continue }
            added += c.copy(
                number = next++,
                providerId = provider.id,
                logoUrl = c.logoUrl ?: provider.logoUrl,
                headers = headers + c.headers,
            )
        }
        return Result(existing + added, added.size, skipped, added.map { it.number })
    }

    /** Lineup channels for catalog LIVE items (from the cache, so no download). */
    fun fromCatalog(items: List<CatalogItem>): List<Channel> = items.filter { it.type == CatalogType.LIVE && it.url != null }.map {
        Channel(
            id = java.util.UUID.randomUUID().toString(),
            number = 0,
            name = it.name,
            type = SourceType.STREAM,
            url = it.url!!,
            logoUrl = it.logo,
            group = it.group,
            headers = it.headers,
            sourceId = it.id,
        )
    }
}
