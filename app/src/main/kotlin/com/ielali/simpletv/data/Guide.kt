package com.ielali.simpletv.data

import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The TV guide (ADR-020): what is on now and next on live channels. Sources: the provider's own
 * guide (Xtream `get_short_epg`, or the M3U header's XMLTV link) and, as a fallback, a free XMLTV
 * guide the caregiver chose. Everything here is pure so it is unit tested; GuideService does I/O.
 */
@Serializable
data class Programme(
    /** Epoch millis. */
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String? = null,
    val category: String? = null,
    /** "S1 E4" when the guide says. */
    val episode: String? = null,
)

/** A channel of a guide source, for matching and for the page's "pick the guide channel" search. */
@Serializable
data class GuideChannel(val key: String, val id: String, val names: List<String>, val source: String)

/** Now and next for one lineup channel. */
@Serializable
data class NowNext(val now: Programme? = null, val next: Programme? = null, val source: String? = null) {
    companion object {
        fun of(list: List<Programme>, at: Long, source: String?): NowNext {
            val sorted = list.sortedBy { it.start }
            val now = sorted.lastOrNull { it.start <= at && at < it.stop }
            val next = sorted.firstOrNull { it.start >= (now?.stop ?: at) && it !== now }
            return NowNext(now, next, source)
        }
    }
}

object XmltvTime {
    private val fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** "20261124193000 +1100" -> epoch millis; no offset means UTC (XMLTV DTD). Null when unreadable. */
    fun parse(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.length < 12) return null
        val digits = s.takeWhile { it.isDigit() }.padEnd(14, '0').take(14)
        val rest = s.drop(s.takeWhile { it.isDigit() }.length).trim()
        val offset = Regex("""^([+-])(\d{2}):?(\d{2})""").find(rest)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt())
        } ?: ZoneOffset.UTC
        return runCatching { LocalDateTime.parse(digits, fmt).toInstant(offset).toEpochMilli() }.getOrNull()
    }
}

/**
 * Streams an XMLTV document (never loading it whole: provider guides can list tens of thousands of
 * channels). Programmes are kept only for channels [keep] accepts and only inside [from]..[to].
 */
object XmltvReader {
    fun read(
        parser: XmlPullParser,
        from: Long,
        to: Long,
        keep: (String) -> Boolean,
        onChannel: (id: String, names: List<String>) -> Unit,
        onProgramme: (channelId: String, programme: Programme) -> Unit,
    ) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let { (id, names) -> onChannel(id, names) }
                    "programme" -> {
                        val channel = parser.getAttributeValue(null, "channel")
                        val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                        val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                        if (channel == null || start == null || !keep(channel) || start > to || (stop != null && stop < from)) skip(parser)
                        else readProgramme(parser, start, stop)?.let { onProgramme(channel, it) }
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun readChannel(p: XmlPullParser): Pair<String, List<String>>? {
        val id = p.getAttributeValue(null, "id") ?: return null.also { skip(p) }
        val names = ArrayList<String>()
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") names += p.nextText().trim()
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
        }
        return id to names.filter { it.isNotEmpty() }
    }

    private fun readProgramme(p: XmlPullParser, start: Long, stopAttr: Long?): Programme? {
        var title: String? = null; var desc: String? = null; var category: String? = null; var episode: String? = null
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "title" -> if (title == null) title = p.nextText().trim()
                "desc" -> if (desc == null) desc = p.nextText().trim()
                "category" -> if (category == null) category = p.nextText().trim()
                "episode-num" -> {
                    val system = p.getAttributeValue(null, "system")
                    val text = p.nextText().trim()
                    if (episode == null || system == "onscreen") episode = if (system == "xmltv_ns") episodeNs(text) ?: episode else text
                }
                else -> skip(p)
            }
        }
        val t = title?.takeIf { it.isNotEmpty() } ?: return null
        return Programme(start, stopAttr ?: (start + 30 * 60_000L), t, desc?.takeIf { it.isNotEmpty() }, category, episode)
    }

    /** xmltv_ns counts from zero: "0.3.0/1" is season 1, episode 4. */
    fun episodeNs(s: String): String? {
        val parts = s.split('.')
        val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
        val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
        return when {
            season != null && ep != null -> "S${season + 1} E${ep + 1}"
            ep != null -> "E${ep + 1}"
            else -> null
        }
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

/** Finds a lineup channel's guide channel: its guide id, then its name, tidied up. */
object GuideMatch {
    private val qualityWords = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "50fps", "backup", "live", "tv")
    private val regionWords = setOf(
        "sydney", "melbourne", "brisbane", "adelaide", "perth", "hobart", "darwin", "canberra", "gold", "coast",
        "nsw", "vic", "qld", "sa", "wa", "tas", "nt", "act", "regional", "metro", "au", "aus", "australia",
    )
    private val aliases = mapOf(
        "seven" to "7", "channel 7" to "7", "7 network" to "7", "seven network" to "7",
        "nine" to "9", "channel 9" to "9", "nine network" to "9",
        "ten" to "10", "channel 10" to "10", "network 10" to "10", "10 peach" to "10 peach",
        "abc1" to "abc", "abc kids abc tv plus" to "abc kids", "sbs one" to "sbs",
        "aljazeera" to "al jazeera", "al jazeera arabic" to "al jazeera", "aljazeera arabic" to "al jazeera",
        "mbc 1" to "mbc1", "mbc 2" to "mbc2", "mbc 3" to "mbc3", "mbc 4" to "mbc4", "mbc 5" to "mbc5",
    )
    private val prefix = Regex("""^\s*[a-z]{2,3}\s*[|:\-]\s*""")
    private val brackets = Regex("""[\[(][^\])]*[\])]""")

    /** "AU: Seven HD (Sydney)" -> "7"; "ABC TV Sydney" -> "abc". */
    fun normalise(name: String): String {
        var s = name.lowercase().replace(brackets, " ").replace(prefix, "")
        s = s.replace(Regex("""[^\p{L}\p{N}+]+"""), " ").trim()
        val words = s.split(' ').filter { it.isNotEmpty() && it !in qualityWords && it !in regionWords }
        val joined = words.joinToString(" ")
        return aliases[joined] ?: joined
    }

    /** Index of a guide source's channels by guide id (any case) and by tidied name. */
    class Index(channels: List<GuideChannel>) {
        val byId: Map<String, String> = channels.associate { it.id.lowercase() to it.key }
        val byName: Map<String, String> = buildMap {
            for (c in channels) for (n in c.names) { val k = normalise(n); if (k.isNotEmpty()) putIfAbsent(k, c.key) }
        }
    }

    fun match(channel: Channel, index: Index): String? =
        channel.epgId?.lowercase()?.let(index.byId::get) ?: normalise(channel.name).takeIf { it.isNotEmpty() }?.let(index.byName::get)
}

