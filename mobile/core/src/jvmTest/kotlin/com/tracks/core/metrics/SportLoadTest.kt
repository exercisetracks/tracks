// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.CivilDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sport's own fitness, for the lines the dashboard draws when a sport is
 * picked: a week of riding is fitness, but not running fitness. Scored
 * against the whole history's calibration, as the overall series is.
 */
class SportLoadTest {

    private val day = CivilDate(2026, 10, 1)
    private val run = MetricActivity(1, day, sport = "running", trainingStressScore = 50.0)
    private val ride = MetricActivity(2, day, sport = "cycling", trainingStressScore = 200.0)

    @Test
    fun a_sport_counts_only_its_own_load() {
        val all = listOf(run, ride)
        val calibration = TrainingLoad.loadCalibration(all, null)
        val running = TrainingLoad.trainingLoad(listOf(run), null, null, day, calibration)
        val overall = TrainingLoad.trainingLoad(all, null, null, day)
        assertEquals(overall.single().tss - running.single().tss, overall.single().tss - 50.0, 1e-9)
        assertTrue(running.single().ctl < overall.single().ctl)
    }
}
