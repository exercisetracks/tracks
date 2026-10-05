// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.tracks.app.ui.flexibility.FlowControls
import com.tracks.app.ui.flexibility.FlowPlayerContent
import com.tracks.app.ui.flexibility.FlowSession
import com.tracks.app.ui.flexibility.Hold
import com.tracks.app.ui.flexibility.HoldPhase
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.core.api.Exercise
import com.tracks.core.api.FlexibilityFlow
import com.tracks.core.api.Stretch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The guided strength session and the flow player, rendered on the JVM into
 * build/outputs/roborazzi/ — pictures to look at, not assertions. See
 * TrainingScreenshotTest for why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h860dp-xxhdpi")
class SessionScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        compose.setContent { TracksTheme(mode = mode) { Surface { content() } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val squat = Exercise("Barbell Back Squat", primaryMuscles = listOf("quads", "glutes"),
        secondaryMuscles = listOf("hamstrings"), cues = listOf("Brace, sit between the heels."))
    private val row = Exercise("Alternating Dumbbell Row", primaryMuscles = listOf("upper_back", "lats"))

    private val session = SessionState(
        exercises = listOf(
            SessionExercise(squat, listOf(SessionSet(80.0, 5, true), SessionSet(80.0, 5), SessionSet(80.0, 5))),
            SessionExercise(row, listOf(SessionSet(22.5, 10), SessionSet(22.5, 10))),
        ),
        startedAtMillis = 1,
    )

    @Test fun strength_set_light() = shoot("strength_set_light", ThemeMode.Light) {
        SessionRunnerContent(session, { emptyList() }, RunnerActions())
    }

    @Test fun strength_resting_dark() = shoot("strength_resting_dark", ThemeMode.Dark) {
        SessionRunnerContent(session.copy(restRemaining = 74), { emptyList() }, RunnerActions())
    }

    @Test fun strength_summary_light() = shoot("strength_summary_light", ThemeMode.Light) {
        SessionSummary(SessionLogic.Summary(7, 2, 1885.0, 38), saving = false, error = null, onBack = {}, onSave = {})
    }

    private val pigeon = Stretch(name = "Pigeon Pose", primaryMuscles = listOf("glutes", "hip_external_rotators"),
        eachSide = true, breathCue = "Exhale and sink the hips.")
    private val flow = FlowSession(
        flow = FlexibilityFlow(id = 1, name = "Hip Opener Wind-Down"),
        holds = listOf(
            Hold(pigeon, "Pigeon Pose", 60, 10, side = "Left"),
            Hold(pigeon, "Pigeon Pose", 60, 10, side = "Right"),
            Hold(null, "Forward Fold", 45, 0),
        ),
        remaining = 41,
    )

    @Test fun flow_hold_dark() = shoot("flow_hold_dark", ThemeMode.Dark) {
        FlowPlayerContent(flow, FlowControls())
    }

    @Test fun flow_rest_light() = shoot("flow_rest_light", ThemeMode.Light) {
        FlowPlayerContent(flow.copy(phase = HoldPhase.Resting, remaining = 6), FlowControls())
    }

    @Test fun flow_done_light() = shoot("flow_done_light", ThemeMode.Light) {
        FlowPlayerContent(flow.copy(phase = HoldPhase.Done, index = 2, running = false), FlowControls())
    }
}
