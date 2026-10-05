// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.TrainingGoal
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * After a drop, the lifted copy must go away — on a new day, back home, and
 * when the day refuses it.
 *
 * On the phone it did not: a workout dropped on another day left its lifted
 * copy on screen for good. The settle launched the shrink in a child and then
 * ran the same shrink itself; on the phone's dispatcher the child starts
 * second, and a second animation of one Animatable interrupts the first by
 * cancelling its caller's Job — here the parent, which was the coroutine
 * that clears the overlay.
 *
 * These run effects on a [StandardTestDispatcher], which queues a launched
 * coroutine as the phone's main dispatcher does. The default test dispatcher
 * is unconfined — a launch runs at once — which reversed the race and is why
 * [ScheduleDragTest] never saw it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h2000dp-xxhdpi")
class ScheduleDropSettleTest {

    @get:Rule val compose = createComposeRule(StandardTestDispatcher())

    private val today = LocalDate.of(2026, 9, 24)   // a Thursday
    private val goal = TrainingGoal(
        id = 1, uid = "g1", goalType = "event", isActive = true, eventName = "5K",
        eventSport = "running", eventDate = "2026-09-26", eventDistanceMeters = 5000.0, daysPerWeek = 5,
    )

    /** The page as the app drives it: a move really moves the workout, as the view model does. */
    private fun show() {
        var workouts by mutableStateOf(
            listOf(
                PlannedWorkout(id = 1, scheduledDate = "2026-09-22", workoutType = "tempo", title = "Tempo", durationMinutes = 45),
                PlannedWorkout(id = 2, scheduledDate = "2026-09-26", workoutType = "race", title = "5K Race"),
            ),
        )
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    TrainingContent(
                        PlanUiState(loading = false, workouts = workouts, goals = listOf(goal)),
                        TrainingActions(
                            onMove = { w, d ->
                                workouts = workouts.map { if (it.id == w.id) it.copy(scheduledDate = d.toString()) else it }
                            },
                        ),
                        today = today,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Long-press [chip], move it by [dy] in two steps, and let go. */
    private fun drag(chip: String, dy: Float) {
        compose.onNodeWithText(chip).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, dy / 2))
            moveBy(Offset(0f, dy / 2))
            up()
        }
    }

    private fun toDay(chip: String, day: Int): Float {
        val from = compose.onNodeWithText(chip).fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithText("$day").fetchSemanticsNode().boundsInRoot
        return to.center.y - from.center.y
    }

    private fun assertSettled() {
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithTag("schedule-drag").assertDoesNotExist()
    }

    @Test
    fun a_workout_dropped_on_a_new_day_leaves_no_lifted_copy_behind() {
        show()
        drag("Tempo", toDay("Tempo", 23))  // Wednesday
        assertSettled()
        compose.onNodeWithText("Tempo").assertExists()
    }

    @Test
    fun a_workout_dragged_away_and_brought_back_home_leaves_no_lifted_copy_behind() {
        show()
        compose.onNodeWithText("Tempo").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 300f))
            moveBy(Offset(0f, -300f))
            up()
        }
        assertSettled()
    }

    @Test
    fun the_race_refused_a_day_in_the_past_leaves_no_lifted_copy_behind() {
        show()
        drag("5K Race", toDay("5K Race", 22))  // Tuesday, before today
        assertSettled()
    }
}