/** Xtream's per-channel guide. Titles and descriptions usually come base64-encoded. */
object XtreamGuide {
    fun url(server: String, username: String, password: String, streamId: String, limit: Int = 4) =
        XtreamApi.accountUrl(server, username, password) + "&action=get_short_epg&stream_id=${java.net.URLEncoder.encode(streamId, "UTF-8")}&limit=$limit"

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The provider's stream id for a channel. Channels loaded through the provider's playlist (when its
     * API failed) carry a made-up id, but their address ends in the stream id: .../live/user/pass/1234.ts
     */
    fun streamId(sourceId: String?, url: String): String? {
        if (sourceId != null && sourceId.isNotEmpty() && sourceId.all(Char::isDigit)) return sourceId
        val path = url.substringBefore('?').substringBefore('#')
        return Regex("""/(\d+)(?:\.[A-Za-z0-9]{1,5})?$""").find(path)?.groupValues?.get(1)
    }

    fun parse(body: String): List<Programme> {
        val root = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return emptyList()
        val list = root["epg_listings"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            val start = str("start_timestamp")?.toDoubleOrNull()?.toLong()?.times(1000) ?: return@mapNotNull null
            val stop = (str("stop_timestamp") ?: str("end_timestamp"))?.toDoubleOrNull()?.toLong()?.times(1000)
                ?: str("end")?.toDoubleOrNull()?.toLong()?.times(1000) ?: (start + 30 * 60_000L)
            val title = str("title")?.let(::maybeBase64)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Programme(start, stop, title, str("description")?.let(::maybeBase64)?.trim()?.takeIf { it.isNotEmpty() })
        }.sortedBy { it.start }
    }

    /** Decodes base64 when the text is base64 of readable UTF-8; otherwise returns it as it came. */
    fun maybeBase64(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || t.length % 4 != 0 || !t.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return s
        val bytes = Base64Lite.decode(t) ?: return s
        val text = runCatching {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return s
        return if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) s else text
    }
}

/** Minimal standard base64 decoder (java.util.Base64 needs Android 8; the app supports Android 7). */
object Base64Lite {
    private val table = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
    }

    fun decode(s: String): ByteArray? {
        val clean = s.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buf = 0; var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) return null
            buf = (buf shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}

/** Free XMLTV guides offered as a fallback (volunteer-run; fetched at most twice a day). */
object GuidePresets {
    data class Preset(val id: String, val label: String, val url: String)

    val ALL = listOf(
        Preset("epgshare01-au", "Australia (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz"),
        Preset("epgpw-au", "Australia (epg.pw)", "https://epg.pw/xmltv/epg_AU.xml.gz"),
        Preset("epgshare01-ae", "United Arab Emirates (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_AE1.xml.gz"),
        Preset("epgshare01-sa", "Saudi Arabia (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_SA1.xml.gz"),
        Preset("epgshare01-aljazeera", "Al Jazeera (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_ALJAZEERA1.xml.gz"),
    )

    fun find(id: String) = ALL.firstOrNull { it.id == id }

    /** The default fallback for a TV in [region]. */
    fun defaultFor(region: String): List<String> = when (region.uppercase()) {
        "AU" -> listOf("epgshare01-au")
        "AE" -> listOf("epgshare01-ae")
        "SA" -> listOf("epgshare01-sa")
        else -> emptyList()
    }
}
