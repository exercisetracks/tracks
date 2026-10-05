// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tracks.app.ui.components.SlideOverHost
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.app.ui.strength.WorkoutKind
import com.tracks.app.ui.strength.libraryEntry
import com.tracks.app.ui.strength.workoutDraft
import com.tracks.core.api.Exercise
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The builder as the app really shows it: in its own dialog window, under the
 * app's [SlideOverHost]. The screenshot tests render its content bare, which is
 * how a picker drawn into the main window — behind the dialog — got past them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class BuilderPickerTest {

    @get:Rule val compose = createComposeRule()

    /**
     * A new workout opens on the page, not the picker — the user asked for the
     * picker not to slide over an empty page unasked. The button opens it, in
     * the builder's own window, and Done puts it away.
     */
    @Test fun a_new_workout_opens_on_the_page_and_the_button_brings_up_the_picker() {
        val library = listOf(Exercise("Goblet Squat", primaryMuscles = listOf("quads")))
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    SlideOverHost {
                        BuilderPage(
                            kind = WorkoutKind(library.associateBy { it.name }),
                            initial = workoutDraft(null),
                            library = library.map { it.libraryEntry() },
                            onSave = {}, onDelete = null, onBack = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Goblet Squat").assertDoesNotExist()
        compose.onNodeWithText("Add exercises").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Goblet Squat").assertExists()
        // In the builder's own window. Existing somewhere is not enough: a
        // past bug drew it in the main window, underneath the dialog.
        val builderRoot = compose.onNodeWithText("Cancel").fetchSemanticsNode().root
        val pickerRoot = compose.onNodeWithText("Goblet Squat").fetchSemanticsNode().root
        assertSame(builderRoot, pickerRoot)
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Goblet Squat").assertDoesNotExist()
    }
}
