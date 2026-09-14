package com.ielali.simpletv.config

import android.content.Context
import android.util.Log
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.AppSettings
import com.ielali.simpletv.data.ChannelRepository
import com.ielali.simpletv.data.SettingsRepository
import com.ielali.simpletv.data.M3uParser
import com.ielali.simpletv.data.Provider
import com.ielali.simpletv.data.ProviderImport
import com.ielali.simpletv.data.ProviderList
import com.ielali.simpletv.data.ProviderRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny LAN-only HTTP server so a caregiver can configure channels from a phone or laptop
 * instead of typing on a TV with a D-pad. Serves the single-page UI from assets/config/index.html.
 *
 * API (all JSON):
 *  GET  /api/status            -> { appVersion, channelCount }
 *  GET  /api/channels          -> ChannelList
 *  PUT  /api/channels          <- ChannelList (replaces everything)
 *  POST /api/import/m3u        <- { url } or { text }  appends parsed channels
 *  GET  /api/settings          -> AppSettings
 *  PUT  /api/settings          <- AppSettings
 *  GET  /api/providers         -> ProviderList with passwords blanked (hasPassword flags instead)
 *  PUT  /api/providers         <- ProviderList; a blank password keeps the stored one
 *  POST /api/providers/import  <- { providerId }  fetches the playlist and adds new channels
 *  POST /api/providers/remove  <- { providerId }  deletes the provider and its channels
 *
 * There is no authentication: it binds to every interface of a device on a home network. Adding a
 * PIN is on the backlog before any wider distribution.
 */
class ConfigServer(
    private val appContext: Context,
    private val repo: ChannelRepository,
    private val settingsRepo: SettingsRepository,
    private val providerRepo: ProviderRepository,
    private val port: Int,
) {
    private var engine: ApplicationEngine? = null

    @Serializable
    data class Status(val appVersion: String, val channelCount: Int, val configUrl: String)

    @Serializable
    data class ImportRequest(val url: String? = null, val text: String? = null)

    @Serializable
    data class ImportResult(val added: Int, val total: Int)

    @Serializable
    data class ErrorBody(val error: String)

    @Serializable
    data class ProviderView(
        val id: String, val name: String, val kind: com.ielali.simpletv.data.ProviderKind, val url: String,
        val username: String?, val logoUrl: String?, val hasPassword: Boolean, val channelCount: Int,
    )

    @Serializable
    data class ProviderViews(val providers: List<ProviderView>)

    @Serializable
    data class ProviderRef(val providerId: String)

    @Serializable
    data class ProviderImportResult(val added: Int, val skipped: Int, val total: Int)

    private fun providerViews(): ProviderViews {
        val counts = repo.current().channels.groupingBy { it.providerId }.eachCount()
        return ProviderViews(providerRepo.current().providers.map {
            ProviderView(it.id, it.name, it.kind, it.url, it.username, it.logoUrl, !it.password.isNullOrEmpty(), counts[it.id] ?: 0)
        })
    }

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing {
                get("/") {
                    val html = withContext(Dispatchers.IO) { appContext.assets.open("config/index.html").bufferedReader().readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/api/status") {
                    val version = runCatching {
                        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                    }.getOrNull() ?: "dev"
                    call.respond(Status(version, repo.current().channels.size, NetworkAddress.configUrl(appContext, port)))
                }
                get("/api/channels") { call.respond(repo.current()) }
                put("/api/channels") {
                    val list = call.receive<ChannelList>()
                    call.respond(repo.replaceAll(list))
                }
                get("/api/settings") { call.respond(settingsRepo.current()) }
                put("/api/settings") { call.respond(settingsRepo.replace(call.receive<AppSettings>())) }
                get("/api/providers") { call.respond(providerViews()) }
                put("/api/providers") {
                    providerRepo.replaceAll(call.receive<ProviderList>())
                    call.respond(providerViews())
                }
                post("/api/providers/import") {
                    val ref = call.receive<ProviderRef>()
                    val provider: Provider? = providerRepo.find(ref.providerId)
                    if (provider == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorBody("Unknown provider"))
                        return@post
                    }
                    val text = runCatching { withContext(Dispatchers.IO) { fetch(provider.playlistUrl(), provider.authHeaders()) } }
                        .getOrElse { e ->
                            call.respond(HttpStatusCode.BadGateway, ErrorBody("Could not download playlist: ${e.message}"))
                            return@post
                        }
                    val parsed = M3uParser.parse(text)
                    if (parsed.isEmpty()) {
                        call.respond(HttpStatusCode.BadGateway, ErrorBody("Playlist was empty or not an M3U. Check the server, username and password."))
                        return@post
                    }
                    val merged = ProviderImport.merge(repo.current().channels, parsed, provider)
                    val updated = repo.replaceAll(repo.current().copy(channels = merged.channels))
                    call.respond(ProviderImportResult(merged.added, merged.skipped, updated.channels.size))
                }
                post("/api/providers/remove") {
                    val ref = call.receive<ProviderRef>()
                    repo.removeProvider(ref.providerId)
                    providerRepo.replaceAll(ProviderList(providers = providerRepo.current().providers.filterNot { it.id == ref.providerId }))
                    call.respond(providerViews())
                }
                post("/api/import/m3u") {
                    val req = call.receive<ImportRequest>()
                    val text = when {
                        !req.text.isNullOrBlank() -> req.text
                        !req.url.isNullOrBlank() -> withContext(Dispatchers.IO) { fetch(req.url) }
                        else -> null
                    }
                    if (text == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("Provide url or text"))
                        return@post
                    }
                    val parsed = M3uParser.parse(text)
                    val updated = repo.append(parsed)
                    call.respond(ImportResult(parsed.size, updated.channels.size))
                }
            }
        }.also {
            runCatching { it.start(wait = false) }
                .onFailure { e -> Log.e(TAG, "Config server failed to start on port $port", e) }
        }
        Log.i(TAG, "Config server listening on ${NetworkAddress.configUrl(appContext, port)}")
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun fetch(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SimpleTV/0.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object { const val TAG = "ConfigServer" }
}
