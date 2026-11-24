package com.ielali.simpletv.data

/**
 * Production years for film channels' "from year … to year" rule and the page's film and series
 * search. A title's year comes from the provider's catalog, then its details, then its name
 * ("Heat (1995)", "Heat - 1995"); a title whose year is unknown plays unless the channel leaves such
 * titles out.
 */
object Years {
    const val MIN = 1900
    const val MAX = 2100

    private val any = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)")
    private val bracketed = Regex("[(\\[]\\s*(19\\d{2}|20\\d{2})\\s*[)\\]]")
    private val trailing = Regex("(?:^|[\\s\\-–|:])(19\\d{2}|20\\d{2})\\s*$")

    /** The first plausible year in a field such as "2019" or "2019-05-04". */
    fun parse(s: String?): Int? = s?.let { any.find(it)?.groupValues?.get(1)?.toInt() }?.takeIf { it in MIN..MAX }

    /** A year written in a title's name, in brackets or at the end. Not "2012" in "Film 2012 Part 2". */
    fun fromName(name: String): Int? =
        (bracketed.find(name)?.groupValues?.get(1) ?: trailing.find(name)?.groupValues?.get(1))?.toInt()?.takeIf { it in MIN..MAX }

    fun of(item: CatalogItem, meta: FilmMeta? = null): Int? = parse(item.year) ?: parse(meta?.year) ?: fromName(item.name)

    /** Does a title of [year] fit between [min] and [max] (either may be open)? */
    fun inRange(year: Int?, min: Int?, max: Int?, skipUnknown: Boolean = false): Boolean {
        if (min == null && max == null) return true
        if (year == null) return !skipUnknown
        return (min == null || year >= min) && (max == null || year <= max)
    }

    fun hasRule(spec: PlaylistSpec) = spec.minYear != null || spec.maxYear != null

    /** Years kept within reason and in order; "leave out unknown years" only means something with a range. */
    fun sanitize(spec: PlaylistSpec): PlaylistSpec {
        var lo = spec.minYear?.takeIf { it in MIN..MAX }
        var hi = spec.maxYear?.takeIf { it in MIN..MAX }
        if (lo != null && hi != null && lo > hi) { val t = lo; lo = hi; hi = t }
        return spec.copy(minYear = lo, maxYear = hi, skipNoYear = spec.skipNoYear && (lo != null || hi != null))
    }
}
