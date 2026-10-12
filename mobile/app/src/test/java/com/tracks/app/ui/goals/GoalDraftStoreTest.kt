// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class GoalDraftStoreTest {

    private val store = GoalDraftStore(ApplicationProvider.getApplicationContext())

    private val filled = GoalDraft(
        goalType = "event",
        eventName = "Harbour 10K",
        eventSport = "running",
        eventDate = "2027-04-18",
        eventDistanceMeters = 10_000.0,
        daysPerWeek = 4,
        planIntensity = 1.2,
        includeStrength = true,
        strengthTier = 2,
        notes = "Hilly second half",
        presetId = "10k",
    )

    /**
     * The form used to come back blank whenever it was closed — to look up
     * the race's date, say — so every field had to be entered again.
     */
    @Test
    fun a_new_goal_s_form_comes_back_as_it_was_left() {
        store.save(filled)
        assertEquals(filled, store.load())
    }

    @Test
    fun every_goal_type_s_fields_survive() {
        val fitness = GoalDraft(goalType = "fitness", fitnessSports = listOf("cycling", "running"), ctlRampPerWeek = 4.0)
        val volume = GoalDraft(goalType = "volume_target", targetWeeklyKm = 42.5, volumeSport = "cycling")
        listOf(fitness, volume).forEach {
            store.save(it)
            assertEquals(it, store.load())
        }
    }

    /** An edit to a saved goal reopens on the saved goal, not on a stale copy. */
    @Test
    fun a_goal_being_edited_is_not_kept() {
        store.clear()
        store.save(filled.copy(id = 7))
        assertNull(store.load())
    }

    /** The date fills itself in on open; that alone is not something to keep. */
    @Test
    fun an_untouched_form_is_not_kept() {
        store.save(filled)
        store.save(GoalDraft(eventDate = "2027-01-10", eventDateAuto = true))
        assertNull(store.load())
    }

    @Test
    fun starting_over_forgets_it() {
        store.save(filled)
        store.clear()
        assertNull(store.load())
    }

    /** A draft written by an older build, missing fields added since, still opens. */
    @Test
    fun a_draft_missing_fields_takes_the_fresh_form_s_values() {
        assertEquals(GoalDraft(eventName = "Spring race"), GoalDraftStore.decode("""{"eventName":"Spring race"}"""))
    }
}
