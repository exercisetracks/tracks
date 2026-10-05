// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import com.tracks.core.plan.Matching
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * How a calendar day reads anywhere a person sees one.
 *
 * The data carries ISO dates (`2026-09-25`) because that is what sorts and
 * syncs; a person reads "Today", "Tomorrow", "Thu 25 Sep". Screens that printed
 * the ISO string directly looked like a debug view next to the web app, which
 * never does. Unparseable input comes back unchanged rather than blank.
 */
fun dayLabel(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) WEEKDAY_DAY_MONTH else DAY_MONTH_YEAR)
    }
}

/** A day with no relative wording — for axes and ranges: "25 Sep", with the year only when it is not this one. */
fun shortDay(iso: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    return date.format(if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR)
}

/**
 * When an activity started, on this phone's clock.
 *
 * `started_at` is a UTC instant, held as `2026-10-01 01:04:12+00:00` when the
 * phone imported it and with a `T` when it came from the server. Taking its
 * first ten characters is the UTC day, which put an 18:04 run in California
 * on the next day (2026-09-30); `Instant.parse` rejects the space, which left
 * the detail header blank for every activity imported on the phone. Both
 * shapes go through [Matching.epochSeconds], the parser matching itself uses.
 */
fun startedLocal(startedAt: String, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? =
    Matching.epochSeconds(startedAt)?.let { Instant.ofEpochSecond(it).atZone(zone) }

/** The local day an activity started on, as `YYYY-MM-DD`, or null if unreadable. */
fun startedLocalDay(startedAt: String): String? = startedLocal(startedAt)?.toLocalDate()?.toString()

private val WEEKDAY_DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
