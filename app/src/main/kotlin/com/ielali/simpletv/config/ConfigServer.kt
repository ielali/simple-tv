package com.ielali.simpletv.config

import android.content.Context
import android.util.Log
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.data.CatalogType
import com.ielali.simpletv.data.FetchErrors
import com.ielali.simpletv.data.HttpFetcher
import com.ielali.simpletv.data.M3uParser
import com.ielali.simpletv.data.PreviewChecks
import com.ielali.simpletv.data.PreviewSessions
import com.ielali.simpletv.data.ProviderImport
import com.ielali.simpletv.data.ProviderLoader
import com.ielali.simpletv.data.SettingsMerge
import com.ielali.simpletv.data.playsOnSimpleTv
import com.ielali.simpletv.guard.GuardBridge
import com.ielali.simpletv.guard.ButtonRoles
import com.ielali.simpletv.guard.GuardKeys
import com.ielali.simpletv.guard.GuardSetup
import com.ielali.simpletv.guard.HomeGuardPolicy
import com.ielali.simpletv.guard.KeyLog
import com.ielali.simpletv.guard.ScreenFingerprint
import com.ielali.simpletv.guard.YouTubeScreenLog
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * LAN-only HTTP server for the caregiver page (assets/config/index.html). Plain HTTP on the home
 * network; HTTPS is parked in the backlog.
 *
 * Rules (docs/design/handoff/README.md): no response carries a provider password or an IPTV address
 * (they embed the password), error messages never contain a URL, and once a PIN is set every /api
 * route except GET /api/auth and POST /api/auth/login needs the session token
 * (`Authorization: Bearer …`). Sessions live in memory and end when the TV restarts.
 *
 * This file holds the shell, sign-in, status, settings, the home guard and the key log. Lineup,
 * subscriptions, catalog, film channels, YouTube and "Play on TV" are in [ContentRoutes].
 */
