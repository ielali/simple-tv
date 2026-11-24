package com.ielali.simpletv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Logos from Wikidata (ADR-027), for channels the TV guide and iptv-org don't know, such as a
 * provider's "Netflix Documentary": the channel's name is looked up, then its brand (the name with
 * the last words dropped, while what's left isn't a generic word like "Movies"). Only an exact
 * name match whose description says it is a TV channel, network, broadcaster or streaming service
 * counts, and its logo (property P154, a file on Wikimedia Commons) is used. Pure, so it is tested.
 */
object WikiLogos {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val prefix = Regex("""^\s*(\|[A-Za-z]{2,3}\|\s*|[A-Za-z]{2,3}\s*[|:]\s*)""")
    private val brackets = Regex("""[\[(][^\])]*[\])]""")
    private val noise = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "1080p", "720p", "50fps", "60fps", "backup", "+1", "vip", "raw")

    /** Words that are never a brand on their own. */
    private val generic = setOf(
        "tv", "channel", "live", "movies", "movie", "films", "film", "cinema", "series", "drama", "dramas", "documentary",
        "documentaries", "docs", "kids", "children", "sport", "sports", "news", "music", "comedy", "action", "family",
        "plus", "max", "extra", "one", "premium", "classic", "classics", "arabic", "english", "world", "international",
        "24", "24/7", "hits", "box", "office", "the", "east", "west", "general", "entertainment", "anime", "cartoon",
        "قناة", "افلام", "أفلام", "مسلسلات", "اخبار", "أخبار", "رياضة", "اطفال", "أطفال", "وثائقي", "وثائقية",
    )

    /** What a description must mention for a match to count. */
    private val describes = Regex("""\b(television|tv|channel|broadcaster|broadcasting|network|streaming|on-demand|news)\b""", RegexOption.IGNORE_CASE)

    private fun clean(name: String): List<String> {
        val s = TextClean.name(name).replace(brackets, " ").replace(prefix, "")
        return s.split(Regex("""[\s_|]+""")).map { it.trim('-', ':', '.', ',') }.filter { it.isNotEmpty() && it.lowercase() !in noise }
    }

    /** The names to look up for a channel, longest first: at most three. */
    fun queries(name: String): List<String> {
        val words = clean(name)
        val out = ArrayList<String>()
        for (n in words.size downTo 1) {
            val part = words.take(n)
            if (part.all { it.lowercase() in generic }) continue
            val q = part.joinToString(" ")
            if (q.length < 3) continue
            out += q
            if (out.size == 3) break
        }
        return out
    }

    /** Looking names up in Arabic when they are written in Arabic. */
    fun language(q: String): String = if (q.any { it in '؀'..'ۿ' }) "ar" else "en"

    fun searchUrl(q: String): String =
        "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=7&uselang=en&language=" +
            language(q) + "&search=" + URLEncoder.encode(q, "UTF-8")

    fun claimsUrl(entity: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&property=P154&entity=" + URLEncoder.encode(entity, "UTF-8")

    private fun same(a: String, b: String) = GuideMatch.normalise(a) == GuideMatch.normalise(b) && GuideMatch.normalise(a).isNotEmpty()

    /** Items whose name is exactly [q] and which are described as TV, in Wikidata's order. */
    fun pick(searchBody: String, q: String, max: Int = 2): List<String> {
        val items = (runCatching { json.parseToJsonElement(searchBody) as? JsonObject }.getOrNull()?.get("search") as? JsonArray).orEmpty()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val matched = (o["match"] as? JsonObject).str("text") ?: o.str("label") ?: return@mapNotNull null
            val desc = o.str("description").orEmpty()
            id.takeIf { same(matched, q) && describes.containsMatchIn(desc) }
        }.take(max)
    }

    /** Search results for the caregiver's logo picker: TV-like items with their name. */
    fun choices(searchBody: String, max: Int = 6): List<Pair<String, String>> {
        val items = (runCatching { json.parseToJsonElement(searchBody) as? JsonObject }.getOrNull()?.get("search") as? JsonArray).orEmpty()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val desc = o.str("description").orEmpty()
            if (!describes.containsMatchIn(desc)) return@mapNotNull null
            id to listOfNotNull(o.str("label"), desc.takeIf { it.isNotEmpty() }).joinToString(" · ")
        }.take(max)
    }

    /** The logo file named in a wbgetclaims answer: a preferred one first, never a deprecated one. */
    fun logoFile(claimsBody: String): String? {
        val claims = ((runCatching { json.parseToJsonElement(claimsBody) as? JsonObject }.getOrNull()?.get("claims") as? JsonObject)?.get("P154") as? JsonArray).orEmpty()
        val ok = claims.mapNotNull { it as? JsonObject }.filter { it.str("rank") != "deprecated" }
        val best = ok.firstOrNull { it.str("rank") == "preferred" } ?: ok.firstOrNull()
        val snak = best?.get("mainsnak") as? JsonObject ?: return null
        return ((snak["datavalue"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * The picture's address on Wikimedia's servers. Drawings (SVG, which the TV can't draw) as a PNG
     * at a standard thumbnail width; other pictures as they are.
     */
    fun imageUrl(file: String, width: Int = 330): String {
        val name = file.trim().replace(' ', '_')
        val md5 = MessageDigest.getInstance("MD5").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val path = "${md5[0]}/${md5.substring(0, 2)}/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return if (name.endsWith(".svg", ignoreCase = true)) {
            "https://upload.wikimedia.org/wikipedia/commons/thumb/$path/${width}px-" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") + ".png"
        } else {
            "https://upload.wikimedia.org/wikipedia/commons/$path"
        }
    }

    private fun JsonObject?.str(k: String): String? = (this?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
