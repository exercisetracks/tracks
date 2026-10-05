// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.TrainingGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Long-press-and-drag on the week: the chip lifts (grows about 5%), follows
 * the finger, comes back down on release, and lands on the day it was let go
 * over. The race moves the goal's date instead, and never into the past.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h2000dp-xxhdpi")
class ScheduleDragTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 24)   // a Thursday
    private val goal = TrainingGoal(
        id = 1, uid = "g1", goalType = "event", isActive = true, eventName = "5K",
        eventSport = "running", eventDate = "2026-09-26", eventDistanceMeters = 5000.0, daysPerWeek = 5,
    )
    private val workouts = listOf(
        PlannedWorkout(id = 1, scheduledDate = "2026-09-22", workoutType = "tempo", title = "Tempo", durationMinutes = 45),
        PlannedWorkout(id = 2, scheduledDate = "2026-09-26", workoutType = "race", title = "5K Race"),
    )

    private val moves = mutableListOf<Pair<Int, LocalDate>>()
    private val raceMoves = mutableListOf<LocalDate>()

    private fun show() {
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    TrainingContent(
                        PlanUiState(loading = false, workouts = workouts, goals = listOf(goal)),
                        TrainingActions(
                            onMove = { w, d -> moves += w.id to d },
                            onMoveRace = { raceMoves += it },
                        ),
                        today = today,
                    )
                }
            }
        }
    }

    /** From a chip's centre down to the row of the day numbered [day]. */
    private fun dy(chip: String, day: Int): Float {
        val from = compose.onNodeWithText(chip).fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithText("$day").fetchSemanticsNode().boundsInRoot
        return to.center.y - from.center.y
    }

    /** Long-press [chip], drag it to [day]'s row in two steps; lift the finger if [release]. */
    private fun dragTo(chip: String, day: Int, release: Boolean = true) {
        val to = dy(chip, day)
        compose.onNodeWithText(chip).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, to / 2))
            moveBy(Offset(0f, to / 2))
            if (release) up()
        }
    }


    @Test
    fun a_picked_up_workout_grows_follows_the_finger_and_settles_on_the_day_it_is_dropped_on() {
        show()
        compose.mainClock.autoAdvance = false
        dragTo("Tempo", 23, release = false)  // Wednesday
        compose.mainClock.advanceTimeBy(1_000)
        val lifted = compose.onNodeWithTag("schedule-drag").fetchSemanticsNode().config[LiftScale]
        assertTrue("lifted to $lifted", lifted in 1.04f..1.06f)

        compose.onNodeWithTag("schedule-drag").assertExists()
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("schedule-drag").assertDoesNotExist()
        assertEquals(listOf(1 to LocalDate.of(2026, 9, 23)), moves)
    }

    @Test
    fun a_workout_held_without_moving_opens_the_move_sheet_instead() {
        show()
        compose.onNodeWithText("Tempo").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Move “Tempo”").assertExists()
        assertTrue(moves.isEmpty())
    }

    @Test
    fun dropping_the_race_moves_the_goals_date() {
        show()
        dragTo("5K Race", 27)  // Sunday
        compose.waitForIdle()
        assertEquals(listOf(LocalDate.of(2026, 9, 27)), raceMoves)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun the_race_dropped_in_the_past_is_handed_on_to_be_refused_not_moved() {
        show()
        dragTo("5K Race", 22)  // Tuesday, in the past
        compose.waitForIdle()
        // The view model refuses it with a message (PlanViewModel.moveRace);
        // the chip flies home rather than landing.
        assertEquals(listOf(LocalDate.of(2026, 9, 22)), raceMoves)
        assertTrue(moves.isEmpty())
    }
}
