// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import java.util.EnumSet
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType

/**
 * Which files a sync is willing to take off the watch.
 *
 * Both failure directions are expensive and neither announces itself. Too
 * narrow and the user's own courses stay invisible to Tracks forever, which is
 * the bug this exists to fix. Too wide and every background sync drags the same
 * unchanging routes back over Bluetooth on every run — and worse, files that
 * are the user's own can reach the path that tells the watch it may reclaim
 * them.
 *
 * The state is static and shared, because the filter runs deep inside a
 * vendored protocol handler on the BLE callback thread. That makes resetting it
 * part of the contract rather than test hygiene: a leaked "also pull courses"
 * would change behaviour for every sync afterwards.
 */
class PullFilterTest {

    @AfterTest
    fun reset() {
        FileType.FILETYPE.setAlsoPull(EnumSet.noneOf(FileType.FILETYPE::class.java))
    }

    @Test
    fun `recordings are pulled without being asked for`() {
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.ACTIVITY))
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.MONITOR))
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.SLEEP))
    }

    @Test
    fun `the user's own files are skipped by default`() {
        // This is the whole reason a watch full of routes was invisible: Garmin
        // marks what you PUT on the device as something a companion app does
        // not fetch, and Tracks inherited that.
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.COURSES))
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.LOCATION))
    }

    @Test
    fun `asking widens the filter to exactly what was asked for`() {
        FileType.FILETYPE.setAlsoPull(
            EnumSet.of(FileType.FILETYPE.COURSES, FileType.FILETYPE.LOCATION)
        )

        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.COURSES))
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.LOCATION))
        // And nothing else comes along with them. Settings and workouts are
        // also non-pullable, and a filter that widened to "everything the user
        // put on the watch" would drag those too.
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.SETTINGS))
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.WORKOUTS))
    }

    @Test
    fun `clearing puts it back`() {
        FileType.FILETYPE.setAlsoPull(EnumSet.of(FileType.FILETYPE.COURSES))
        FileType.FILETYPE.setAlsoPull(EnumSet.noneOf(FileType.FILETYPE::class.java))

        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.COURSES))
        // Recordings are unaffected either way — the widening is additive, and
        // clearing it must not switch the ordinary sync off.
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.ACTIVITY))
    }

    @Test
    fun `the training calendar comes back on every sync`() {
        // Not behind the user-triggered widening: the run that pushes a schedule
        // is a different button from the run that reads one, so gating the read
        // meant the evidence was never collected on the run that produced it.
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.SCHEDULES))
    }

    @Test
    fun `the watch may never reclaim its schedule`() {
        // Not shouldPull: the widening says what may be *read*, and the mark-as-
        // synced path deliberately reads `pull` instead, because telling the
        // watch it may drop the calendar is how a read turns into a delete.
        assertFalse(FileType.FILETYPE.SCHEDULES.pull)
    }

    @Test
    fun `pulling the schedule does not drag its workouts back`() {
        // The one that would hurt. A schedule names a workout per day, and a
        // filter that swept those up too would pull the whole training plan back
        // over BLE on every single sync.
        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.SCHEDULES))
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.WORKOUTS))
    }

    @Test
    fun `null is not pullable`() {
        // An unrecognised directory entry decodes to a null type, and the
        // filters ask about it directly.
        assertFalse(FileType.FILETYPE.shouldPull(null))
    }

    @Test
    fun `a later ask replaces the previous one rather than accumulating`() {
        FileType.FILETYPE.setAlsoPull(EnumSet.of(FileType.FILETYPE.COURSES))
        FileType.FILETYPE.setAlsoPull(EnumSet.of(FileType.FILETYPE.LOCATION))

        assertTrue(FileType.FILETYPE.shouldPull(FileType.FILETYPE.LOCATION))
        assertFalse(FileType.FILETYPE.shouldPull(FileType.FILETYPE.COURSES))
    }
}
