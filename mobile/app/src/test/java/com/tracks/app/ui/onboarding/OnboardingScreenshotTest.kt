// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.tracks.app.ui.profile.BodyForm
import com.tracks.app.ui.profile.FeaturesForm
import com.tracks.app.ui.profile.FrequencyForm
import com.tracks.app.ui.profile.LookForm
import com.tracks.app.ui.profile.ProfileState
import com.tracks.app.ui.profile.StrengthForm
import com.tracks.app.ui.profile.TrainingPrefsForm
import com.tracks.app.ui.profile.ZonesForm
import com.tracks.app.ui.screens.SettingsCard
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.Tokens
import com.tracks.app.ui.theme.TracksTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Onboarding's steps and Settings' account sections, rendered on the JVM in
 * both themes — pictures for a person to look at, written to
 * build/outputs/roborazzi/, because the phone this is built on is in use.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h1600dp-xxhdpi")
class OnboardingScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val profile = ProfileState(
        values = mapOf(
            "name" to "Alex", "units" to "imperial", "sex" to "female", "weight_kg" to 61.0,
            "height_cm" to 168.0, "timezone" to "America/New_York",
            "max_hr_mode" to "manual", "max_hr_manual" to 194.0,
            "equipment_available" to listOf("bodyweight", "dumbbell"),
            "strength_experience" to "regular", "theme_mode" to "system", "accent_color" to "emerald",
            "hidden_sports" to listOf("golf"),
            "activity_frequency" to mapOf("running" to "3_4", "swimming" to "never"),
        ),
        sports = listOf("running", "cycling", "golf", "hiking"),
        loaded = true,
    )

    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent {
            TracksTheme(mode = mode) {
                Surface {
                    Column(Modifier.padding(Tokens.Space.s6), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s5)) {
                        content()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun welcome_light() = shot("onboarding_welcome_light", ThemeMode.Light) { WelcomeStep({}, {}, {}) }
    @Test fun welcome_dark() = shot("onboarding_welcome_dark", ThemeMode.Dark) { WelcomeStep({}, {}, {}) }

    @Test fun body_light() = shot("onboarding_body_light", ThemeMode.Light) {
        ProfileStep("About you", "Used for calories, zones and the body model.", {}, {}) { BodyForm(profile) { _, _ -> } }
    }

    @Test fun zones_dark() = shot("onboarding_zones_dark", ThemeMode.Dark) {
        ProfileStep("Heart rate & power", "Leave these on Auto.", {}, {}) { ZonesForm(profile) { _, _ -> } }
    }

    @Test fun strength_light() = shot("onboarding_strength_light", ThemeMode.Light) {
        ProfileStep("Strength", "What you can lift with.", {}, {}) { StrengthForm(profile) { _, _ -> } }
    }

    @Test fun habits_light() = shot("onboarding_habits_light", ThemeMode.Light) {
        ProfileStep("How often you train", "Sets where your first plan starts.", {}, {}) { FrequencyForm(profile) { _, _ -> } }
    }

    @Test fun habits_empty_dark() = shot("onboarding_habits_empty_dark", ThemeMode.Dark) {
        ProfileStep("How often you train", "Sets where your first plan starts.", {}, {}) {
            FrequencyForm(ProfileState(loaded = true)) { _, _ -> }
        }
    }

    @Test fun device_light() = shot("onboarding_device_light", ThemeMode.Light) { DeviceStep {} }

    /** No watch: location and notifications only, and nothing blocks Continue. */
    @Test fun permissions_no_device_light() = shot("onboarding_permissions_no_device_light", ThemeMode.Light) {
        PermissionsList(grantsFor(hasDevice = false), mapOf("post_notifications" to true), {}, {})
    }

    @Test fun permissions_device_dark() = shot("onboarding_permissions_device_dark", ThemeMode.Dark) {
        PermissionsList(grantsFor(hasDevice = true), mapOf("location" to true), {}, {})
    }

    /** Settings' account sections with no watch: no pace coaching, no time zone. */
    @Test fun settings_no_device_light() = shot("settings_no_device_light", ThemeMode.Light) {
        androidx.compose.runtime.CompositionLocalProvider(com.tracks.app.ui.components.LocalHasDevice provides false) {
            SettingsCard("Profile") { BodyForm(profile) { _, _ -> } }
            SettingsCard("Training") { TrainingPrefsForm(profile, { _, _ -> }, { it.replaceFirstChar(Char::uppercase) }) }
            SettingsCard("How often you train") { FrequencyForm(profile) { _, _ -> } }
        }
    }

    @Test fun look_dark() = shot("onboarding_look_dark", ThemeMode.Dark) {
        ProfileStep("Look", "Shared with the web.", {}, {}) { LookForm(profile) { _, _ -> } }
    }

    @Test fun privacy_light() = shot("onboarding_privacy_light", ThemeMode.Light) { PrivacyStep({}, {}) }

    @Test fun done_dark() = shot("onboarding_done_dark", ThemeMode.Dark) {
        DoneStep(pairedName = null, hasDevice = true, standalone = true, onFinish = {})
    }

    @Test fun settings_sections_light() = shot("settings_sections_light", ThemeMode.Light) {
        SettingsCard("Training") { TrainingPrefsForm(profile, { _, _ -> }, { it.replaceFirstChar(Char::uppercase) }) }
        SettingsCard("Privacy & data") { FeaturesForm(profile) { _, _ -> } }
    }

    @Test fun settings_sections_dark() = shot("settings_sections_dark", ThemeMode.Dark) {
        SettingsCard("Profile") { BodyForm(profile) { _, _ -> } }
    }
}
