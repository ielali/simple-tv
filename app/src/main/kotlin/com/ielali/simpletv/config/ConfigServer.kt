package com.ielali.simpletv.config

import android.content.Context
import android.util.Log
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.ChannelRepository
import com.ielali.simpletv.data.M3uParser
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
 *
 * There is no authentication: it binds to every interface of a device on a home network. Adding a
 * PIN is on the backlog before any wider distribution.
 */
class ConfigServer(
    private val context: Context,
    private val repo: ChannelRepository,
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

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing {
                get("/") {
                    val html = withContext(Dispatchers.IO) { context.assets.open("config/index.html").bufferedReader().readText() }
                    call.respondText(html, ContentType.Text.Html)
                }
                get("/api/status") {
                    val version = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "dev"
                    call.respond(Status(version, repo.current().channels.size, NetworkAddress.configUrl(context, port)))
                }
                get("/api/channels") { call.respond(repo.current()) }
                put("/api/channels") {
                    val list = call.receive<ChannelList>()
                    call.respond(repo.replaceAll(list))
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
        Log.i(TAG, "Config server listening on ${NetworkAddress.configUrl(context, port)}")
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "SimpleTV/0.1")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object { const val TAG = "ConfigServer" }
}
