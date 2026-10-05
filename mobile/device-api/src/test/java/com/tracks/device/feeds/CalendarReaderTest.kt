// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.database.MatrixCursor
import android.provider.CalendarContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the agenda.
 *
 * Driven through a MatrixCursor rather than a real provider so the row-shaping
 * rules — which events to skip, which fields are optional — are testable
 * without a calendar account on the machine running the tests.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarReaderTest {

    private val columns = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_COLOR,
    )

    private fun cursorOf(vararg rows: Array<Any?>) =
        MatrixCursor(columns).apply { rows.forEach { addRow(it) } }

    private fun row(
        id: Long = 1L,
        title: String? = "Stand-up",
        location: String? = "Room 2",
        begin: Long = 1_786_665_600_000L,
        end: Long = 1_786_669_200_000L,
        allDay: Int = 0,
        color: Int = 0xFF0000,
    ): Array<Any?> = arrayOf(id, title, location, begin, end, allDay, color)

    @Test
    fun `reads the fields a watch can show`() {
        val events = CalendarReader.readAll(cursorOf(row()), limit = 10)
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals(1L, e.id)
        assertEquals("Stand-up", e.title)
        assertEquals("Room 2", e.location)
        assertEquals(1_786_665_600_000L, e.startMillis)
        assertTrue(!e.allDay)
        assertEquals(0xFF0000, e.color)
    }

    @Test
    fun `skips untitled events`() {
        // Busy blocks from shared calendars come through with no title. There
        // is nothing to render, and a blank row on a wrist is worse than none.
        val events = CalendarReader.readAll(
            cursorOf(row(title = null), row(id = 2, title = "  "), row(id = 3, title = "Real")),
            limit = 10,
        )
        assertEquals(listOf("Real"), events.map { it.title })
    }

    @Test
    fun `treats a blank location as absent`() {
        val events = CalendarReader.readAll(cursorOf(row(location = "")), limit = 10)
        assertNull(events.single().location)
    }

    @Test
    fun `treats colour zero as absent`() {
        // Zero is "unset" in the provider, not black.
        val events = CalendarReader.readAll(cursorOf(row(color = 0)), limit = 10)
        assertNull(events.single().color)
    }

    @Test
    fun `carries the all-day flag`() {
        val events = CalendarReader.readAll(cursorOf(row(allDay = 1)), limit = 10)
        assertTrue(events.single().allDay)
    }

    @Test
    fun `respects the limit`() {
        // An unusually dense calendar must not produce a payload too large to
        // send to a watch.
        val rows = (1..50).map { row(id = it.toLong(), title = "Event $it") }.toTypedArray()
        assertEquals(5, CalendarReader.readAll(cursorOf(*rows), limit = 5).size)
    }

    @Test
    fun `an empty calendar is empty, not an error`() {
        assertTrue(CalendarReader.readAll(cursorOf(), limit = 10).isEmpty())
    }
}
