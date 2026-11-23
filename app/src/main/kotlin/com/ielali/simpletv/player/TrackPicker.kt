package com.ielali.simpletv.player

import kotlinx.serialization.Serializable

/**
 * Chooses the subtitle and soundtrack of a film or episode from the tracks its file carries, following
 * the channel's wishes (PlaylistSpec.subtitles / audio). Android-free so the rules are unit tested;
 * PlaybackEngine maps Media3 track groups in and the choice back out.
 *
 * Rules (caregiver's decision): a wished language that the file does not have means no subtitles,
 * or the soundtrack the file comes with. Never guess another language.
 */
object TrackPicker {
    /** Subtitles off; [ANY] shows the file's own choice of subtitles in whatever language. */
    const val ANY = "any"

    data class Track(val group: Int, val index: Int, val language: String?, val isDefault: Boolean = false, val isForced: Boolean = false)

    sealed interface Choice {
        /** Leave the player's own choice alone. */
        data object Keep : Choice
        /** Turn this kind of track off. */
        data object Off : Choice
        data class Pick(val group: Int, val index: Int) : Choice
    }

    /** "ara", "ar-SA", "Arabic" -> "ar"; "eng", "en-GB" -> "en"; null or "und" -> null. */
    fun normalise(tag: String?): String? {
        val t = tag?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return null
        return when (t) {
            "und", "mul", "zxx", "mis" -> null
            "ara", "arabic", "العربية" -> "ar"
            "eng", "english" -> "en"
            "fre", "fra", "french" -> "fr"
            "ger", "deu", "german" -> "de"
            "spa", "spanish" -> "es"
            "ita", "italian" -> "it"
            "tur", "turkish" -> "tr"
            "per", "fas", "persian", "farsi" -> "fa"
            "urd", "urdu" -> "ur"
            "heb", "hebrew" -> "he"
            "por", "portuguese" -> "pt"
            "rus", "russian" -> "ru"
            "hin", "hindi" -> "hi"
            "chi", "zho", "chinese" -> "zh"
            else -> t
        }
    }

    /**
     * [wish]: null or "" = off, [ANY] = any subtitles, else a language code. Full subtitles beat
     * "forced" ones (which only translate the odd foreign line); the file's default track breaks ties.
     */
    fun subtitles(tracks: List<Track>, wish: String?): Choice {
        if (wish.isNullOrBlank()) return Choice.Off
        val pool = if (wish == ANY) tracks else tracks.filter { normalise(it.language) == normalise(wish) }
        val best = pool.sortedWith(compareBy<Track>({ it.isForced }, { !it.isDefault })).firstOrNull() ?: return Choice.Off
        return Choice.Pick(best.group, best.index)
    }

    /** [wish]: null = as the film comes; else a language code. No match keeps the file's soundtrack. */
    fun audio(tracks: List<Track>, wish: String?): Choice {
        if (wish.isNullOrBlank()) return Choice.Keep
        val best = tracks.filter { normalise(it.language) == normalise(wish) }
            .sortedWith(compareBy { !it.isDefault }).firstOrNull() ?: return Choice.Keep
        return Choice.Pick(best.group, best.index)
    }

    /** Languages on offer, for telling the caregiver what a film has. */
    fun languages(tracks: List<Track>): List<String> = tracks.mapNotNull { normalise(it.language) }.distinct()

    /** What a film or episode channel asked for. */
    data class Wish(val subtitles: String?, val audio: String?)

    /** What the file offered and what the TV did, for "On the TV now" on the config page. */
    @Serializable
    data class Report(
        val subtitleLanguages: List<String> = emptyList(),
        val audioLanguages: List<String> = emptyList(),
        /** Language shown ("" when the track has none), or null when subtitles are off. */
        val subtitlesShown: String? = null,
        /** Subtitles were wished for but the file has none in that language (or none at all). */
        val subtitlesMissing: Boolean = false,
        /** A soundtrack language was wished for but the file does not have it. */
        val audioMissing: Boolean = false,
    )

    fun report(text: List<Track>, audioTracks: List<Track>, wish: Wish): Report {
        val sub = subtitles(text, wish.subtitles)
        val aud = audio(audioTracks, wish.audio)
        val shown = (sub as? Choice.Pick)?.let { p -> text.first { it.group == p.group && it.index == p.index } }
        return Report(
            subtitleLanguages = languages(text),
            audioLanguages = languages(audioTracks),
            subtitlesShown = shown?.let { normalise(it.language).orEmpty() },
            subtitlesMissing = !wish.subtitles.isNullOrBlank() && sub == Choice.Off,
            audioMissing = !wish.audio.isNullOrBlank() && aud == Choice.Keep,
        )
    }
}
