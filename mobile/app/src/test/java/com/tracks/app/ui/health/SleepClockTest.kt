// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * When a night happened, which is half of what a fortnight of sleep says.
 *
 * The history chart used to measure nights from zero, so seven hours starting
 * at eleven and seven hours starting at three were the same bar. Placing them
 * on a clock is the fix, and the arithmetic behind it has exactly one hard
 * part: a bedtime is *before* the day the night is filed under, and any
 * attempt to wrap it into a 0–24 range puts 23:00 and 01:00 at opposite ends
 * of the chart — which is the shape the whole view exists to make legible.
 */
class SleepClockTest {

    /** A zone with daylight saving, as the files these rules were checked against had. */
    private val zone = ZoneId.of("America/Edmonton")

    private fun night(start: String?, end: String?, date: String = "2026-08-29") =
        SleepNightSummary(
            date = date,
            total = 7.0,
            deep = 1.5,
            rem = 1.5,
            light = 4.0,
            awake = 0.5,
            score = 78.0,
            startAt = start,
            endAt = end,
        )

    @Test
    fun `going to bed before midnight is a negative hour`() {
        // 22:30 local on the 28th, which is 04:30Z on the 29th.
        val clock = nightClock(
            night("2026-08-29T04:30:00+00:00", "2026-08-29T12:45:00+00:00"),
            zone,
        )!!
        assertEquals(-1.5, clock.from, 0.001)
        assertEquals(6.75, clock.to, 0.001)
    }

    @Test
    fun `a night is read in the zone the reader is standing in`() {
        // The same instants, an hour further east. The stamps carry UTC and no
        // memory of where they were recorded; "eleven at night" is a fact
        // about where somebody was.
        val utc = nightClock(
            night("2026-08-29T04:30:00+00:00", "2026-08-29T12:45:00+00:00"),
            ZoneId.of("UTC"),
        )!!
        assertEquals(4.5, utc.from, 0.001)
        assertEquals(12.75, utc.to, 0.001)
    }

    @Test
    fun `a night with no timeline cannot be placed on a clock`() {
        // Every night imported before the server kept stage timelines. Its
        // totals are real and the chart says how many it could not place
        // rather than dropping them in silence.
        assertNull(nightClock(night(null, null), zone))
        assertNull(nightClock(night("2026-08-29T04:30:00+00:00", null), zone))
    }

    @Test
    fun `a backwards or empty span is not a night`() {
        assertNull(nightClock(night("2026-08-29T12:45:00+00:00", "2026-08-29T04:30:00+00:00"), zone))
        assertNull(nightClock(night("2026-08-29T04:30:00+00:00", "2026-08-29T04:30:00+00:00"), zone))
    }

    @Test
    fun `an unreadable date or stamp costs the night rather than throwing`() {
        assertNull(nightClock(night("not a time", "2026-08-29T12:45:00+00:00"), zone))
        assertNull(
            nightClock(
                night("2026-08-29T04:30:00+00:00", "2026-08-29T12:45:00+00:00", date = "nonsense"),
                zone,
            ),
        )
    }

    @Test
    fun `a naive stamp is read as the phone's own time`() {
        // What the phone's own parser used to write, and what a night stored
        // before that was fixed still holds. Read in the local zone it is at
        // least self-consistent, which is the best available answer.
        val clock = nightClock(night("2026-08-28T22:30:00", "2026-08-29T06:45:00"), zone)!!
        assertEquals(-1.5, clock.from, 0.001)
        assertEquals(6.75, clock.to, 0.001)
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    fun `hours before midnight are labelled as the evening they were`() {
        // Twelve-hour, because that is what the wearer's own watch face says,
        // and `:00` dropped on the hour because the axis is mostly whole
        // hours and those four characters come out of the plot's width.
        assertEquals("10:30 PM", clockLabel(-1.5))
        assertEquals("12 AM", clockLabel(0.0))
        assertEquals("6:45 AM", clockLabel(6.75))
        // And past the following midnight, for a night that ran very long.
        assertEquals("1 AM", clockLabel(25.0))
    }

    // ── The habit lines ──────────────────────────────────────────────────────

    @Test
    fun `an average bedtime either side of midnight is still a bedtime`() {
        // The trap a wrapped 0-24 clock falls into: 23:30 and 00:30 are an
        // hour apart and average to midnight, not to noon. These hours are
        // signed against each night's own midnight, so the plain mean is
        // right — see [NightClock].
        val bedtimes = listOf(-0.5, 0.5)
        assertEquals(0.0, bedtimes.average(), 0.001)
        assertEquals("12 AM", clockLabel(bedtimes.average()))
    }

    // ── The axis ─────────────────────────────────────────────────────────────

    @Test
    fun `ticks land on hours a clock divides into`() {
        // Nights running 23:10 to 11:50 — a real fortnight off this watch.
        // [chartScale] gave this a five-hour step and an axis running 19:00 to
        // 15:00: twenty hours of height for thirteen hours of data, labelled
        // at hours with no relationship to anything.
        val scale = clockScale(-0.83, 11.83)

        // Read top to bottom, which on a negated axis is the ticks reversed:
        // the scale's maximum is the earliest hour.
        assertEquals(
            listOf("9 PM", "12 AM", "3 AM", "6 AM", "9 AM", "12 PM"),
            scale.ticks.map { clockLabel(-it) }.reversed(),
        )
    }

    @Test
    fun `a short window gets a finer step`() {
        // Six hours of nights, hour by hour, rather than three ticks.
        val scale = clockScale(0.5, 6.0)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0), scale.ticks.map { -it }.sorted())
    }

    @Test
    fun `a long window is still counted in clock divisions`() {
        // Somebody whose nights wander across half a day. Never 2.5 hours,
        // which is what the general-purpose scale reached for and what put
        // every other label at half past.
        val steps = clockScale(-6.0, 14.0).ticks.sorted().zipWithNext { a, b -> b - a }
        steps.forEach { assertEquals(4.0, it, 0.001) }
    }

    @Test
    fun `the earliest night is at the top of the plot`() {
        // The axis is negated so that a night reads downwards from lights out
        // to waking. The maximum of the scale is therefore the *earliest*
        // hour, and [ChartScale.fraction] puts a maximum at the top.
        val scale = clockScale(-0.83, 11.83)
        assertEquals(1f, scale.fraction(-(-3.0)), 0.001f)
        assertEquals(0f, scale.fraction(-12.0), 0.001f)
    }

    @Test
    fun `an axis always has an extent`() {
        // One night, or a window whose nights all began and ended at the same
        // minute. A scale with no span divides by zero.
        val scale = clockScale(1.0, 1.0)
        assertEquals(true, scale.max > scale.min)
    }
}
