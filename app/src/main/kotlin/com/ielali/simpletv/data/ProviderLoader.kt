package com.ielali.simpletv.data

import java.io.IOException

/**
 * Downloads a provider's channel list, trying in order:
 *  1. Xtream `player_api.php` (XTREAM providers): also tells us if the login is wrong or the
 *     account has expired, which a playlist download cannot.
 *  2. The M3U playlist (`get.php` for XTREAM, the link itself for M3U providers).
 * Each step is tried with Simple TV's own User-Agent, then with a widely accepted player one,
 * because some providers turn away apps they do not recognise. The winning User-Agent is stored on
 * the imported channels so playback sends the same one.
 *
 * Messages are caregiver-readable and never contain a URL (see [FetchErrors]).
 */
class ProviderLoader(private val fetcher: TextFetcher) {

    sealed interface Result {
        data class Loaded(
            val channels: List<Channel>,
            val source: String,
            val account: XtreamApi.Account?,
        ) : Result

        data class Failed(val message: String) : Result
    }

    fun load(provider: Provider): Result = when (provider.kind) {
        ProviderKind.XTREAM -> loadXtream(provider)
        ProviderKind.M3U -> loadPlaylist(provider, provider.playlistUrl(), provider.authHeaders())
            .let { it.result ?: Result.Failed(it.failure ?: GENERIC) }
    }

    private fun loadXtream(provider: Provider): Result {
        val server = provider.url
        val user = provider.username.orEmpty()
        val pass = provider.password.orEmpty()
        if (user.isBlank() || pass.isEmpty()) return Result.Failed("Enter the username and password from your provider.")

        var failure: String? = null
        for (ua in USER_AGENTS) {
            val headers = mapOf("User-Agent" to ua)
            val auth = try {
                fetcher.get(XtreamApi.accountUrl(server, user, pass), headers)
            } catch (e: IOException) {
                return Result.Failed(FetchErrors.forNetwork(e)) // unreachable server: a new User-Agent won't help
            }
            if (auth.ok) {
                val account = XtreamApi.parseAccount(auth.body)
                if (account != null) {
                    accountProblem(account)?.let { return Result.Failed(it) }
                    loadApiStreams(server, user, pass, account, ua)?.let { return it }
                }
            } else {
                failure = FetchErrors.forHttp(auth.code)
            }
            val playlist = loadPlaylist(provider, provider.playlistUrl(), headers, ua)
            playlist.result?.let { return it }
            failure = playlist.failure ?: failure
        }
        return Result.Failed(failure ?: GENERIC)
    }

    private fun loadApiStreams(server: String, user: String, pass: String, account: XtreamApi.Account, ua: String): Result? {
        val headers = mapOf("User-Agent" to ua)
        return try {
            val streams = fetcher.get(XtreamApi.liveStreamsUrl(server, user, pass), headers)
            if (!streams.ok) return null
            val categories = fetcher.get(XtreamApi.liveCategoriesUrl(server, user, pass), headers)
                .takeIf { it.ok }?.let { XtreamApi.parseCategories(it.body) }.orEmpty()
            val channels = XtreamApi.parseLiveStreams(streams.body, server, user, pass, categories, account.formats)
            if (channels.isEmpty()) null else Result.Loaded(withUserAgent(channels, ua), "Xtream API", account)
        } catch (e: IOException) {
            null
        }
    }

    private data class Attempt(val result: Result.Loaded?, val failure: String?)

    private fun loadPlaylist(provider: Provider, url: String, headers: Map<String, String>, ua: String? = null): Attempt {
        val agents = if (ua != null) listOf(ua) else USER_AGENTS
        var failure: String? = null
        for (agent in agents) {
            val response = try {
                fetcher.get(url, headers + ("User-Agent" to agent))
            } catch (e: IOException) {
                return Attempt(null, FetchErrors.forNetwork(e))
            }
            if (!response.ok) {
                failure = FetchErrors.forHttp(response.code)
                continue
            }
            val channels = M3uParser.parse(response.body)
            if (channels.isEmpty()) {
                failure = "The provider answered, but not with a channel list. " +
                    if (provider.kind == ProviderKind.XTREAM) "Check the username and password." else "Check the playlist link."
                continue
            }
            return Attempt(Result.Loaded(withUserAgent(channels, agent), "M3U playlist", null), null)
        }
        return Attempt(null, failure)
    }

    private fun withUserAgent(channels: List<Channel>, ua: String): List<Channel> =
        if (ua == DEFAULT_USER_AGENT) channels
        else channels.map { it.copy(headers = mapOf("User-Agent" to ua) + it.headers) }

    companion object {
        const val DEFAULT_USER_AGENT = "SimpleTV/0.1 (Android TV)"
        /** Recognised by most IPTV panels; used only when the provider refuses Simple TV's own name. */
        const val PLAYER_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        val USER_AGENTS = listOf(DEFAULT_USER_AGENT, PLAYER_USER_AGENT)
        private const val GENERIC = "The channel list could not be downloaded."

        fun accountProblem(account: XtreamApi.Account, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): String? = when {
            !account.authenticated -> "The provider did not accept this username and password."
            !account.active -> "The provider says this account is ${account.status}. Contact the provider."
            account.expiresAt != null && account.expiresAt < nowEpochSeconds ->
                "This subscription has expired. Renew it with the provider."
            else -> null
        }

        /** Category name -> channel count, largest first; channels without a category under "". */
        fun groups(channels: List<Channel>): List<Pair<String, Int>> =
            channels.groupingBy { it.group.orEmpty() }.eachCount().toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
}
