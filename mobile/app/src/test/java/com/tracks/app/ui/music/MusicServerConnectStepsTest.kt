// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.music

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.tracks.app.MusicUiState
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Connecting a music server, one question at a time. The form it replaced
 * asked for the address, username and password at once, and credentials typed
 * for an address nothing was listening on were its commonest dead end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class MusicServerConnectStepsTest {

    @get:Rule val compose = createComposeRule()

    private var foundAt: String? = "https://music.example.com"
    private var loginError: String? = null
    private val logins = mutableListOf<Triple<String, String, String>>()
    private var dismissed = false

    private fun show(music: MusicUiState = MusicUiState()) {
        compose.setContent {
            TracksTheme(mode = ThemeMode.Light) {
                Surface {
                    // The steps without their dialog window, where a text
                    // field never settles under Robolectric.
                    MusicServerConnectSteps(
                        music,
                        onSearch = {},
                        onFind = { _, done -> done(foundAt, if (foundAt == null) "No music server answered" else null) },
                        onLogIn = { url, user, pass, done -> logins += Triple(url, user, pass); done(loginError) },
                        onDone = { dismissed = true },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun the_login_is_asked_for_only_once_a_server_has_answered() {
        show()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Server address").performTextInput("music.example.com")
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Found a music server at music.example.com", substring = true).assertExists()
        compose.onNodeWithText("Username").performTextInput("hawk")
        compose.onNodeWithText("Password").performTextInput("secret")
        compose.onNodeWithText("Log in").performClick()
        compose.waitForIdle()

        // Signed in at the address that answered, not the one typed.
        assertEquals(listOf(Triple("https://music.example.com", "hawk", "secret")), logins)
        assertTrue(dismissed)
    }

    @Test fun an_address_with_no_server_stays_on_the_address_and_says_so() {
        foundAt = null
        show()
        compose.onNodeWithText("Server address").performTextInput("nowhere.example")
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No music server answered", substring = true).assertExists()
        compose.onNodeWithText("Username").assertDoesNotExist()
    }

    @Test fun a_refused_login_stays_open_with_the_reason_and_back_returns_to_the_address() {
        loginError = "Wrong username or password"
        show()
        compose.onNodeWithText("Server address").performTextInput("music.example.com")
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Username").performTextInput("hawk")
        compose.onNodeWithText("Password").performTextInput("nope")
        compose.onNodeWithText("Log in").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Wrong username or password").assertExists()
        assertTrue(!dismissed)

        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Server address").assertExists()
    }
}
