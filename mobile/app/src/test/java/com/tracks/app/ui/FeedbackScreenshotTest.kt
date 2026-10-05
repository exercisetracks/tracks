// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.padding
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.SyncProgressPopup
import com.tracks.app.ui.music.MusicAccountRow
import com.tracks.app.ui.screens.SettingsCard
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.replica.SyncProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of what the first-impressions round changed, both themes — for a
 * person to look at in build/outputs/roborazzi/, not assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h800dp-xxhdpi")
class FeedbackScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun popup(p: SyncProgress) = Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.BottomCenter) {
        SyncProgressPopup(p)
    }

    @Test
    fun sync_popup_light() = shoot("sync_popup_light", ThemeMode.Light) {
        popup(SyncProgress(SyncProgress.Step.Files, 412, 2994))
    }

    @Composable
    private fun musicAccount() = androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
        SettingsCard("Music server", MetricInfo("Music server", "Navidrome for the watch app.")) {
            MusicAccountRow("alex", "https://music.example.com:4533/", busy = false) {}
        }
    }

    @Test
    fun music_account_light() = shoot("music_account_light", ThemeMode.Light) { musicAccount() }

    @Test
    fun music_account_dark() = shoot("music_account_dark", ThemeMode.Dark) { musicAccount() }

    @Test
    fun sync_popup_dark() = shoot("sync_popup_dark", ThemeMode.Dark) {
        popup(SyncProgress(SyncProgress.Step.Receiving, 1500, null))
    }
}
