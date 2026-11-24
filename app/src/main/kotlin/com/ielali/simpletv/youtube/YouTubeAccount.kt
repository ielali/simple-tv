package com.ielali.simpletv.youtube

import com.ielali.simpletv.data.FormPoster
import com.ielali.simpletv.data.HttpResult
import com.ielali.simpletv.data.TextFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * The household's YouTube account, read-only (ADR-021): sign-in on the TV with Google's device flow
 * ("go to google.com/device and enter this code") using the household's own OAuth client, then its
 * subscriptions and which of them are live now. Google offers no API for watch history or the home
 * feed, so suggestions come from subscriptions only. No Android, so it is tested with fakes.
 */
class YouTubeAccount(private val fetcher: TextFetcher, private val poster: FormPoster) {

    @Serializable
    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresIn: Int, val interval: Int)

    @Serializable
    data class SubChannel(val id: String, val title: String, val thumbnail: String? = null)

    @Serializable
    data class LiveVideo(val videoId: String, val channelId: String, val title: String)

    sealed interface Poll {
        data class Signed(val accessToken: String, val refreshToken: String?, val expiresIn: Int) : Poll
        data object Pending : Poll
        data object SlowDown : Poll
        data class Stop(val message: String) : Poll
    }

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Problem(val message: String, val signedOut: Boolean = false) : Outcome<Nothing>
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun obj(body: String) = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

    fun start(clientId: String): Outcome<DeviceCode> = call {
        val r = poster.post(DEVICE_URL, mapOf("client_id" to clientId.trim(), "scope" to SCOPE), emptyMap())
        val o = obj(r.body)
        if (!r.ok || o == null) return@call Outcome.Problem(googleError(o, r.code))
        val dc = DeviceCode(
            deviceCode = o["device_code"].str() ?: return@call Outcome.Problem(GENERIC),
            userCode = o["user_code"].str() ?: return@call Outcome.Problem(GENERIC),
            verificationUrl = (o["verification_url"] ?: o["verification_uri"]).str() ?: "https://www.google.com/device",
            expiresIn = o["expires_in"].str()?.toIntOrNull() ?: 1800,
            interval = o["interval"].str()?.toIntOrNull() ?: 5,
        )
        Outcome.Ok(dc)
    }

    fun poll(clientId: String, secret: String, deviceCode: String): Poll = try {
        val r = poster.post(
            TOKEN_URL,
            mapOf("client_id" to clientId.trim(), "client_secret" to secret.trim(), "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"),
            emptyMap(),
        )
        val o = obj(r.body)
        when {
            r.ok && o?.get("access_token").str() != null -> Poll.Signed(o!!["access_token"].str()!!, o["refresh_token"].str(), o["expires_in"].str()?.toIntOrNull() ?: 3600)
            o?.get("error").str() == "authorization_pending" -> Poll.Pending
            o?.get("error").str() == "slow_down" -> Poll.SlowDown
            o?.get("error").str() == "access_denied" -> Poll.Stop(DENIED)
            o?.get("error").str() == "expired_token" -> Poll.Stop("The code ran out of time. Press Connect for a new one.")
            else -> Poll.Stop(googleError(o, r.code))
        }
    } catch (e: IOException) {
        Poll.Pending // a network blip: keep waiting
    }

    /**
     * Google says access_denied both when someone presses Cancel and when the client's Google Cloud
     * project is still "Testing" ("... has not completed the Google verification process").
     */
    private val DENIED = "Google refused the sign-in. If it said the app has not completed verification, " +
        "open the Google Cloud project this client ID belongs to and publish its app (Audience, In production). " +
        "If you pressed Cancel, press Connect to try again."

    /** A fresh access token from the stored refresh token; [Outcome.Problem.signedOut] when Google revoked it. */
    fun refresh(clientId: String, secret: String, refreshToken: String): Outcome<Pair<String, Int>> = call {
        val r = poster.post(TOKEN_URL, mapOf("client_id" to clientId.trim(), "client_secret" to secret.trim(),
            "refresh_token" to refreshToken, "grant_type" to "refresh_token"), emptyMap())
        val o = obj(r.body)
        val token = o?.get("access_token").str()
        when {
            r.ok && token != null -> Outcome.Ok(token to (o!!["expires_in"].str()?.toIntOrNull() ?: 3600))
            o?.get("error").str() == "invalid_grant" -> Outcome.Problem("YouTube signed the TV out. Connect again.", signedOut = true)
            else -> Outcome.Problem(googleError(o, r.code))
        }
    }

    fun revoke(token: String) {
        runCatching { poster.post(REVOKE_URL, mapOf("token" to token), emptyMap()) }
    }

    /** The signed-in account's channel name ("Connected as …"). */
    fun accountName(access: String): Outcome<String> = call {
        val r = fetcher.get("$API/channels?part=snippet&mine=true", bearer(access))
        val items = (obj(r.body)?.get("items") as? JsonArray).orEmpty()
        if (!r.ok) return@call apiProblem(r)
        Outcome.Ok(((items.firstOrNull() as? JsonObject)?.get("snippet") as? JsonObject)?.get("title").str() ?: "your YouTube account")
    }

    /** Every subscription, alphabetically, up to [max]. */
    fun subscriptions(access: String, max: Int = 500): Outcome<List<SubChannel>> = call {
        val out = ArrayList<SubChannel>()
        var page: String? = null
        do {
            val r = fetcher.get("$API/subscriptions?part=snippet&mine=true&maxResults=50&order=alphabetical" + (page?.let { "&pageToken=${enc(it)}" } ?: ""), bearer(access))
            if (!r.ok) return@call apiProblem(r)
            val o = obj(r.body) ?: return@call Outcome.Problem(GENERIC)
            (o["items"] as? JsonArray).orEmpty().forEach { el ->
                val s = (el as? JsonObject)?.get("snippet") as? JsonObject ?: return@forEach
                val id = ((s["resourceId"] as? JsonObject)?.get("channelId")).str() ?: return@forEach
                val thumbs = s["thumbnails"] as? JsonObject
                val thumb = listOf("medium", "default", "high").firstNotNullOfOrNull { ((thumbs?.get(it) as? JsonObject)?.get("url")).str() }
                out += SubChannel(id, s["title"].str() ?: id, thumb)
            }
            page = o["nextPageToken"].str()
        } while (page != null && out.size < max)
        Outcome.Ok(out.take(max))
    }

    /** Each channel's uploads playlist, 50 channels per call (1 quota unit each). */
    fun uploadsPlaylists(access: String, channelIds: List<String>): Outcome<Map<String, String>> = call {
        val out = HashMap<String, String>()
        for (batch in channelIds.chunked(50)) {
            val r = fetcher.get("$API/channels?part=contentDetails&maxResults=50&id=${enc(batch.joinToString(","))}", bearer(access))
            if (!r.ok) return@call apiProblem(r)
            (obj(r.body)?.get("items") as? JsonArray).orEmpty().forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val id = o["id"].str() ?: return@forEach
                val up = (((o["contentDetails"] as? JsonObject)?.get("relatedPlaylists") as? JsonObject)?.get("uploads")).str() ?: return@forEach
                out[id] = up
            }
        }
        Outcome.Ok(out)
    }

    /** The latest few videos of one uploads playlist (1 unit). */
    fun latestVideos(access: String, playlistId: String, max: Int = 3): Outcome<List<String>> = call {
        val r = fetcher.get("$API/playlistItems?part=contentDetails&maxResults=$max&playlistId=${enc(playlistId)}", bearer(access))
        if (r.code == 404) return@call Outcome.Ok(emptyList())
        if (!r.ok) return@call apiProblem(r)
        Outcome.Ok((obj(r.body)?.get("items") as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("contentDetails") as? JsonObject)?.get("videoId").str() })
    }

    /** Which of these videos are live right now, 50 per call (1 unit each). */
    fun liveNow(access: String, videoIds: List<String>): Outcome<List<LiveVideo>> = call {
        val out = ArrayList<LiveVideo>()
        for (batch in videoIds.distinct().chunked(50)) {
            val r = fetcher.get("$API/videos?part=snippet&maxResults=50&id=${enc(batch.joinToString(","))}", bearer(access))
            if (!r.ok) return@call apiProblem(r)
            (obj(r.body)?.get("items") as? JsonArray).orEmpty().forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val s = o["snippet"] as? JsonObject ?: return@forEach
                if (s["liveBroadcastContent"].str() == "live") {
                    out += LiveVideo(o["id"].str() ?: return@forEach, s["channelId"].str() ?: return@forEach, s["title"].str() ?: "")
                }
            }
        }
        Outcome.Ok(out)
    }

    private inline fun <T> call(block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: IOException) {
        Outcome.Problem("The TV couldn't reach YouTube. Check its internet connection.")
    }

    private fun apiProblem(r: HttpResult): Outcome.Problem {
        val reason = (((obj(r.body)?.get("error") as? JsonObject)?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("reason").str()
        return when {
            r.code == 401 -> Outcome.Problem("YouTube signed the TV out. Connect again.", signedOut = true)
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> Outcome.Problem("YouTube's daily limit for your key is used up. It resets overnight.")
            reason == "accessNotConfigured" || reason == "SERVICE_DISABLED" -> Outcome.Problem("Turn on the YouTube Data API v3 in your Google Cloud project.")
            r.code == 403 -> Outcome.Problem("YouTube didn't allow this. Check the Google Cloud project's settings.")
            else -> Outcome.Problem(GENERIC)
        }
    }

    private fun googleError(o: JsonObject?, code: Int): String = when (o?.get("error").str()) {
        "invalid_client", "unauthorized_client" -> "Google didn't accept the client ID or secret. Use a client of the type \"TVs and Limited Input devices\"."
        "invalid_scope" -> "This kind of Google client can't read YouTube. Use a client of the type \"TVs and Limited Input devices\"."
        "access_denied" -> DENIED
        else -> if (code == 429) "Google is busy. Try again in a minute." else GENERIC
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val API = "https://www.googleapis.com/youtube/v3"
        private const val GENERIC = "YouTube didn't answer as expected. Try again later."
    }
}