class ConfigServer(
    private val appContext: Context,
    private val port: Int,
) {
    private val app get() = appContext as SimpleTvApp
    private val settingsRepo get() = app.settings
    private var engine: ApplicationEngine? = null

    private val auth = PinAuth()
    private val content = ContentRoutes(app)

    /** The preview player in the page's channel list and in a subscription's own list (ADR-029). */
    private val preview = PreviewRoutes(
        channels = { app.channels.current().channels },
        catalogChannel = { providerId, itemId ->
            app.providers.find(providerId)?.let { provider ->
                app.catalogs.catalog(providerId)?.items?.firstOrNull { it.type == CatalogType.LIVE && it.id == itemId }
                    ?.let { ProviderImport.tryOut(it, provider, "catalog:$providerId:$itemId") }
            }
        },
        // What Telly's own player holds: the channel on screen and, with the list open, the one in its window.
        onTv = {
            if (app.tv.appVisible.value) listOfNotNull(app.tv.current.value, app.tv.listChannel()).filter { it.playsOnSimpleTv }
            else emptyList()
        },
        streamLimit = { app.catalogs.status(it).maxConnections },
        minutes = { settingsRepo.current().previewMinutes },
        asset = { appContext.assets.open(it) },
        userAgent = ProviderLoader.DEFAULT_USER_AGENT,
        log = { Log.i(PLAYBACK_TAG, it) },
        // What each preview showed, kept across restarts; "Erase Telly" deletes it with the rest of the files.
        checks = PreviewChecks(java.io.File(appContext.filesDir, "preview_checks.json")),
    )
    private var tvWatch: Job? = null

    @Serializable
    data class ErrorBody(val error: String, val code: String? = null)

    @Serializable
    data class EraseRequest(val confirm: String = "")

    @Serializable
    data class Done(val ok: Boolean = true)

    @Serializable
    data class Status(
        val appVersion: String,
        val channelCount: Int,
        val providerCount: Int,
        val configUrl: String,
        val pinSet: Boolean,
        val hasYoutubeKey: Boolean,
        val hasTmdbKey: Boolean = false,
        /** Country whose official ratings apply, and the TV's own region (the default). */
        val ratingCountry: String = "",
        val tvRegion: String = "",
        /** Nothing set up yet: the page opens the three-step first run. */
        val firstRun: Boolean,
        val refreshTime: String,
        val nextRefresh: Long,
        val now: Long,
    )

    @Serializable
    data class AuthState(val pinSet: Boolean, val signedIn: Boolean, val lockedUntil: Long, val now: Long)

    @Serializable
    data class LoginRequest(val pin: String = "")

    @Serializable
    data class LoginResponse(val token: String)

    @Serializable
    data class LoginProblem(val error: String, val code: String, val triesLeft: Int? = null, val retryAfterSeconds: Long? = null)

    @Serializable
    data class PinRequest(val pin: String = "")

    @Serializable
    data class PinResponse(val pinSet: Boolean, val token: String? = null)

    /** The config address as QR modules ("1" dark), drawn by the page as SVG. */
    @Serializable
    data class Qr(val text: String, val size: Int, val rows: List<String>)

    @Serializable
    data class ImportRequest(val url: String? = null, val text: String? = null)

    @Serializable
    data class ImportResult(val added: Int, val total: Int)

    @Serializable
    data class KeyName(val code: Int, val name: String)

    @Serializable
    data class GuardStatus(
        val guardEnabled: Boolean,
        val serviceEnabled: Boolean,
        val canSelfManage: Boolean,
        val pausedUntil: Long,
        val now: Long,
        val blockedKeys: List<KeyName>,
        /** Last automatic choice on YouTube's account screen; 0 if it never happened. */
        val youtubePickerAt: Long = 0L,
        val youtubePickerAction: String = "",
        /** When the account screen's look was remembered; 0 if never. */
        val youtubePickerLearnedAt: Long = 0L,
        /** Recognising it needs screenshots (Android 11) and pressing OK (Android 13). */
        val youtubePickerSupported: Boolean = false,
    )

    @Serializable
    data class PauseRequest(val minutes: Int = 15)

    @Serializable
    data class KeyRow(
        val atMillis: Long, val keyCode: Int, val name: String, val source: String, val action: String,
        val needed: Boolean, val blockedByDefault: Boolean, val blocked: Boolean, val blockable: Boolean,
        val returnsHome: Boolean,
        /** "list", "previous" or "" (ButtonRoles), and whether the button may be given a job. */
        val role: String = "",
        val assignable: Boolean = false,
    )

    private fun keyName(code: Int) = KeyName(code, android.view.KeyEvent.keyCodeToString(code))

    private fun guardStatus(): GuardStatus {
        val s = settingsRepo.current()
        return GuardStatus(
            guardEnabled = s.guardEnabled,
            serviceEnabled = GuardSetup.isServiceEnabled(appContext),
            canSelfManage = GuardSetup.canSelfManage(appContext),
            pausedUntil = s.guardPausedUntil,
            now = System.currentTimeMillis(),
            blockedKeys = s.guardBlockedKeys.map { keyName(it) },
            youtubePickerAt = YouTubeScreenLog.snapshot.lastActionAt,
            youtubePickerAction = YouTubeScreenLog.snapshot.lastAction,
            youtubePickerLearnedAt = (appContext as SimpleTvApp).pickerReference.current().savedAt,
            youtubePickerSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU,
        )
    }

    private fun keyRows(): List<KeyRow> {
        val s = settingsRepo.current()
        val extra = s.guardBlockedKeys.toSet()
        return KeyLog.shared.entries().map {
            KeyRow(
                it.atMillis, it.keyCode, it.name, it.source, it.action,
                needed = it.keyCode in GuardKeys.PROTECTED,
                blockedByDefault = it.keyCode in GuardKeys.DEFAULT_BLOCKED,
                blocked = it.keyCode in extra,
                blockable = HomeGuardPolicy.isBlockable(it.keyCode),
                returnsHome = it.keyCode in GuardKeys.GO_HOME,
                role = when (ButtonRoles.roleOf(it.keyCode, s.listButtons, s.previousButtons)) {
                    ButtonRoles.Role.CHANNEL_LIST -> "list"
                    ButtonRoles.Role.PREVIOUS_CHANNEL -> "previous"
                    null -> ""
                },
                assignable = ButtonRoles.assignable(it.keyCode),
            )
        }
    }

    private fun tokenOf(header: String?): String? =
        header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()

    private fun pinSet() = !app.secrets.current().pin.isNullOrEmpty()

    private fun isOpen(method: HttpMethod, path: String) =
        (method == HttpMethod.Get && path == "/api/auth") || (method == HttpMethod.Post && path == "/api/auth/login")

    private fun status(): Status {
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "dev"
        val channels = app.channels.current().channels.size
        val providers = app.providers.current().providers.size
        val time = settingsRepo.current().catalogRefreshTime
        return Status(
            appVersion = version,
            channelCount = channels,
            providerCount = providers,
            configUrl = NetworkAddress.configUrl(appContext, port),
            pinSet = pinSet(),
            hasYoutubeKey = !app.secrets.current().youtubeApiKey.isNullOrEmpty(),
            hasTmdbKey = app.meta.hasKey(),
            ratingCountry = app.meta.country(),
            tvRegion = app.meta.tvRegion(),
            firstRun = channels == 0 && providers == 0,
            refreshTime = time,
            nextRefresh = app.catalog.nextRefresh(),
            now = System.currentTimeMillis(),
        )
    }

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }) }
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (path.startsWith("/api/")) {
                    // The page and its data must never be cached by the browser or a proxy.
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (pinSet() && !isOpen(call.request.httpMethod, path) &&
                        !auth.isValid(tokenOf(call.request.headers[HttpHeaders.Authorization]))
                    ) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorBody("Enter the PIN shown on the TV.", "signin"))
                        finish()
                    }
                }
            }
            routing {
                get("/") {
                    val html = withContext(Dispatchers.IO) { appContext.assets.open("config/index.html").bufferedReader().readText() }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(html, ContentType.Text.Html)
                }
                authRoutes()
                get("/api/status") { call.respond(status()) }
                get("/api/qr") {
                    val url = NetworkAddress.configUrl(appContext, port)
                    val m = QrCode.encode(url)
                    call.respond(Qr(url, m.size, (0 until m.size).map { y -> (0 until m.size).joinToString("") { x -> if (m[x, y]) "1" else "0" } }))
                }
                get("/api/settings") { call.respond(settingsRepo.current()) }
                put("/api/settings") {
                    val before = settingsRepo.current()
                    val saved = settingsRepo.replace(SettingsMerge.merge(before, call.receive<JsonObject>()))
                    if (saved.guardEnabled != before.guardEnabled) GuardSetup.apply(appContext, saved.guardEnabled)
                    if (saved.hideAdult != before.hideAdult || saved.ratingCountry != before.ratingCountry) {
                        app.appScope.launch { app.meta.checkChannels() }
                    }
                    call.respond(saved)
                }
                get("/api/guard") { call.respond(guardStatus()) }
                post("/api/guard/pause") {
                    val minutes = call.receive<PauseRequest>().minutes.coerceIn(1, 240)
                    val until = System.currentTimeMillis() + minutes * 60_000L
                    settingsRepo.replace(settingsRepo.current().copy(guardPausedUntil = until))
                    call.respond(guardStatus())
                }
                post("/api/guard/resume") {
                    settingsRepo.replace(settingsRepo.current().copy(guardPausedUntil = 0L))
                    call.respond(guardStatus())
                }
                get("/api/debug/youtube-screen") { call.respond(YouTubeScreenLog.snapshot) }
                post("/api/guard/youtube-picker/remember") {
                    val service = GuardBridge.service
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        call.respond(HttpStatusCode.Conflict, ErrorBody("This needs Android 13 or newer on the TV."))
                        return@post
                    }
                    if (service == null) {
                        call.respond(HttpStatusCode.Conflict, ErrorBody("The home guard is not running on the TV. Turn it on first."))
                        return@post
                    }
                    val shot = withContext(Dispatchers.Main) { service.captureFingerprint() }
                    when {
                        shot == null -> call.respond(HttpStatusCode.BadGateway, ErrorBody("The TV did not allow a screen capture. Try again in a moment."))
                        ScreenFingerprint.isBlank(shot) -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorBody("The TV screen looks blank. Press this while YouTube's account screen is showing."),
                        )
                        else -> {
                            (appContext as SimpleTvApp).pickerReference.save(shot, System.currentTimeMillis())
                            call.respond(guardStatus())
                        }
                    }
                }
                delete("/api/guard/youtube-picker") {
                    (appContext as SimpleTvApp).pickerReference.clear()
                    call.respond(guardStatus())
                }
                get("/api/keys") { call.respond(keyRows()) }
                // "Erase Simple TV" (ADR-024): signs out of YouTube, deletes everything Simple TV keeps on the
                // box, then restarts the app so nothing in memory writes it back. The PIN guards this route.
                post("/api/erase") {
                    if (call.receive<EraseRequest>().confirm != "ERASE") {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("Confirm the erase first."))
                        return@post
                    }
                    runCatching { app.ytSuggestions.forgetClient() }
                    call.respond(Done())
                    app.appScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(500)
                        appContext.filesDir.listFiles()?.forEach { it.deleteRecursively() }
                        java.io.File(appContext.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { it.delete() }
                        appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                post("/api/keys/clear") {
                    KeyLog.shared.clear()
                    call.respond(keyRows())
                }
                post("/api/import/m3u") {
                    val req = call.receive<ImportRequest>()
                    val text = when {
                        !req.text.isNullOrBlank() -> req.text
                        !req.url.isNullOrBlank() -> {
                            val response = runCatching {
                                withContext(Dispatchers.IO) {
                                    HttpFetcher().get(req.url, mapOf("User-Agent" to ProviderLoader.DEFAULT_USER_AGENT))
                                }
                            }.getOrElse { e ->
                                val msg = (e as? java.io.IOException)?.let(FetchErrors::forNetwork) ?: "The playlist could not be downloaded."
                                call.respond(HttpStatusCode.BadGateway, ErrorBody(msg))
                                return@post
                            }
                            if (!response.ok) {
                                call.respond(HttpStatusCode.BadGateway, ErrorBody(FetchErrors.forHttp(response.code)))
                                return@post
                            }
                            response.body
                        }
                        else -> null
                    }
                    if (text == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody("Provide url or text"))
                        return@post
                    }
                    val parsed = M3uParser.parse(text)
                    val updated = app.channels.append(parsed)
                    call.respond(ImportResult(parsed.size, updated.channels.size))
                }
                with(content) { install() }
                with(preview) { install() }
            }
        }.also {
            runCatching { it.start(wait = false) }
                .onFailure { e -> Log.e(TAG, "Config server failed to start on port $port", e) }
        }
        // The TV always wins: a preview ends the moment the TV's player needs its subscription.
        tvWatch = app.appScope.launch {
            // Letting go of the provider closes sockets, so that part runs off the main thread. Every few
            // seconds too: a preview whose browser has vanished must not keep its stream.
            launch(Dispatchers.IO) {
                while (true) {
                    delay(PREVIEW_SWEEP_MS)
                    runCatching { preview.sweep() }
                }
            }
            combine(app.tv.current, app.tv.appVisible, app.tv.listIndex) { _, _, _ -> }.collect {
                preview.tvChanged()
                launch(Dispatchers.IO) { runCatching { preview.sweep() } }
            }
        }
        Log.i(TAG, "Config server listening on ${NetworkAddress.configUrl(appContext, port)}")
    }

    private fun Route.authRoutes() {
        get("/api/auth") {
            val signedIn = !pinSet() || auth.isValid(tokenOf(call.request.headers[HttpHeaders.Authorization]))
            call.respond(AuthState(pinSet(), signedIn, auth.lockedUntil(), System.currentTimeMillis()))
        }
        post("/api/auth/login") {
            val entered = call.receive<LoginRequest>().pin.trim()
            when (val r = auth.login(entered, app.secrets.current().pin)) {
                is PinAuth.LoginResult.Ok -> call.respond(LoginResponse(r.token))
                is PinAuth.LoginResult.NoPin -> call.respond(LoginResponse(""))
                is PinAuth.LoginResult.Wrong -> call.respond(
                    HttpStatusCode.Unauthorized,
                    LoginProblem(
                        "That PIN didn't match. You have ${r.triesLeft} ${if (r.triesLeft == 1) "try" else "tries"} left.",
                        "wrong", triesLeft = r.triesLeft,
                    ),
                )
                is PinAuth.LoginResult.Locked -> call.respond(
                    HttpStatusCode.TooManyRequests,
                    LoginProblem(
                        "Too many tries. You can try again in ${(r.retryAfterSeconds + 59) / 60} minutes.",
                        "locked", retryAfterSeconds = r.retryAfterSeconds,
                    ),
                )
            }
        }
        // Setting the first PIN needs no sign-in (there was nothing to sign in with); changing or
        // removing one does (the interceptor above). Either way everyone else is signed out.
        put("/api/auth/pin") {
            val pin = call.receive<PinRequest>().pin.trim()
            if (pin.isNotEmpty() && !PinAuth.isValidPin(pin)) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody("Use 4 to 6 digits."))
                return@put
            }
            app.secrets.setPin(pin.ifEmpty { null })
            auth.reset()
            call.respond(PinResponse(pinSet = pin.isNotEmpty(), token = if (pin.isNotEmpty()) auth.issue() else null))
        }
        post("/api/auth/logout") {
            auth.revoke(tokenOf(call.request.headers[HttpHeaders.Authorization]))
            call.respond(AuthState(pinSet(), false, auth.lockedUntil(), System.currentTimeMillis()))
        }
    }

    fun stop() {
        tvWatch?.cancel()
        tvWatch = null
        preview.sessions.end(PreviewSessions.Ended.CLOSED)
        engine?.stop(500, 1000)
        engine = null
    }

    private companion object {
        const val TAG = "ConfigServer"

        /** The playback log's tag (PlaybackEngine): `adb logcat -s SimpleTvPlayback` shows a preview's timings too. */
        const val PLAYBACK_TAG = "SimpleTvPlayback"
        const val PREVIEW_SWEEP_MS = 5_000L
    }
}
