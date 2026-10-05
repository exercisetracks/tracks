// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.WorkoutStep
import com.tracks.core.fit.daysFromCivil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulePushPlanTest {

    private fun workout(
        id: Int,
        date: String,
        type: String = "run",
        sport: String = "running",
        title: String = "Session",
        steps: List<WorkoutStep> = listOf(WorkoutStep(type = "run", durationMin = 30.0)),
        complete: Boolean = false,
    ) = PlannedWorkout(
        id = id, scheduledDate = date, sport = sport, workoutType = type,
        title = title, steps = steps, isComplete = complete,
    )

    private val context = CoachingContext(
        paceCoaching = true, vdot = 50.0, lthr = 165, ftp = 250,
        planName = "Autumn Block", planEnd = "2026-09-30",
    )

    /** Stable per workout, which is the contract the builder relies on. */
    private val fixedTimes: (Int) -> Long = { id -> 1_756_000_000_000L + id * 1000L }

    @Test
    fun `the window runs from today to four days out, inclusive at both ends`() {
        val all = listOf(
            workout(1, "2026-08-28"),   // yesterday
            workout(2, "2026-08-29"),   // today
            workout(3, "2026-09-02"),   // the last day in range
            workout(4, "2026-09-03"),   // one day past
        )
        val picked = workoutsForSchedule(all, today = "2026-08-29")
        assertContentEquals(listOf(2, 3), picked.map { it.id })
    }

    @Test
    fun `rest days are left out entirely`() {
        val all = listOf(
            workout(1, "2026-08-30", type = "rest"),
            workout(2, "2026-08-31"),
        )
        assertContentEquals(listOf(2), workoutsForSchedule(all, "2026-08-29").map { it.id })
    }

    @Test
    fun `a workout already done is not sent to the watch again`() {
        // The watch cannot delete over Bluetooth, so a file sent for a finished
        // session is a file that stays and counts against its workout limit.
        val all = listOf(
            workout(1, "2026-08-29", complete = true),
            workout(2, "2026-08-30"),
        )
        assertContentEquals(listOf(2), workoutsForSchedule(all, "2026-08-29").map { it.id })
    }

    @Test
    fun `workouts come back in date order whatever order they arrived in`() {
        val all = listOf(workout(1, "2026-09-02"), workout(2, "2026-08-30"), workout(3, "2026-09-01"))
        assertContentEquals(
            listOf("2026-08-30", "2026-09-01", "2026-09-02"),
            workoutsForSchedule(all, "2026-08-29").map { it.scheduledDate },
        )
    }

    @Test
    fun `nothing scheduled produces no bundle rather than an empty calendar`() {
        assertNull(
            buildScheduleBundle(
                workouts = listOf(workout(1, "2026-10-20")),
                today = "2026-08-29", context = context,
                fitTimeCreated = fixedTimes, nowMillis = 1_788_000_000_000L,
            ),
        )
    }

    @Test
    fun `a bundle carries the schedule and one file per workout it names`() {
        val bundle = buildScheduleBundle(
            workouts = listOf(
                workout(11, "2026-08-30"),
                workout(12, "2026-09-01", type = "strength", sport = "training"),
                workout(13, "2026-09-20"),   // outside the window
            ),
            today = "2026-08-29", context = context,
            fitTimeCreated = fixedTimes, nowMillis = 1_788_000_000_000L,
        )
        assertNotNull(bundle)
        assertEquals(2, bundle.count)
        assertEquals(2, bundle.workouts.size)
        assertEquals("SCHEDULE.fit", bundle.schedule.filename)
        assertContentEquals(
            listOf("WKT_20260830_11.fit", "WKT_20260901_12.fit"),
            bundle.workouts.map { it.filename },
        )
        // Everything in the batch goes to the same folder, which is the whole
        // reason the calendar builds at all — see the class doc on batching.
        assertTrue(bundle.workouts.all { it.folder == NEW_FILES_FOLDER })
        assertEquals(NEW_FILES_FOLDER, bundle.schedule.folder)
        assertTrue(bundle.workouts.all { it.bytes.isNotEmpty() })
    }

    @Test
    fun `the same plan builds the same bytes twice`() {
        // The fingerprint that stops a sync rebooting the watch is taken over
        // these bytes, so an unstable encoder would reboot it on every run.
        val plan = listOf(workout(21, "2026-08-30"), workout(22, "2026-09-02"))
        val first = buildScheduleBundle(
            plan, "2026-08-29", context, fixedTimes, 1_788_000_000_000L,
        )
        val second = buildScheduleBundle(
            plan, "2026-08-29", context, fixedTimes, 1_788_000_000_000L,
        )
        assertNotNull(first)
        assertNotNull(second)
        assertContentEquals(first.schedule.bytes, second.schedule.bytes)
        first.workouts.zip(second.workouts).forEach { (a, b) ->
            assertContentEquals(a.bytes, b.bytes)
        }
    }

    @Test
    fun `a changed workout changes the bytes`() {
        val before = buildScheduleBundle(
            listOf(workout(31, "2026-08-30", title = "Easy")),
            "2026-08-29", context, fixedTimes, 1_788_000_000_000L,
        )
        val after = buildScheduleBundle(
            listOf(workout(31, "2026-08-30", title = "Tempo")),
            "2026-08-29", context, fixedTimes, 1_788_000_000_000L,
        )
        assertNotNull(before)
        assertNotNull(after)
        assertTrue(
            !before.workouts.single().bytes.contentEquals(after.workouts.single().bytes),
            "renaming a workout must change the file the watch is sent",
        )
    }

    @Test
    fun `coaching off drops the targets a coached plan would carry`() {
        val plan = listOf(workout(41, "2026-08-30", steps = listOf(
            WorkoutStep(type = "run", durationMin = 30.0, pace = "threshold"),
        )))
        val coached = buildScheduleBundle(
            plan, "2026-08-29", context, fixedTimes, 1_788_000_000_000L,
        )
        val plain = buildScheduleBundle(
            plan, "2026-08-29", context.copy(paceCoaching = false),
            fixedTimes, 1_788_000_000_000L,
        )
        assertNotNull(coached)
        assertNotNull(plain)
        assertTrue(
            !coached.workouts.single().bytes.contentEquals(plain.workouts.single().bytes),
            "a coached step carries a speed target and an uncoached one does not",
        )
    }

    @Test
    fun `civil dates round-trip through their day numbers`() {
        for (iso in listOf(
            "1970-01-01", "1999-12-31", "2000-02-29", "2000-03-01",
            "2024-02-29", "2026-08-29", "2100-03-01",
        )) {
            assertEquals(iso, civilFromDays(daysFromCivil(iso)), iso)
        }
    }

    @Test
    fun `a week out crosses a month end correctly`() {
        assertEquals("2026-09-05", civilFromDays(daysFromCivil("2026-08-29") + 7))
        assertEquals("2027-01-04", civilFromDays(daysFromCivil("2026-12-28") + 7))
    }

    @Test
    fun `workouts written offline get distinct serials, not all serial one`() {
        // Both encoders clamp a serial with maxOf(1, id), so a negative local
        // id would arrive as 1 — every offline-authored workout colliding with
        // every other and with the real workout 1, and the calendar binding
        // several entries to one file.
        assertEquals(1_000_000_001, watchSerial(-1))
        assertEquals(1_000_000_007, watchSerial(-7))
        assertTrue(watchSerial(-1) != watchSerial(-2))
        // Server ids pass through untouched, and stay well below the range.
        assertEquals(412, watchSerial(412))
        assertTrue(watchSerial(412) < watchSerial(-1))
    }

    @Test
    fun `an offline workout is named and numbered without a minus sign`() {
        val bundle = buildScheduleBundle(
            workouts = listOf(workout(-3, "2026-08-30"), workout(-4, "2026-08-31")),
            today = "2026-08-29", context = context,
            fitTimeCreated = fixedTimes, nowMillis = 1_788_000_000_000L,
        )
        assertNotNull(bundle)
        assertContentEquals(
            listOf("WKT_20260830_1000000003.fit", "WKT_20260831_1000000004.fit"),
            bundle.workouts.map { it.filename },
        )
        // Two different files, not one file twice.
        assertTrue(!bundle.workouts[0].bytes.contentEquals(bundle.workouts[1].bytes))
    }

    @Test
    fun `a workout the server has not seen is not reported as uploaded`() {
        val bundle = buildScheduleBundle(
            workouts = listOf(workout(-3, "2026-08-30"), workout(9, "2026-08-31")),
            today = "2026-08-29", context = context,
            fitTimeCreated = fixedTimes, nowMillis = 1_788_000_000_000L,
        )
        assertNotNull(bundle)
        // Marking id -3 uploaded would send the server hunting for a row that
        // does not exist.
        assertEquals(listOf(null, 9), bundle.workouts.map { it.id })
    }
}
