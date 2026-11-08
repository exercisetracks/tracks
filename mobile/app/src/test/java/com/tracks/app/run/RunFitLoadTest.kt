// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitReader
import com.tracks.core.metrics.MetricActivity
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.FitParsing
import com.tracks.core.run.RunFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A run recorded on the phone, read back by the phone's own importer, carries
 * load into the fitness model.
 *
 * [RunFitTest] parses the encoder's output with Gadgetbridge's reader. Nothing
 * checked it with the reader that actually imports it — Tracks' own
 * (com.tracks.core.parse) — and for someone with no watch that is the whole
 * of their training: a duration or sport that came back empty would score
 * every run as nothing, and fitness and readiness would sit at their defaults
 * however much they ran.
 */
class RunFitLoadTest {

    private fun fixes(minutes: Int, speed: Double): List<RunFix> = (0 until minutes * 60 step 5).map { s ->
        RunFix(
            timestampMs = START_MS + s * 1000L,
            elapsedMs = s * 1000L,
            lat = 46.0 + speed * s / 111_000.0,
            lng = 7.0,
            altitudeM = 500.0 + (s % 600) / 20.0,
            accuracyM = 5.0,
            speedMps = speed,
        )
    }

    private fun parsedActivity(bytes: ByteArray): Map<String, Any?> {
        assertEquals(FitParsing.Kind.Activity, FitParsing.select(bytes))
        @Suppress("UNCHECKED_CAST")
        return FitParsing.parse(FitParsing.Kind.Activity, bytes)["activity"] as Map<String, Any?>
    }

    private fun metric(a: Map<String, Any?>) = MetricActivity(
        id = 1,
        date = CivilDate(2026, 11, 1),
        sport = a["sport"] as String?,
        distanceMeters = (a["distance_meters"] as Number?)?.toDouble(),
        durationSeconds = (a["duration_seconds"] as Number?)?.toLong(),
        avgHeartRate = (a["avg_heart_rate"] as Number?)?.toInt(),
        maxHeartRate = (a["max_heart_rate"] as Number?)?.toInt(),
        trainingStressScore = (a["training_stress_score"] as Number?)?.toDouble(),
        totalAscent = (a["total_ascent"] as Number?)?.toDouble(),
    )

    @Test
    fun `a phone run is read back as a run with its time, distance and climb`() {
        val minutes = 40
        val speed = 2.9
        val bytes = requireNotNull(
            RunFit.encode(
                fixes = fixes(minutes, speed),
                distanceM = minutes * 60 * speed,
                ascentM = 45.0,
                elapsedMs = minutes * 60_000L,
                movingMs = minutes * 60_000L,
                startedAtMs = START_MS,
            )
        )
        val a = parsedActivity(bytes)
        assertEquals("running", a["sport"])
        assertEquals(minutes * 60L, (a["duration_seconds"] as Number).toLong())
        assertEquals(minutes * 60 * speed, (a["distance_meters"] as Number).toDouble(), 1.0)
        assertEquals(45.0, (a["total_ascent"] as Number).toDouble(), 0.5)
        assertNull("a phone has no heart rate", a["avg_heart_rate"])
        assertNull("nor a device load", a["training_stress_score"])
    }

    @Test
    fun `the session's elapsed time is written in the profile's unit`() {
        // The parsers repair files from before this was right, so reading
        // the duration back cannot tell a fixed encoder from a repaired
        // file: the session message itself has to say 2400 seconds.
        val bytes = requireNotNull(
            RunFit.encode(
                fixes = fixes(40, 2.9), distanceM = 40 * 60 * 2.9, ascentM = 45.0,
                elapsedMs = 40 * 60_000L, movingMs = 40 * 60_000L - 1_500, startedAtMs = START_MS,
            )
        )
        val timed = requireNotNull(RunFit.encodeTimed(START_MS, 45 * 60_000L, 44 * 60_000L, 10, 20))
        for ((file, seconds) in listOf(bytes to 2400.0, timed to 2700.0)) {
            val session = FitReader(file).messages().first { it.name == "session" }
            assertEquals(seconds, (session.get("total_elapsed_time") as Number).toDouble(), 0.001)
        }
    }

    @Test
    fun `a phone run scores load in the fitness model`() {
        val bytes = requireNotNull(
            RunFit.encode(
                fixes = fixes(40, 2.9),
                distanceM = 40 * 60 * 2.9,
                ascentM = 45.0,
                elapsedMs = 40 * 60_000L,
                movingMs = 40 * 60_000L,
                startedAtMs = START_MS,
            )
        )
        val tss = TrainingLoad.estimateTss(metric(parsedActivity(bytes)))
        // 40 minutes at the running estimate's IF 0.78 is ~40 TSS.
        assertTrue("a 40-minute run scored $tss", tss in 30.0..55.0)
    }

    @Test
    fun `a timed session with no track scores load too`() {
        val (sport, sub) = RunFit.fitSport("strength_training")
        val bytes = requireNotNull(RunFit.encodeTimed(START_MS, 45 * 60_000L, 45 * 60_000L, sport, sub))
        val tss = TrainingLoad.estimateTss(metric(parsedActivity(bytes)))
        assertTrue("45 minutes of strength scored $tss", tss > 15.0)
    }

    private companion object {
        /** 2026-11-01 07:00 UTC. */
        const val START_MS = 1_793_516_400_000L
    }
}
