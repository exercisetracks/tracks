// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import com.tracks.core.sync.WatchPushFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The guard that decides whether a sync reboots the watch.
 *
 * A training calendar reaches the watch through `GARMIN/NewFiles`, and
 * importing that batch restarts the device. The schedule is a statement of the
 * whole calendar rather than a queued item, so it is rebuilt every run and would
 * be resent every run — meaning every sync would reboot the watch and take the
 * link away before anything else could happen. This digest is what makes the
 * steady state quiet, so the two ways of getting it wrong both matter: too eager
 * and the watch restarts for nothing, too lazy and a plan change never arrives.
 *
 * Now that the phone builds the files itself, the digest covers the bytes it
 * built rather than the base64 a server sent — which is the same guarantee, one
 * encoding earlier.
 */
class ScheduleDigestTest {

    private fun workout(name: String, data: String) = WatchPushFile(
        type = "workout", id = 1, filename = name,
        folder = "GARMIN/NewFiles", bytes = data.encodeToByteArray(),
    )

    private fun schedule(body: String) = body.encodeToByteArray()

    @Test
    fun `the same calendar digests the same`() {
        val a = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA"), workout("b.fit", "BBB")))
        val b = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA"), workout("b.fit", "BBB")))
        assertEquals(a, b)
    }

    @Test
    fun `a changed schedule changes the digest`() {
        val before = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA")))
        val after = scheduleDigest(schedule("SCHED2"), listOf(workout("a.fit", "AAA")))
        assertNotEquals(before, after)
    }

    @Test
    fun `a workout edited in place changes the digest`() {
        // The case the schedule alone cannot see. Move nothing, change the
        // intervals: the schedule names the same file_ids and is byte-identical,
        // so digesting it on its own would leave last week's session on the watch
        // under this week's name.
        val before = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA")))
        val after = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "ZZZ")))
        assertNotEquals(before, after)
    }

    @Test
    fun `adding a workout changes the digest`() {
        val before = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA")))
        val after = scheduleDigest(
            schedule("SCHED"),
            listOf(workout("a.fit", "AAA"), workout("b.fit", "BBB")),
        )
        assertNotEquals(before, after)
    }

    @Test
    fun `the ordering of the workouts is not a change`() {
        // Otherwise a reordered plan would reboot the watch for nothing.
        val a = scheduleDigest(schedule("SCHED"), listOf(workout("a.fit", "AAA"), workout("b.fit", "BBB")))
        val b = scheduleDigest(schedule("SCHED"), listOf(workout("b.fit", "BBB"), workout("a.fit", "AAA")))
        assertEquals(a, b)
    }

    @Test
    fun `two workouts cannot swap contents unnoticed`() {
        // Names are digested alongside the bytes, so a calendar that moved
        // Tuesday's session to Thursday and back is not mistaken for no change.
        val before = scheduleDigest(
            schedule("SCHED"),
            listOf(workout("a.fit", "AAA"), workout("b.fit", "BBB")),
        )
        val after = scheduleDigest(
            schedule("SCHED"),
            listOf(workout("a.fit", "BBB"), workout("b.fit", "AAA")),
        )
        assertNotEquals(before, after)
    }
}
