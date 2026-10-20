// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.TracksSchema
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Days are read a page at a time. Every day's intraday series parsed at once
 * ran the phone out of memory on every launch after years of history were
 * imported; reading in pages must still hand over each day exactly once.
 */
class DayPagingTest {

    private val lib = LocalLibrary(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TracksSchema.create(it) })
    private val first = LocalDate.now().minusDays(199)
    private val dates = (0L until 200L).map { first.plusDays(it).toString() }

    init {
        dates.forEach { lib.q.upsertDay(it, """{"steps":1}""", """{"hr_series":[[0,60]]}""") }
    }

    @Test
    fun every_day_is_read_once_across_pages_oldest_first() {
        val seen = mutableListOf<String>()
        lib.forEachDay(null) { seen += it.date }
        assertEquals(dates, seen)
    }

    @Test
    fun a_start_date_reads_only_the_days_from_it_on() {
        val since = dates[150]
        val seen = mutableListOf<String>()
        lib.forEachDay(since) { seen += it.date }
        assertEquals(dates.drop(150), seen)
    }

    @Test
    fun the_daily_numbers_come_without_the_series() {
        assertEquals(200, lib.dayMetrics().size)
    }
}
