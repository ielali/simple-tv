package com.ielali.simpletv.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the daily catalog refresh runs. The TV may be off at the set time, so the rule is "the most
 * recent scheduled moment has not been run yet", which also catches up after the TV was switched off.
 */
object RefreshSchedule {
    const val DEFAULT_TIME = "04:00"
    private val pattern = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

    /** "21:00" style; anything else falls back to the default. */
    fun normalise(time: String?): String {
        val m = time?.trim()?.let(pattern::find) ?: return DEFAULT_TIME
        return "%02d:%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    private fun at(date: LocalDate, time: String, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(date, LocalTime.parse(normalise(time)), zone)

    /** The latest scheduled moment at or before [nowMs]. */
    fun lastDue(nowMs: Long, time: String, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = at(now.toLocalDate(), time, zone)
        return (if (!today.isAfter(now)) today else at(now.toLocalDate().minusDays(1), time, zone)).toInstant().toEpochMilli()
    }

    /** The next scheduled moment after [nowMs]. */
    fun nextDue(nowMs: Long, time: String, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = at(now.toLocalDate(), time, zone)
        return (if (today.isAfter(now)) today else at(now.toLocalDate().plusDays(1), time, zone)).toInstant().toEpochMilli()
    }

    /** True when a scheduled moment has passed since the last automatic run (or there never was one). */
    fun isDue(nowMs: Long, lastAutoRunMs: Long, time: String, zone: ZoneId): Boolean =
        lastAutoRunMs < lastDue(nowMs, time, zone)
}
