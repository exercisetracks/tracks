// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.CalendarContract
import com.tracks.device.CalendarEvent

/**
 * Reads upcoming calendar entries for the watch's agenda.
 *
 * Uses [CalendarContract.Instances], not Events, which matters for anything
 * recurring: Events holds the *rule* ("every Tuesday"), while Instances holds
 * the expanded occurrences. Querying Events would show a weekly stand-up once,
 * at the date the series began, and never again.
 *
 * READ_CALENDAR is a runtime permission and the feature is optional — no
 * permission means no agenda, and nothing else changes.
 */
object CalendarReader {

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_COLOR,
    )

    /**
     * Events between [fromMillis] and [fromMillis] + [windowMillis].
     *
     * A bounded window rather than everything: a watch shows the next day or
     * two, and a decade of history is a large query answering a question nobody
     * asked.
     *
     * @param limit caps the result so an unusually dense calendar cannot
     *   produce a payload too large to send.
     */
    fun upcoming(
        resolver: ContentResolver,
        fromMillis: Long,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
        limit: Int = DEFAULT_LIMIT,
    ): List<CalendarEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { builder ->
            ContentUris.appendId(builder, fromMillis)
            ContentUris.appendId(builder, fromMillis + windowMillis)
            builder.build()
        }

        return try {
            resolver.query(
                uri, PROJECTION, null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor -> readAll(cursor, limit) } ?: emptyList()
        } catch (e: SecurityException) {
            // Permission not granted, or revoked since. Not an error — the
            // agenda is optional and the rest of the app is unaffected.
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun readAll(cursor: Cursor, limit: Int): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
        val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
        val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
        val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
        val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
        val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
        val colorIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_COLOR)

        while (cursor.moveToNext() && out.size < limit) {
            val title = titleIdx.takeIf { it >= 0 }?.let { cursor.getString(it) }
            // An untitled event is almost always a placeholder or a busy block
            // from a shared calendar; there is nothing to show.
            if (title.isNullOrBlank()) continue

            out += CalendarEvent(
                id = if (idIdx >= 0) cursor.getLong(idIdx) else continue,
                title = title,
                location = locIdx.takeIf { it >= 0 }
                    ?.let { cursor.getString(it) }?.takeIf { it.isNotBlank() },
                startMillis = if (beginIdx >= 0) cursor.getLong(beginIdx) else continue,
                endMillis = if (endIdx >= 0) cursor.getLong(endIdx) else continue,
                allDay = allDayIdx >= 0 && cursor.getInt(allDayIdx) != 0,
                color = colorIdx.takeIf { it >= 0 }
                    ?.let { cursor.getInt(it) }?.takeIf { it != 0 },
            )
        }
        return out
    }

    /** Two days: enough for "what's next", short enough to stay small. */
    const val DEFAULT_WINDOW_MILLIS: Long = 2 * 24 * 60 * 60 * 1000L
    const val DEFAULT_LIMIT: Int = 32
}
