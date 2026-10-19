// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import com.tracks.core.api.ActivitySummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone list sorts by what the desktop's does. An activity with no value
 * for the field -- a swim without heart rate -- goes last either way rather
 * than reading as zero and topping an ascending sort.
 */
class ActivitySortTest {

    private val long = ActivitySummary(id = 1, name = "Long run", startedAt = "2026-10-01T07:00", distanceMeters = 21000.0, maxHeartRate = 172)
    private val short = ActivitySummary(id = 2, name = "easy", startedAt = "2026-10-05T07:00", distanceMeters = 5000.0, maxHeartRate = 150)
    private val swim = ActivitySummary(id = 3, name = "Swim", startedAt = "2026-10-03T07:00", distanceMeters = 1500.0)

    private fun ids(list: List<ActivitySummary>) = list.map { it.id }

    @Test
    fun distance_longest_first_then_flipped() {
        val all = listOf(short, swim, long)
        assertEquals(listOf(1, 2, 3), ids(sortActivities(all, ActivitySort.Distance, descending = true)))
        assertEquals(listOf(3, 2, 1), ids(sortActivities(all, ActivitySort.Distance, descending = false)))
    }

    @Test
    fun a_missing_value_goes_last_whichever_way() {
        val all = listOf(swim, short, long)
        assertEquals(listOf(1, 2, 3), ids(sortActivities(all, ActivitySort.MaxHr, descending = true)))
        assertEquals(listOf(2, 1, 3), ids(sortActivities(all, ActivitySort.MaxHr, descending = false)))
    }

    @Test
    fun names_read_a_to_z_ignoring_case() {
        assertEquals(listOf(2, 1, 3), ids(sortActivities(listOf(swim, long, short), ActivitySort.Name, descending = false)))
    }

    @Test
    fun the_search_matches_inside_names() {
        val vm = ActivitiesUiState(query = "RUN")
        val kept = listOf(long, short, swim).filter { (it.name ?: "").lowercase().contains(vm.query.trim().lowercase()) }
        assertEquals(listOf(1), ids(kept))
    }
}
