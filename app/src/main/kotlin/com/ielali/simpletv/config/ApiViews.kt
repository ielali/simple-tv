package com.ielali.simpletv.config

import com.ielali.simpletv.data.CatalogStatus
import com.ielali.simpletv.data.CatalogType
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.PlaylistMode
import com.ielali.simpletv.data.PlaylistSpec
import com.ielali.simpletv.data.Provider
import com.ielali.simpletv.data.ProviderKind
import com.ielali.simpletv.data.RefreshRun
import com.ielali.simpletv.data.SourceType
import kotlinx.serialization.Serializable

/**
 * What the config page may see. Stream addresses and provider passwords stay on the TV: IPTV
 * addresses embed the account password, so only YouTube sources (not secret) are ever returned.
 * Android-free so the rules are unit tested.
 */
object ApiViews {

    @Serializable
    data class ChannelView(
        val id: String,
        val number: Int,
        val name: String,
        val type: SourceType,
        val logoUrl: String? = null,
        val group: String? = null,
        val providerId: String? = null,
        /** YouTube channels only. */
        val source: String? = null,
        val playlist: PlaylistSpec? = null,
        /** "missing" (the provider dropped it) or "failed" (would not play lately). */
        val flag: String? = null,
    )

    @Serializable
    data class Lineup(val channels: List<ChannelView>, val nextNumber: Int)

    fun channel(c: Channel, flag: String? = null) = ChannelView(
        id = c.id,
        number = c.number,
        name = c.name,
        type = c.type,
        logoUrl = c.logoUrl,
        group = c.group,
        providerId = c.providerId,
        source = c.url.takeIf { c.type == SourceType.YOUTUBE },
        playlist = c.playlist,
        flag = flag,
    )

    fun lineup(channels: List<Channel>, flags: Map<String, String>) = Lineup(
        channels = channels.sortedBy { it.number }.map { channel(it, flags[it.id]) },
        nextNumber = nextNumber(channels),
    )

    fun nextNumber(channels: List<Channel>): Int = (channels.maxOfOrNull { it.number } ?: 0) + 1

    @Serializable
    data class ProviderView(
        val id: String,
        val name: String,
        val kind: ProviderKind,
        val username: String? = null,
        val logoUrl: String? = null,
        val userAgent: String? = null,
        /** Addresses are never returned (M3U links embed the password); the page shows "Saved". */
        val hasUrl: Boolean,
        val hasPassword: Boolean,
        val channelCount: Int,
        /** Lineup numbers of its channels, for "This also removes its 14 channels (numbers 1–4 …)". */
        val numbers: List<Int>,
        val status: StatusView,
        val refreshing: Boolean = false,
    )

    @Serializable
    data class StatusView(
        val lastRefresh: Long = 0L,
        val lastGood: Long = 0L,
        val ok: Boolean = true,
        val added: Int = 0,
        val removed: Int = 0,
        val error: String? = null,
        val expiresAt: Long? = null,
        val maxConnections: Int? = null,
        val live: Int = 0,
        val films: Int = 0,
        val series: Int = 0,
        val history: List<RefreshRun> = emptyList(),
    )

    fun status(s: CatalogStatus) = StatusView(
        lastRefresh = s.lastRefresh, lastGood = s.lastGood, ok = s.ok, added = s.added, removed = s.removed,
        error = s.error, expiresAt = s.expiresAt, maxConnections = s.maxConnections,
        live = s.counts[CatalogType.LIVE] ?: 0, films = s.counts[CatalogType.FILM] ?: 0, series = s.counts[CatalogType.SERIES] ?: 0,
        history = s.history,
    )

    fun provider(p: Provider, channels: List<Channel>, status: CatalogStatus, refreshing: Boolean): ProviderView {
        val mine = channels.filter { it.providerId == p.id || it.playlist?.providerId == p.id }
        return ProviderView(
            id = p.id, name = p.name, kind = p.kind,
            username = p.username, logoUrl = p.logoUrl, userAgent = p.userAgent,
            hasUrl = p.url.isNotBlank(), hasPassword = !p.password.isNullOrEmpty(),
            channelCount = mine.size, numbers = mine.map { it.number }.sorted(),
            status = status(status), refreshing = refreshing,
        )
    }

    /** What the page sends to add or edit a subscription. Blank url or password keeps the stored value. */
    @Serializable
    data class ProviderInput(
        val id: String? = null,
        val name: String,
        val kind: ProviderKind = ProviderKind.XTREAM,
        val url: String? = null,
        val username: String? = null,
        val password: String? = null,
        val logoUrl: String? = null,
        val userAgent: String? = null,
    )

    sealed interface Upsert {
        data class Ok(val provider: Provider) : Upsert
        data class Invalid(val message: String) : Upsert
    }

    fun upsert(existing: Provider?, input: ProviderInput, newId: () -> String): Upsert {
        val url = input.url?.trim().orEmpty().ifEmpty { existing?.url.orEmpty() }
        val password = input.password?.takeIf { it.isNotEmpty() } ?: existing?.password
        val name = input.name.trim()
        if (name.isEmpty()) return Upsert.Invalid("Give the subscription a name.")
        if (url.isEmpty()) {
            return Upsert.Invalid(if (input.kind == ProviderKind.XTREAM) "Add the server address." else "Add the channel list link.")
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return Upsert.Invalid("The address should start with http:// or https://.")
        }
        if (input.kind == ProviderKind.XTREAM && (input.username.isNullOrBlank() || password.isNullOrEmpty())) {
            return Upsert.Invalid("Add the username and password your provider gave you.")
        }
        return Upsert.Ok(
            Provider(
                id = existing?.id ?: input.id?.takeIf { it.isNotBlank() } ?: newId(),
                name = name,
                kind = input.kind,
                url = url,
                username = input.username?.trim()?.takeIf { it.isNotEmpty() },
                password = password,
                logoUrl = input.logoUrl?.trim()?.takeIf { it.isNotEmpty() },
                userAgent = input.userAgent?.trim()?.takeIf { it.isNotEmpty() },
            )
        )
    }

    /** A new film or series channel as the editor sends it. */
    @Serializable
    data class FilmChannelInput(
        val id: String? = null,
        val number: Int? = null,
        val name: String,
        val playlist: PlaylistSpec,
    )

    fun filmChannelType(spec: PlaylistSpec) = if (spec.mode == PlaylistMode.SERIES) SourceType.SERIES else SourceType.FILMS

    /** Checks a film/series spec before saving; returns a caregiver-readable problem or null. */
    fun checkPlaylist(spec: PlaylistSpec, providerKnown: Boolean): String? = when {
        !providerKnown -> "Choose a subscription first."
        spec.mode == PlaylistMode.SERIES && spec.seriesId.isNullOrBlank() -> "Choose a series."
        spec.mode == PlaylistMode.FILM && spec.categories.isEmpty() && spec.titles.isEmpty() -> "Choose at least one category or film."
        else -> null
    }
}
