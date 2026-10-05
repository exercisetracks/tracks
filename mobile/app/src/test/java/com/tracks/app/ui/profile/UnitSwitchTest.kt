// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Switching units converts what was typed. It did not: a height typed and
 * not yet committed survived the switch as the same digits under the new
 * unit — "180" cm became "180" in — and was then saved as 180 inches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h1600dp-xxhdpi")
class UnitSwitchTest {

    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(ProfileState(values = mapOf("units" to "metric"), loaded = true))

    private fun show() {
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    BodyForm(state) { field, value -> state = state.copy(values = state.values + (field to value)) }
                }
            }
        }
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun a_height_typed_in_centimetres_is_converted_when_switching_to_imperial() {
        show()
        field("Height").performTextInput("180")
        compose.onNodeWithText("Imperial").performClick()
        compose.waitForIdle()
        assertEquals(180.0, state.num("height_cm"))
        field("Height").assert(hasText("70.9"))
    }

    @Test
    fun a_weight_typed_in_pounds_is_converted_when_switching_to_metric() {
        state = state.copy(values = mapOf("units" to "imperial"))
        show()
        field("Weight").performTextInput("165")
        compose.onNodeWithText("Metric").performClick()
        compose.waitForIdle()
        assertEquals(74.8, state.num("weight_kg"))
        field("Weight").assert(hasText("74.8"))
    }

    @Test
    fun a_saved_height_follows_the_units_back_and_forth() {
        state = state.copy(values = mapOf("units" to "metric", "height_cm" to 180.0))
        show()
        compose.onNodeWithText("Imperial").performClick()
        compose.waitForIdle()
        field("Height").assert(hasText("70.9"))
        compose.onNodeWithText("Metric").performClick()
        compose.waitForIdle()
        field("Height").assert(hasText("180"))
        assertEquals(180.0, state.num("height_cm"))
    }
}
