// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.core.api.CourseSummary
import com.tracks.core.api.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which things count as being "on the watch".
 *
 * The question somebody is really asking when they open that view is "will I
 * have this with me", so a track that is queued but not yet transferred belongs
 * in the list — the row's own caption is where queued and carried are told
 * apart. Filtering it out would answer a different, less useful question.
 */
class LibraryScopeTest {

    private fun course(
        loadToDevice: Boolean = false,
        external: Boolean = false,
        status: String? = null,
    ) = CourseSummary(
        id = 1, name = "Ridge", loadToDevice = loadToDevice,
        isExternal = external, deviceStatus = status,
    )

    private fun waypoint(loadToDevice: Boolean = false, onWatch: Boolean = false) =
        Waypoint(id = 1, name = "Spring", loadToDevice = loadToDevice, onWatch = onWatch)

    @Test
    fun `everything includes what is not going to the watch`() {
        assertTrue(Scope.Everything.includes(course()))
        assertTrue(Scope.Everything.includes(waypoint()))
    }

    @Test
    fun `the watch view leaves out what was never asked for`() {
        assertFalse(Scope.OnTheWatch.includes(course()))
        assertFalse(Scope.OnTheWatch.includes(waypoint()))
    }

    @Test
    fun `something merely queued still counts as coming with you`() {
        assertTrue(Scope.OnTheWatch.includes(course(loadToDevice = true)))
        assertTrue(Scope.OnTheWatch.includes(waypoint(loadToDevice = true)))
    }

    @Test
    fun `a course found on the watch counts even though we never sent it`() {
        assertTrue(Scope.OnTheWatch.includes(course(external = true)))
    }

    @Test
    fun `a place on its way off still shows, so the removal is visible`() {
        // Unflagged but still on the device. Hiding it the instant the flag
        // clears would make it look like the removal had already happened.
        assertTrue(Scope.OnTheWatch.includes(waypoint(loadToDevice = false, onWatch = true)))
    }

    @Test
    fun `the summary counts what the current view shows`() {
        val summary = Scope.OnTheWatch.summary(
            listOf(course(loadToDevice = true), course()),
            listOf(waypoint(onWatch = true), waypoint(loadToDevice = true), waypoint()),
        )
        assertEquals("1 track, 2 waypoints on the watch", summary)
    }

    @Test
    fun `the everything view counts everything and says nothing about the watch`() {
        val summary = Scope.Everything.summary(
            listOf(course(loadToDevice = true), course()),
            listOf(waypoint()),
        )
        assertEquals("2 tracks, 1 waypoint", summary)
    }

    @Test
    fun `one of something is not one of somethings`() {
        val summary = Scope.OnTheWatch.summary(listOf(course(loadToDevice = true)), emptyList())
        assertTrue(summary.startsWith("1 track, 0 waypoints"))
    }
}
