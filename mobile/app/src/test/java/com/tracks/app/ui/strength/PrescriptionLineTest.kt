// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import com.tracks.core.api.UserWorkoutExercise
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The one line that summarises a prescription.
 *
 * Worth its own test for a single reason: `weight_value` carries three
 * different quantities depending on `weight_method` — a fraction of the
 * estimated one-rep max, a target RPE, or kilograms — and printing 0.75 with no
 * unit reads as three-quarters of a kilogram. Getting this wrong is not a crash,
 * it is somebody loading the wrong bar.
 */
class PrescriptionLineTest {

    private fun exercise(
        method: String,
        value: Double?,
    ) = UserWorkoutExercise(
        exerciseName = "Back Squat",
        targetSets = 4,
        targetReps = 5,
        rirTarget = 2,
        restSeconds = 150,
        weightMethod = method,
        weightValue = value,
    )

    @Test
    fun `a fraction of one-rep max is shown as a percentage`() {
        val line = prescriptionLine(exercise("percentage_e1rm", 0.75))
        assertTrue(line.contains("75% e1RM"), line)
    }

    @Test
    fun `an RPE is shown as an RPE, not as a weight`() {
        val line = prescriptionLine(exercise("rpe", 8.0))
        assertTrue(line.contains("RPE 8"), line)
        assertTrue(!line.contains("8 kg"), line)
    }

    @Test
    fun `a fixed weight is shown in kilograms`() {
        val line = prescriptionLine(exercise("fixed", 60.0))
        assertTrue(line.contains("60 kg"), line)
    }

    @Test
    fun `no weight set says nothing about weight`() {
        val line = prescriptionLine(exercise("percentage_e1rm", null))
        assertTrue(!line.contains("e1RM"), line)
        assertTrue(!line.contains("kg"), line)
    }

    @Test
    fun `reps are one number, as the watch counts them`() {
        assertTrue(prescriptionLine(exercise("fixed", null)).startsWith("4 × 5 ·"))
    }

    @Test
    fun `sets, reserve and rest are all present`() {
        val line = prescriptionLine(exercise("fixed", 60.0))
        assertTrue(line.contains("4 × 5"), line)
        assertTrue(line.contains("RIR 2"), line)
        assertTrue(line.contains("150s rest"), line)
    }
}
