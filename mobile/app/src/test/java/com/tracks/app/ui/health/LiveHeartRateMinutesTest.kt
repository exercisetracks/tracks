// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import com.tracks.core.api.StressDay
import com.tracks.device.garmin.live.LiveHeartRate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * The heart-rate history behind the dial is the watch's monitoring files,
 * which reach the phone at the next sync; the minutes this page watched live
 * are laid over today so the curve reaches now rather than the last sync.
 */
class LiveHeartRateMinutesTest {

    @Test
    fun live_readings_land_on_todays_minutes_and_win_them() {
        val now = LocalDateTime.now().withHour(10).withMinute(30)
        val today = now.toLocalDate().toString()
        val synced = listOf(StressDay(today, listOf(listOf(600, 61), listOf(629, 70))))
        val elapsed = 1_000_000L
        val live = listOf(
            LiveHeartRate.Reading(88, elapsed - 60_000),   // 10:29
            LiveHeartRate.Reading(91, elapsed),            // 10:30
        )
        val day = withLiveMinutes(synced, live, nowElapsed = elapsed, nowLocal = now).single { it.date == today }
        assertEquals(listOf(listOf(600, 61), listOf(629, 88), listOf(630, 91)), day.points)
    }

    @Test
    fun nothing_live_changes_nothing() {
        val days = listOf(StressDay("2026-01-01", listOf(listOf(1, 60))))
        assertEquals(days, withLiveMinutes(days, emptyList(), nowElapsed = 0L))
    }

    /** Off the wrist the watch streams zeros: the dial must go to "--", not show 0 bpm. */
    @Test
    fun a_zero_from_the_stream_ends_the_live_reading() {
        LiveHeartRate.now = { 5_000L }
        LiveHeartRate.onReading(72)
        assertEquals(72, LiveHeartRate.current()?.bpm)
        LiveHeartRate.onReading(0)
        assertEquals(null, LiveHeartRate.current())
        LiveHeartRate.clear()
    }
}
