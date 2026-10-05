// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tracks.app.ui.goals.GoalDraft
import com.tracks.app.ui.goals.GoalEditorSheet
import com.tracks.app.ui.race.FuelTargetsForm
import com.tracks.app.ui.race.RacePlanCard
import com.tracks.app.ui.race.RacePlansContent
import com.tracks.app.ui.race.RacePlansState
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.TrainingGoal
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.local.LocalRacePlans
import com.tracks.core.local.RecentLoad
import com.tracks.core.plan.PlanPhases
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The redesigned Training and Race Plans pages, rendered on the JVM.
 *
 * Not assertions — pictures, written to build/outputs/roborazzi/ for a person
 * (or the agent building the screen) to look at, in both themes, empty and
 * full, at a phone's width. The phone this app is built on is somebody's
 * phone; these are how a layout gets checked without borrowing it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h2000dp-xxhdpi")
class TrainingScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 24)

    private val goal = TrainingGoal(
        id = 1, uid = "g1", goalType = "event", isActive = true, eventName = "5K",
        eventSport = "running", eventDate = "2026-10-15", eventDistanceMeters = 5000.0,
        daysPerWeek = 5, planIntensity = 1.0,
    )

    private val workouts = listOf(
        w(1, "2026-09-21", "easy", "Easy", 30, 5000.0, done = true),
        w(2, "2026-09-21", "strength", "Hill Power Builder", 40),
        w(3, "2026-09-22", "mobility", "Lower-Body Flush", 15),
        w(4, "2026-09-23", "tempo", "Tempo", 45, 8000.0),
        w(5, "2026-09-23", "strength", "Upper Body & Core", 35),
        w(6, "2026-09-24", "easy", "Easy", 30, 5000.0),
        w(7, "2026-09-24", "mobility", "Shoulder & Chest Release", 17),
        w(8, "2026-09-25", "intervals", "Fartlek Intervals", 40),
        w(9, "2026-09-27", "long_run", "Long Session", 75, 14000.0),
        w(10, "2026-09-30", "race_pace", "Race Pace", 40),
        w(11, "2026-10-15", "race", "5K Race"),
    )

    private fun w(id: Int, date: String, type: String, title: String, min: Int? = null, m: Double? = null, done: Boolean = false) =
        PlannedWorkout(id = id, scheduledDate = date, workoutType = type, title = title,
            durationMinutes = min, distanceMeters = m, isComplete = done)

    private val full = PlanUiState(
        loading = false,
        workouts = workouts,
        goals = listOf(goal, TrainingGoal(id = 2, goalType = "fitness", eventSport = "cycling", ctlRampPerWeek = 3.0)),
        phase = PlanPhases.info(CivilDate(2026, 10, 15), CivilDate(2026, 8, 1), CivilDate(2026, 9, 24)),
        predicted = "22:41",
        linked = true,
    )

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun training_week_light() = shoot("training_week_light", ThemeMode.Light) {
        TrainingContent(full, TrainingActions(), today = today)
    }

    /** No watch: the goal card has no "Sync to watch". */
    @Test
    fun training_week_no_device_light() = shoot("training_week_no_device_light", ThemeMode.Light) {
        androidx.compose.runtime.CompositionLocalProvider(com.tracks.app.ui.components.LocalHasDevice provides false) {
            TrainingContent(full, TrainingActions(), today = today)
        }
    }

    @Test
    fun training_week_dark() = shoot("training_week_dark", ThemeMode.Dark) {
        TrainingContent(full, TrainingActions(), today = today)
    }

    /** Mid-drag: the tempo lifted over Friday, which is tinted to take it. */
    @Test
    fun training_week_dragging_light() {
        shoot("training_week_dragging_before", ThemeMode.Light) { TrainingContent(full, TrainingActions(), today = today) }
        val from = compose.onNodeWithText("Tempo").fetchSemanticsNode().boundsInRoot
        val to = compose.onNodeWithText("Fartlek Intervals").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Tempo").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(androidx.compose.ui.geometry.Offset(12f, (to.center.y - from.center.y) / 2))
            moveBy(androidx.compose.ui.geometry.Offset(12f, (to.center.y - from.center.y) / 2))
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/training_week_dragging_light.png")
    }

    @Test
    fun training_month_light() {
        shoot("training_month_light_before", ThemeMode.Light) { TrainingContent(full, TrainingActions(), today = today) }
        compose.onNodeWithText("Month").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/training_month_light.png")
    }

    /** Another week, so the "Go to current week" button shows beside the range. */
    @Test
    fun training_next_week_light() {
        shoot("training_next_week_before", ThemeMode.Light) { TrainingContent(full, TrainingActions(), today = today) }
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/training_next_week_light.png")
    }

    /** The selected day's workouts inside the month's own card, not a card of their own. */
    @Test
    fun training_month_dark() {
        shoot("training_month_dark_before", ThemeMode.Dark) { TrainingContent(full, TrainingActions(), today = today) }
        compose.onNodeWithText("Month").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/training_month_dark.png")
    }

    /**
     * The goal sheet at a real phone's height, for two types whose fields
     * differ a lot: the sheet should be the same height for both, with the
     * short one's notes field taking the slack. A bottom sheet is a window of
     * its own, so this captures the screen rather than the root.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shootSheet(
        name: String, mode: ThemeMode, draft: GoalDraft,
        load: RecentLoad? = RecentLoad.NONE, onCopyLink: (() -> Unit)? = null,
    ) {
        compose.setContent {
            TracksTheme(mode = mode) {
                Surface {
                    GoalEditorSheet(draft, onSave = {}, onDelete = draft.id?.let { { } }, onDismiss = {},
                        eventLoad = load, onCopyLink = onCopyLink, today = today)
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** A new race: the recommended date already filled in, with its "?" beside it. */
    @Test @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun goal_sheet_event_dark() = shootSheet("goal_sheet_event_dark", ThemeMode.Dark, GoalDraft(goalType = "event"))

    @Test @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun goal_sheet_event_light() = shootSheet(
        "goal_sheet_event_light", ThemeMode.Light, GoalDraft(goalType = "event"),
        load = RecentLoad(45.0, listOf("running" to 1500.0, "cycling" to 500.0)),
    )

    @Test @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun goal_sheet_fitness_dark() = shootSheet("goal_sheet_fitness_dark", ThemeMode.Dark, GoalDraft(goalType = "fitness", includeStrength = true))

    @Test @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun goal_sheet_fitness_light() = shootSheet("goal_sheet_fitness_light", ThemeMode.Light, GoalDraft(goalType = "fitness"))

    /** An existing race being edited: strength on (its slider), and the subscription link at the foot. */
    @Test @Config(qualifiers = "w400dp-h2000dp-xxhdpi")
    fun goal_sheet_edit_light() = shootSheet(
        "goal_sheet_edit_light", ThemeMode.Light,
        GoalDraft.of(goal).copy(includeStrength = true, strengthTier = 2), onCopyLink = {},
    )

    @Test @Config(qualifiers = "w400dp-h2000dp-xxhdpi")
    fun goal_sheet_edit_dark() = shootSheet(
        "goal_sheet_edit_dark", ThemeMode.Dark,
        GoalDraft.of(goal).copy(includeStrength = true, strengthTier = 4), onCopyLink = {},
    )

    @Test @Config(qualifiers = "w400dp-h800dp-xxhdpi")
    fun goal_sheet_volume_light() = shootSheet("goal_sheet_volume_light", ThemeMode.Light, GoalDraft(goalType = "volume_target"))

    /** Scrolled to the strength toggle and the options below the sport, in both themes. */
    @Test @Config(qualifiers = "w400dp-h2000dp-xxhdpi")
    fun goal_sheet_event_full_light() =
        shootSheet("goal_sheet_event_full_light", ThemeMode.Light, GoalDraft(goalType = "event", eventSport = "cycling"))

    @Test @Config(qualifiers = "w400dp-h2000dp-xxhdpi")
    fun goal_sheet_event_full_dark() =
        shootSheet("goal_sheet_event_full_dark", ThemeMode.Dark, GoalDraft(goalType = "event", includeStrength = false, eventSport = "strength_training"))

    /** Swept screens: the workout editor's option grids and its Delete / Cancel / Save row. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shootWindow(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun workout_editor_light() = shootWindow("workout_editor_light", ThemeMode.Light) {
        WorkoutEditorSheet(WorkoutDraft.of(workouts[3]), {}, onDelete = {}, onDismiss = {})
    }

    @Test
    fun workout_editor_dark() = shootWindow("workout_editor_dark", ThemeMode.Dark) {
        WorkoutEditorSheet(WorkoutDraft.of(workouts[3]), {}, onDelete = {}, onDismiss = {})
    }

    @Test
    fun training_empty_dark() = shoot("training_empty_dark", ThemeMode.Dark) {
        TrainingContent(PlanUiState(loading = false), TrainingActions(), today = today)
    }

    private val card get() = RacePlanCard(
        goal, LocalRacePlans.Strategy(uid = "r1", splitSpread = -0.1, paceHrMode = "pace_hr"),
        vdot = 45.0,
        prediction = LocalRacePlans.running(45.0, 5000.0, "flat", -0.1, imperial = false, maxHr = 190),
    )

    @Test
    fun race_plans_list_light() = shoot("race_plans_list_light", ThemeMode.Light) {
        RacePlansContent(RacePlansState(loading = false, cards = listOf(card)), {}, { _, _, _ -> })
    }

    @Test
    fun race_plan_detail_dark() = shoot("race_plan_detail_dark", ThemeMode.Dark) {
        RacePlansContent(RacePlansState(loading = false, cards = listOf(card), openGoalId = 1, maxHr = 190), {}, { _, _, _ -> })
    }

    /** A half marathon with two products picked: targets, timeline and the gut ramp. */
    private val fuelledCard: RacePlanCard get() {
        val gel = com.tracks.core.fuel.FuelPlan.Product("g", "Gel", 25.0, 50)
        val big = com.tracks.core.fuel.FuelPlan.Product("b", "Big Gel", 40.0, 200, caffeineMg = 100)
        val pred = LocalRacePlans.running(45.0, 21097.5, "flat", 0.0, imperial = false, maxHr = 190)
        val strategy = LocalRacePlans.Strategy(uid = "r1", fuelCarbs = 60, fuelProductUids = listOf("g", "b"))
        val minutes = pred.seconds / 60.0
        val tg = com.tracks.core.fuel.FuelPlan.targets(minutes, carbs = 60)
        val fuel = LocalRacePlans.Fuel(
            tg,
            com.tracks.core.fuel.FuelPlan.timeline(minutes, tg, listOf(gel, big),
                pred.laps.map { com.tracks.core.fuel.FuelPlan.Lap(it.distanceM.toDouble(), it.targetSecPerKm) }),
            listOf(gel, big),
            mapOf("w1" to 40, "w2" to 50, "w3" to 60),
        )
        return RacePlanCard(goal, strategy, 45.0, pred, fuel)
    }

    /**
     * The targets editor, drawn on its own: it lives in a bottom sheet, which
     * is a separate window the root capture would miss. Carbs overridden, the
     * rest blank, so both states of a box are in the picture.
     */
    @Test
    fun race_plan_fuel_targets_light() = shoot("race_plan_fuel_targets_light", ThemeMode.Light) {
        val card = fuelledCard
        FuelTargetsForm(card.strategy, card.fuel!!.targets, { _, _ -> }, {})
    }

    @Test
    fun race_plan_fuel_light() = shoot("race_plan_fuel_light", ThemeMode.Light) {
        RacePlansContent(
            RacePlansState(loading = false, cards = listOf(fuelledCard), openGoalId = 1, maxHr = 190,
                linked = true, tracks = listOf("t1" to "Canyon loop")),
            {}, { _, _, _ -> },
        )
    }

    @Test
    fun race_plan_fuel_dark() = shoot("race_plan_fuel_dark", ThemeMode.Dark) {
        RacePlansContent(
            RacePlansState(loading = false, cards = listOf(fuelledCard), openGoalId = 1, maxHr = 190),
            {}, { _, _, _ -> },
        )
    }

    @Test
    fun training_fuel_practice_light() = shoot("training_fuel_practice_light", ThemeMode.Light) {
        val long = w(9, today.toString(), "long", "Long run", min = 100, done = true)
        DayCard(today, listOf(long), {}, {}, {}, {}, gutTargets = mapOf(9 to 50))
    }
}
