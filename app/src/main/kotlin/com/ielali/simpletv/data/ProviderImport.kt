package com.ielali.simpletv.data

/** Pure merge logic for importing a provider's playlist into the existing channel list. */
object ProviderImport {

    data class Result(val channels: List<Channel>, val added: Int, val skipped: Int)

    /**
     * Adds channels from [parsed] that are not already present for [provider] (matched by stream URL),
     * tagging them with the provider id, its auth headers and its icon as a fallback logo.
     * Existing channels and their numbers are untouched; new ones get numbers after the highest.
     */
    fun merge(existing: List<Channel>, parsed: List<Channel>, provider: Provider): Result {
        val known = existing.filter { it.providerId == provider.id }.map { it.url }.toHashSet()
        var next = (existing.maxOfOrNull { it.number } ?: 0) + 1
        val headers = provider.authHeaders()
        val added = mutableListOf<Channel>()
        var skipped = 0
        for (c in parsed) {
            if (!known.add(c.url)) { skipped++; continue }
            added += c.copy(
                number = next++,
                providerId = provider.id,
                logoUrl = c.logoUrl ?: provider.logoUrl,
                headers = headers + c.headers,
            )
        }
        return Result(existing + added, added.size, skipped)
    }
}
