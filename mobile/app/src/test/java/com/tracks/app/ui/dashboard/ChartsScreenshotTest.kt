// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.tracks.app.ui.activity.StreamsCard
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import com.tracks.app.ui.theme.Accent
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.TrackPoint
import com.tracks.core.api.TrainingLoadPoint
import com.tracks.core.api.WeeklyVolumePoint
import java.time.LocalDate
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the dashboard's and the activity page's line charts — for a
 * person to look at in build/outputs/roborazzi/, not assertions.
 *
 * The streams cover the case the y scaling exists for: a gentle ride whose
 * elevation moves a few metres, which used to sit as a flat line on the floor
 * of a 0-based axis.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h1400dp-xxhdpi")
class ChartsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, mode: ThemeMode = ThemeMode.Light, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.setContent { TracksTheme(mode = mode) { Surface { Column(Modifier.padding(16.dp)) { content() } } } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // Anchored to today rather than a fixed date, so the picture never ages.
    private val start = LocalDate.now().minusDays(180)

    private val load = (0 until 180).map { i ->
        val ctl = 28.0 + 12 * sin(i / 30.0)
        val atl = ctl + 10 * sin(i / 5.0)
        TrainingLoadPoint(start.plusDays(i.toLong()).toString(), ctl = ctl, atl = atl, tsb = ctl - atl)
    }

    // Gaps at weeks 3 and 7: no activity at all, so the server sends no row.
    private val weeks = (0 until 12).filter { it != 3 && it != 7 }.map { w ->
        val monday = start.minusDays(start.dayOfWeek.value - 1L).plusWeeks(w.toLong())
        WeeklyVolumePoint(monday.toString(), distanceKm = 30.0 + 4 * w, durationHours = 3.0 + (w % 4))
    }

    private val gentle = (0 until 400).map { i ->
        TrackPoint(
            altitude = 112.0 + 4 * sin(i / 40.0),
            heartRate = (128 + 6 * sin(i / 25.0)).toInt(),
            speed = 6.8 + 0.4 * sin(i / 15.0),
            cadence = (86 + 3 * sin(i / 10.0)).toInt(),
            power = (180 + 30 * sin(i / 12.0)).toInt(),
        )
    }

    private val hilly = (0 until 400).map { i ->
        TrackPoint(
            altitude = 820.0 + 340 * sin(i / 70.0),
            heartRate = (150 + 22 * sin(i / 30.0)).toInt(),
            speed = 3.2 + 1.1 * sin(i / 20.0),
        )
    }

    /** The chart with a finger held on it, so the popup is in the picture. */
    private fun shootHeld(name: String, mode: ThemeMode, accent: Accent, atY: Int = 160, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            TracksTheme(mode = mode, accent = accent) { Surface { Column(Modifier.padding(16.dp)) { content() } } }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().performTouchInput { down(Offset(width * 0.45f, atY.dp.toPx())) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun fitness_held_light() = shootHeld("chart_fitness_held_light", ThemeMode.Light, Accent.Violet) { FitnessChart(load) }

    @Test fun fitness_held_dark() = shootHeld("chart_fitness_held_dark", ThemeMode.Dark, Accent.Blue) { FitnessChart(load) }

    @Test fun form_held_light() = shootHeld("chart_form_held_light", ThemeMode.Light, Accent.Violet, atY = 300) { FitnessChart(load) }

    @Test fun volume_held_dark() = shootHeld("chart_volume_held_dark", ThemeMode.Dark, Accent.Violet) { WeeklyVolumeChart(weeks) }

    @Test fun fitness_light() = shoot("chart_fitness_light") { FitnessChart(load) }

    @Test fun fitness_dark() = shoot("chart_fitness_dark", ThemeMode.Dark) { FitnessChart(load) }

    @Test fun volume_light() = shoot("chart_volume_light") { WeeklyVolumeChart(weeks) }

    @Test fun streams_gentle() = shoot("chart_streams_gentle") { StreamsCard(gentle) }

    @Test fun streams_hilly() = shoot("chart_streams_hilly", ThemeMode.Dark) { StreamsCard(hilly) }
}
