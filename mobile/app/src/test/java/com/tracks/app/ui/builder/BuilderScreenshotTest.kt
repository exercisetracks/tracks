// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tracks.app.ui.body.MuscleFilterSide
import com.tracks.app.ui.recap.AnimationRecapDialog
import com.tracks.app.ui.strength.LibraryFilters
import com.tracks.app.ui.strength.LibraryFilter
import com.tracks.app.ui.strength.PrefToggle
import com.tracks.app.ui.strength.WorkoutKind
import com.tracks.app.ui.strength.libraryEntry
import com.tracks.app.ui.strength.workoutDraft
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.Exercise
import com.tracks.core.api.UserWorkout
import com.tracks.core.api.UserWorkoutExercise
import com.tracks.core.local.AnimationAsk
import com.tracks.core.local.AnimationRecap
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Strength/Flexibility redesign rendered on the JVM into
 * build/outputs/roborazzi/ — pictures to look at, not assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class BuilderScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val library = listOf(
        Exercise("Goblet Squat", primaryMuscles = listOf("quads", "glutes"), defaultSets = 3, defaultReps = 10),
        Exercise("Push Up", primaryMuscles = listOf("chest", "triceps")),
        Exercise("Barbell Row", primaryMuscles = listOf("upper_back", "lats")),
        Exercise("Plank", primaryMuscles = listOf("abs")),
    )

    private fun g(uid: String, kind: String, rounds: Int, rest: Int) = mapOf(
        "uid" to uid, "kind" to kind, "rounds" to rounds, "rest" to rest,
    )

    private val workout = UserWorkout(
        id = 1, name = "Upper circuit", description = "Tuesdays", tags = listOf("upper_body"),
        exercises = listOf(
            UserWorkoutExercise(exerciseName = "Goblet Squat", orderIndex = 0),
            UserWorkoutExercise(itemKind = "rest", restSeconds = 120, orderIndex = 1),
            UserWorkoutExercise(exerciseName = "Push Up", orderIndex = 2, groupUid = "g", groupKind = "repeat", groupRounds = 3, groupRestSeconds = 60),
            UserWorkoutExercise(exerciseName = "Barbell Row", orderIndex = 3, groupUid = "g", groupKind = "repeat", groupRounds = 3, groupRestSeconds = 60),
            UserWorkoutExercise(exerciseName = "Plank", orderIndex = 4),
        ),
    )

    @Composable
    private fun Builder(adding: Boolean) = BuilderContent(
        kind = WorkoutKind(library.associateBy { it.name }),
        initial = workoutDraft(workout),
        library = library.map { it.libraryEntry() },
        onSave = {}, onDelete = {}, onBack = {},
        startAdding = adding,
    )

    @Test fun builder_page_light() = shoot("builder_page_light", ThemeMode.Light) { Builder(adding = false) }
    @Test fun builder_page_dark() = shoot("builder_page_dark", ThemeMode.Dark) { Builder(adding = false) }
    @Test fun builder_add_panel_light() = shoot("builder_add_panel_light", ThemeMode.Light) { Builder(adding = true) }
    @Test fun builder_add_panel_dark() = shoot("builder_add_panel_dark", ThemeMode.Dark) { Builder(adding = true) }

    /** A just-added, empty Superset: the drop target a new group starts as. */
    @Composable
    private fun WithEmptyGroup() = BuilderContent(
        kind = WorkoutKind(library.associateBy { it.name }),
        initial = workoutDraft(null, startFrom = workout.exercises.take(2)),
        library = library.map { it.libraryEntry() },
        onSave = {}, onDelete = null, onBack = {},
        startAdding = false,
    )
    @Test fun builder_new_light() {
        compose.setContent { TracksTheme(mode = ThemeMode.Light) { Surface { WithEmptyGroup() } } }
        compose.onNodeWithText("Add superset").performClick()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/builder_new_light.png")
    }

    /** The filter at the size the user sized it for: figures as large as the phone allows. */
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun muscle_filter_phone() = shoot("muscle_filter_phone", ThemeMode.Light) {
        MuscleFilterSide(setOf("quads", "lats", "biceps"), {}, {})
    }

    @Test fun muscle_filter_light() = shoot("muscle_filter_light", ThemeMode.Light) {
        MuscleFilterSide(setOf("quads", "lats"), {}, {})
    }
    @Test fun muscle_filter_dark() = shoot("muscle_filter_dark", ThemeMode.Dark) {
        MuscleFilterSide(setOf("quads", "lats"), {}, {})
    }

    @Composable
    private fun Rows() = Column(Modifier.padding(16.dp)) {
        LibraryFilters(
            title = "Exercise library", searchLabel = "Search exercises", search = "", onSearch = {}, muscles = setOf("quads"),
            onOpenMuscles = {}, filter = LibraryFilter.All, onFilter = {}, onCustom = {},
        )
        PrefToggle(preference = "preferred") {}
        PrefToggle(preference = "excluded") {}
    }
    @Test fun library_rows_light() = shoot("library_rows_light", ThemeMode.Light) { Rows() }
    @Test fun library_rows_dark() = shoot("library_rows_dark", ThemeMode.Dark) { Rows() }

    /** Strength's header: the dropdown, Muscles and My equipment on one row. */
    @Composable
    private fun StrengthHeader(filter: LibraryFilter = LibraryFilter.All, muscles: Set<String> = emptySet(), only: Boolean = false) =
        Column(Modifier.padding(16.dp)) {
            LibraryFilters(
                title = "Exercise library", searchLabel = "Search exercises", search = "", onSearch = {},
                muscles = muscles, onOpenMuscles = {}, filter = filter, onFilter = {}, onCustom = {},
                myEquipment = setOf("dumbbell"), onlyMyEquipment = only,
            )
        }
    @Test fun strength_header_light() = shoot("strength_header_light", ThemeMode.Light) { StrengthHeader() }
    @Test fun strength_header_dark() = shoot("strength_header_dark", ThemeMode.Dark) {
        StrengthHeader(LibraryFilter.Preferred, setOf("quads", "lats"), only = true)
    }
    /** The width the row was sized for: a small phone, with the longest labels. */
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    @Test fun strength_header_narrow() = shoot("strength_header_narrow", ThemeMode.Light) {
        StrengthHeader(LibraryFilter.Preferred, setOf("hamstrings", "lats", "biceps"), only = true)
    }
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    @Test fun builder_page_narrow() = shoot("builder_page_narrow", ThemeMode.Light) { Builder(adding = false) }

    @Composable
    private fun Recap() {
        val answers = mutableStateMapOf<AnimationAsk, Boolean>()
        AnimationRecapDialog(
            AnimationRecap("w", "Upper circuit", listOf(AnimationAsk("push_up", 77, "Push Up"), AnimationAsk("row", 3, "Barbell Row"))),
            answers, {}, {},
        )
    }
    @Test fun recap_prompt_light() = shoot("recap_prompt_light", ThemeMode.Light) { Recap() }
    @Test fun recap_prompt_dark() = shoot("recap_prompt_dark", ThemeMode.Dark) { Recap() }
}
