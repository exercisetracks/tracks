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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.padding
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.status.StatusHost
import com.tracks.app.ui.status.StatusJob
import com.tracks.app.ui.status.StatusNote
import com.tracks.app.ui.status.StatusSource
import com.tracks.app.ui.components.syncLabel
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
    private fun popup(p: SyncProgress) = Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.TopCenter) {
        StatusHost(
            board = com.tracks.app.ui.status.StatusBoard(),
            jobs = mapOf(
                StatusSource.Server to StatusJob(syncLabel(p), p.fraction),
                StatusSource.Watch to StatusJob("Reading the watch"),
            ),
            notes = listOf(StatusNote(1, StatusSource.Watch, "Your watch refused to send its 12 file(s). Restart the watch", failed = true)),
            onDismiss = {}, onExpirePosted = {}, onSyncNow = {},
        )
    }

    @Test
    fun sync_popup_light() = shoot("sync_popup_light", ThemeMode.Light) {
        popup(SyncProgress(SyncProgress.Step.Files, 412, 2994))
    }

    @Test
    fun history_chart_bands_light() = shoot("history_chart_bands_light", ThemeMode.Light) {
        val today = java.time.LocalDate.now()
        val values = listOf(52.0, 54.0, 57.0, 60.0, 63.0, 59.0, 55.0, 51.0, 49.0, 53.0, 61.0, 67.0, 64.0, 58.0)
        com.tracks.app.ui.health.HistoryChart(
            points = values.mapIndexed { i, v ->
                com.tracks.app.ui.dashboard.HistoryPoint(today.minusDays((values.size - 1 - i).toLong()).toString(), v)
            },
            color = androidx.compose.ui.graphics.Color.Gray,
            zones = com.tracks.app.ui.health.RESTING_HR_ZONES,
            modifier = Modifier.padding(16.dp),
        )
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

    @Composable
    private fun musicConnect() = androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(16.dp),
    ) {
        com.tracks.app.ui.music.MusicServerConnectSteps(
            com.tracks.app.MusicUiState(), onSearch = {},
            onFind = { _, done -> done("https://music.example.com", null) },
            onLogIn = { _, _, _, _ -> }, onDone = {},
            modifier = Modifier.padding(24.dp),
        )
    }

    @Test
    fun music_connect_address_light() = shoot("music_connect_address_light", ThemeMode.Light) { musicConnect() }

    @Test
    fun music_connect_signin_dark() {
        shoot("music_connect_signin_dark", ThemeMode.Dark) { musicConnect() }
        compose.onNodeWithText("Server address").performTextInput("music.example.com")
        compose.onNodeWithText("Next").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/music_connect_signin_dark.png")
    }

    @Test
    fun sync_popup_dark() = shoot("sync_popup_dark", ThemeMode.Dark) {
        popup(SyncProgress(SyncProgress.Step.Receiving, 1500, null))
    }
}
