package com.ielali.simpletv.youtube

/**
 * Builds the HTML page loaded into a WebView for YOUTUBE channels.
 *
 * Uses the official IFrame Player API only. Do not add stream-URL extraction here: it breaks the
 * YouTube Terms of Service, it stops working every few weeks, and it makes the app un-publishable.
 *
 * Accepted [source] forms:
 *  - a video id: `dQw4w9WgXcQ`
 *  - a watch / live URL: `https://www.youtube.com/watch?v=...`, `https://youtu.be/...`, `https://www.youtube.com/live/...`
 *  - a channel id starting with `UC`: plays that channel's current live stream
 */
object YouTubeEmbed {

    /** Origin sent to YouTube; the embed is refused when this is empty on some players. */
    const val ORIGIN = "https://simpletv.local"

    private val videoIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val channelIdRegex = Regex("""^UC[A-Za-z0-9_-]{22}$""")

    sealed interface Target {
        data class Video(val id: String) : Target
        data class ChannelLive(val channelId: String) : Target
    }

    fun parse(source: String): Target? {
        val s = source.trim()
        if (channelIdRegex.matches(s)) return Target.ChannelLive(s)
        if (videoIdRegex.matches(s)) return Target.Video(s)
        val url = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        val host = url.host?.lowercase() ?: return null
        val path = url.path ?: ""
        val query = url.rawQuery?.split('&')?.mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else null
        }?.toMap().orEmpty()

        return when {
            host.endsWith("youtu.be") -> path.trimStart('/').takeIf { videoIdRegex.matches(it) }?.let { Target.Video(it) }
            host.endsWith("youtube.com") -> when {
                path == "/watch" -> query["v"]?.takeIf { videoIdRegex.matches(it) }?.let { Target.Video(it) }
                path.startsWith("/live/") || path.startsWith("/embed/") || path.startsWith("/shorts/") ->
                    path.split('/').getOrNull(2)?.takeIf { videoIdRegex.matches(it) }?.let { Target.Video(it) }
                path.startsWith("/channel/") ->
                    path.split('/').getOrNull(2)?.takeIf { channelIdRegex.matches(it) }?.let { Target.ChannelLive(it) }
                path == "/embed/live_stream" -> query["channel"]?.takeIf { channelIdRegex.matches(it) }?.let { Target.ChannelLive(it) }
                else -> null
            }
            else -> null
        }
    }

    fun embedUrl(target: Target): String {
        val params = "autoplay=1&controls=0&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3&fs=0&origin=$ORIGIN"
        return when (target) {
            is Target.Video -> "https://www.youtube.com/embed/${target.id}?$params"
            is Target.ChannelLive -> "https://www.youtube.com/embed/live_stream?channel=${target.channelId}&$params"
        }
    }

    /** Full-bleed page so the video fills the TV with no page chrome. */
    fun html(target: Target): String = """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>html,body{margin:0;background:#000;height:100%;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style>
        </head><body>
        <iframe src="${embedUrl(target)}" allow="autoplay; encrypted-media" allowfullscreen></iframe>
        </body></html>
    """.trimIndent()
}
