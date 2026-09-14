package com.ielali.simpletv.data

import java.util.UUID

/**
 * Minimal M3U / M3U8 playlist parser for IPTV provider lists.
 *
 * Handles the common `#EXTINF:-1 tvg-id="x" tvg-logo="y" group-title="z",Channel name` form followed
 * by the stream URL on the next non-comment line. Channel numbers are NOT assigned here; the
 * repository does that so numbering stays consistent with what is already configured.
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingHeaders = mutableMapOf<String, String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingName = line.substringAfterLast(',', "").trim().ifBlank { attrs["tvg-name"] ?: "" }
                    pendingLogo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    pendingHeaders = mutableMapOf()
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                    pendingHeaders["User-Agent"] = line.substringAfter('=').trim()
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                    pendingHeaders["Referer"] = line.substringAfter('=').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val name = pendingName ?: line.substringAfterLast('/').substringBefore('?')
                    val type = if (isYouTube(line)) SourceType.YOUTUBE else SourceType.STREAM
                    result += Channel(
                        id = UUID.randomUUID().toString(),
                        number = 0,
                        name = name.ifBlank { "Channel" },
                        type = type,
                        url = line,
                        logoUrl = pendingLogo,
                        group = pendingGroup,
                        headers = pendingHeaders.toMap(),
                    )
                    pendingName = null; pendingLogo = null; pendingGroup = null
                    pendingHeaders = mutableMapOf()
                }
            }
        }
        return result
    }

    fun isYouTube(url: String): Boolean =
        url.contains("youtube.com/", ignoreCase = true) || url.contains("youtu.be/", ignoreCase = true)
}
