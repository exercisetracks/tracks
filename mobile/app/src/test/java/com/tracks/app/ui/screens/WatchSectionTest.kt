// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tracks.app.UiState
import com.tracks.app.WatchUiState
import com.tracks.app.ui.profile.ProfileState
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With a watch paired, Settings offers Unpair where "I have a Garmin watch"
 * was. The switch stayed on screen beside a paired watch, asking a question
 * the pairing had already answered, and there was no way to unpair at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h2400dp-xxhdpi")
class WatchSectionTest {

    @get:Rule val compose = createComposeRule()

    private var forgotten = 0

    private fun show(pairedName: String?) {
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    Column {
                        WatchSection(
                            state = UiState(watch = WatchUiState(pairedName = pairedName)),
                            profile = ProfileState(),
                            onSet = { _, _ -> },
                            feeds = emptyList(),
                            hasDevice = true,
                            onHasDeviceChange = {},
                            onPair = {},
                            onSync = {},
                            onForget = { forgotten++ },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun a_paired_watch_shows_unpair_instead_of_the_switch() {
        show(pairedName = "fenix 6X Pro")
        compose.onAllNodesWithText("I have a Garmin watch").assertCountEquals(0)
        compose.onNodeWithText("Unpair watch").performClick()
        compose.onNodeWithText("Unpair").performClick()
        compose.waitForIdle()
        assertEquals(1, forgotten)
    }

    @Test
    fun with_nothing_paired_the_switch_is_back_and_unpair_is_not() {
        show(pairedName = null)
        compose.onNodeWithText("I have a Garmin watch").assertExists()
        compose.onAllNodesWithText("Unpair watch").assertCountEquals(0)
    }
}
