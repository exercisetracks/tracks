// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.plan.RunningFitness
import com.tracks.core.race.RacePredictor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * What today's running fitness is read from, gathered from this phone's own
 * library the way the server's `_get_running_evidence` gathers it from
 * Postgres (api/training_plan/helpers.py): every pace best of the last year
 * with its run's date, the last year's runs, and the median resting HR of the
 * last 30 days. The estimate itself is [RunningFitness]; the plan and the race
 * predictions both read it from here, so the phone has one running fitness,
 * as the server does.
 *
 * Every read here is a blocking library query (the efforts parse each run's
 * detail JSON), and the callers are view models on the main thread, so each
 * function moves itself onto the IO pool, as [LocalSources] does.
 */
class LocalRunningEvidence(private val sources: LocalSources, private val library: LocalLibrary) {

    data class Evidence(
        val efforts: List<RunningFitness.Effort>,
        val runs: List<RunningFitness.Run>,
        val restingHr: Double?,
    )

    /** `_get_running_evidence`. */
    suspend fun evidence(today: CivilDate): Evidence = withContext(Dispatchers.IO) { read(today, efforts = true) }

    private suspend fun read(today: CivilDate, efforts: Boolean): Evidence {
        val cutoff = today.epochDay - RunningFitness.EFFORT_MAX_AGE_DAYS
        val runs = ArrayList<RunningFitness.Run>()
        val bests = ArrayList<RunningFitness.Effort>()
        // Newest first, as the server orders them: the watch's VO2max on a day
        // with two runs is the later run's on both.
        for (a in library.metricActivities(sources.accountZone())) {
            if (a.sport !in RUN_SPORTS || a.date.epochDay < cutoff) continue
            runs += RunningFitness.Run(
                a.date, a.sport, a.distanceMeters, a.durationSeconds?.toDouble(), a.avgSpeed,
                a.avgHeartRate?.toDouble(), a.totalAscent, a.vo2maxEstimate,
            )
            if (!efforts) continue
            val uid = library.uidOf(a.id.toInt()) ?: continue
            val curve = library.detailJson(uid)?.get("pace_curve") as? JsonObject ?: continue
            for ((k, v) in curve) {
                val dist = k.toDoubleOrNull() ?: continue
                val speed = (v as? JsonPrimitive)?.doubleOrNull ?: continue
                bests += RunningFitness.Effort(a.date, dist, speed)
            }
        }
        if (!efforts) return Evidence(bests, runs, null)
        val rhr = LocalMetrics(library, sources).dayMetrics()
            .filter { it.date <= today && it.date.epochDay > today.epochDay - RESTING_HR_DAYS }
            .mapNotNull { it.restingHr }
        return Evidence(bests, runs, if (rhr.isEmpty()) null else com.tracks.core.parse.PyMath.median(rhr))
    }

    /** `_get_running_fitness`: today's estimate from this phone's data and settings. */
    suspend fun fitness(today: CivilDate): RunningFitness.Estimate {
        val evidence = evidence(today)
        val s = sources.settingValues()
        return RunningFitness.estimate(
            evidence.efforts, evidence.runs, today,
            maxHr = sources.importThresholds().maxHr,
            restingHr = evidence.restingHr,
            sex = s["sex"] as? String,
            heightCm = (s["height_cm"] as? Number)?.toDouble(),
            weightKg = (s["weight_kg"] as? Number)?.toDouble(),
            birthYear = (s["birth_year"] as? Number)?.toInt(),
            frequencies = s["activity_frequency"],
        )
    }

    /** The marathon's training indices (RacePredictor.trainingIndices) for today — runs only. */
    suspend fun trainingIndices(today: CivilDate): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        RacePredictor.trainingIndices(read(today, efforts = false).runs, today)
    }

    private companion object {
        /** `_RUN_SPORTS` in helpers.py. */
        val RUN_SPORTS = setOf("running", "trail_running", "treadmill_running", "road_running", "virtual_running")
        /** `_RESTING_HR_DAYS`. */
        const val RESTING_HR_DAYS = 30
    }
}
