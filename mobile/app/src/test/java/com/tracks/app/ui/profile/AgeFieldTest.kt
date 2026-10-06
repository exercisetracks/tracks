// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.tracks.app.ui.onboarding.ProfileStep
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import java.time.Year
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Age is asked as an age, stored as `birth_year`, and required: onboarding
 * cannot be left without it, and Settings cannot clear it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h1600dp-xxhdpi")
class AgeFieldTest {

    @get:Rule val compose = createComposeRule()

    private val thisYear = Year.now().value
    private var state by mutableStateOf(ProfileState(values = mapOf("units" to "metric"), loaded = true))
    private var advanced = false

    private fun set(field: String, value: Any?) { state = state.copy(values = state.values + (field to value)) }

    private fun showStep() {
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    Column {
                        ProfileStep(
                            "About you", "", onBack = {}, onNext = { advanced = true },
                            missing = { if (state.num("birth_year") == null) "Enter your age to continue." else null },
                        ) { BodyForm(state, ::set) }
                    }
                }
            }
        }
    }

    private fun ageField() = compose.onNode(hasSetTextAction() and hasText("Age"))

    @Test
    fun an_age_and_its_birth_year_round_trip_and_age_a_year_later_on_their_own() {
        assertEquals(1996, birthYearFromAge("34", 2030))
        assertEquals(34, ageFromBirthYear(1996.0, 2030))
        assertEquals(35, ageFromBirthYear(1996.0, 2031))
    }

    /** "3" is what the field holds on the way to "34"; it must not be saved as a three-year-old. */
    @Test
    fun an_implausible_or_partial_age_is_refused() {
        for (bad in listOf("", "3", "34.5", "abc", "${MIN_AGE - 1}", "${MAX_AGE + 1}")) {
            assertNull(bad, birthYearFromAge(bad, 2030))
        }
    }

    @Test
    fun onboarding_cannot_continue_without_an_age() {
        showStep()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertFalse(advanced)
        compose.onNodeWithText("Enter your age to continue.").assertExists()
    }

    /**
     * The age is written when its field loses focus. Typing it and going
     * straight for Continue must count — that tap is what takes the focus.
     */
    @Test
    fun an_age_typed_and_not_yet_committed_lets_onboarding_continue() {
        showStep()
        ageField().performTextInput("34")
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertTrue(advanced)
        assertEquals((thisYear - 34).toDouble(), state.num("birth_year")!!, 0.0)
    }

    @Test
    fun a_cleared_age_is_not_saved_and_the_stored_one_comes_back() {
        set("birth_year", (thisYear - 40).toDouble())
        showStep()
        ageField().performTextClearance()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals((thisYear - 40).toDouble(), state.num("birth_year")!!, 0.0)
        ageField().assert(hasText("40"))
        assertTrue(advanced)
    }
}
