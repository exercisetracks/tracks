// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fuel.FuelPlan
import com.tracks.core.race.GpxCourse
import kotlinx.serialization.json.contentOrNull
import com.tracks.core.race.Segment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import com.tracks.core.api.TrainingGoal
import com.tracks.core.race.RaceLap
import com.tracks.core.race.RacePredictor
import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Race plans on the phone: the strategy somebody chose for an event, and the
 * pacing it implies.
 *
 * The strategy (terrain, split, pace-or-HR targets) is a `race_plan` row and
 * syncs. The prediction and the per-lap paces are derived from it and from
 * the training plan's VDOT with the ported race predictor, every time, as
 * everything derived is — so they need no server and cannot disagree between
 * devices about inputs they share.
 *
 * ## What the phone's prediction leaves out, and says so
 *
 * The server's `POST /race-plan/generate` also scales by race-day freshness
 * (from the fitness model) and by a weather forecast it fetches; the phone
 * predicts on a flat, neutral day. A GPX course is parsed on the server only
 * (the phone has no GPX parser yet), so a course arrives here as the synced
 * `course_segments` a server wrote, or not at all. Running is covered; the
 * cycling, swimming and triathlon breakdowns stay on the web.
 */
class LocalRacePlans(private val sources: LocalSources) {

    data class Strategy(
        val uid: String?,
        val courseType: String = "flat",
        val splitSpread: Double = 0.0,
        val paceHrMode: String = "pace",
        val hasCourse: Boolean = false,
        val watchUploadedAt: String? = null,
        /** The loaded course's ~100 m segments; empty when none is loaded. */
        val segments: List<Segment> = emptyList(),
        /** The loaded course's sampled path as (lat, lon), for its map preview; empty when none. */
        val path: List<Pair<Double, Double>> = emptyList(),
        val useGpxDistance: Boolean = false,
        /** The user's fuelling overrides; null = the calculated default. */
        val fuelCarbs: Int? = null,
        val fuelFluid: Int? = null,
        val fuelSodium: Int? = null,
        val fuelInterval: Int? = null,
        /** fuel_product uids the timeline may use, in the user's order. */
        val fuelProductUids: List<String> = emptyList(),
    )

    /** Everything the Fuelling section shows: targets, the plan, the ramp. */
    data class Fuel(
        val targets: FuelPlan.Targets,
        val timeline: FuelPlan.Timeline,
        val products: List<FuelPlan.Product>,
        val gut: Map<String, Int>,
    )

    data class Prediction(val seconds: Double, val laps: List<RaceLap>)

    suspend fun strategy(goal: TrainingGoal): Strategy {
        val row = row(goal) ?: return Strategy(uid = null)
        return Strategy(
            uid = row.uid,
            courseType = row.str("course_type") ?: "flat",
            splitSpread = (row.fields["split_spread"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            paceHrMode = row.str("pace_hr_mode") ?: "pace",
            hasCourse = row.fields["course_segments"].let { it != null && it.toString() != "null" && it.toString() != "[]" },
            watchUploadedAt = row.str("watch_uploaded_at"),
            segments = (row.fields["course_segments"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Segment(
                    distanceM = (o["distance_m"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                    gradient = (o["gradient"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    elevationGainM = (o["elevation_gain_m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                )
            },
            path = (row.fields["course_path"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonArray ?: return@mapNotNull null
                val lat = (p.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val lon = (p.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                lat to lon
            },
            useGpxDistance = (row.fields["use_gpx_distance"] as? JsonPrimitive)?.booleanOrNull == true,
            fuelCarbs = row.int("fuel_carbs_per_hour"),
            fuelFluid = row.int("fuel_fluid_ml_per_hour"),
            fuelSodium = row.int("fuel_sodium_mg_per_hour"),
            fuelInterval = row.int("fuel_interval_min"),
            fuelProductUids = (row.fields["fuel_product_uids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    private fun SyncedRow.int(field: String): Int? = (fields[field] as? JsonPrimitive)?.doubleOrNull?.toInt()

    /** Every fuel product the user has, as the planner takes them. */
    suspend fun products(): List<FuelPlan.Product> = sources.replica.rows("fuel_product").map {
        FuelPlan.Product(
            uid = it.uid, name = it.str("name"),
            carbsG = (it.fields["carbs_g"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            sodiumMg = it.int("sodium_mg") ?: 0, fluidMl = it.int("fluid_ml") ?: 0,
            caffeineMg = it.int("caffeine_mg") ?: 0,
        )
    }.sortedBy { (it.name ?: "").lowercase() }

    suspend fun addProduct(name: String, kind: String, carbsG: Double, sodiumMg: Int, caffeineMg: Int, fluidMl: Int) {
        sources.createValues("fuel_product", mapOf(
            "name" to name, "kind" to kind, "carbs_g" to carbsG, "sodium_mg" to sodiumMg,
            "caffeine_mg" to caffeineMg, "fluid_ml" to fluidMl,
        ))
    }

    /**
     * The fuel plan for [goal] — `fuel_plan_for` in backend/app/api/fuel.py,
     * worked out here from synced rows: the same targets, timeline and
     * gut-training ramp the web shows.
     */
    suspend fun fuel(goal: TrainingGoal, prediction: Prediction?): Fuel {
        val s = strategy(goal)
        val minutes = (prediction?.seconds ?: 0.0) / 60.0
        val tg = FuelPlan.targets(minutes, null, null, s.fuelCarbs, s.fuelFluid, s.fuelSodium, s.fuelInterval)
        val all = products().associateBy { it.uid }
        val chosen = s.fuelProductUids.mapNotNull { all[it] }
        val timeline = if (minutes > 0) {
            FuelPlan.timeline(minutes, tg, chosen, prediction?.laps?.map { FuelPlan.Lap(it.distanceM.toDouble(), it.targetSecPerKm) })
        } else FuelPlan.Timeline(emptyList(), FuelPlan.Amounts(0, 0, 0, 0), FuelPlan.Amounts(0, 0, 0, 0))
        val gut = goal.eventDate?.let { race ->
            val plan = sources.replica.rows("plan").firstOrNull { it.str("goal_uid") == goal.uid }?.uid
            val workouts = sources.replica.rows("planned_workout")
                .filter { plan == null || it.str("plan_uid") == null || it.str("plan_uid") == plan }
                .map {
                    FuelPlan.Workout(it.uid, it.str("scheduled_date"), it.str("sport"), it.str("workout_type"),
                        (it.fields["duration_minutes"] as? JsonPrimitive)?.doubleOrNull, it.int("fuel_carbs_per_hour"))
                }
            val logs = sources.replica.rows("gut_training_log").map {
                FuelPlan.Log(it.uid, it.str("planned_workout_uid"), it.str("date"), it.int("comfort"),
                    (it.fields["carbs_g"] as? JsonPrimitive)?.doubleOrNull,
                    (it.fields["duration_min"] as? JsonPrimitive)?.doubleOrNull)
            }
            FuelPlan.gutTraining(tg.carbsGPerH, race, workouts, logs)
        }.orEmpty()
        return Fuel(tg, timeline, all.values.toList(), gut)
    }

    /**
     * Use a saved map track as the course — `course_from_track` on the
     * server: the geometry becomes GPX and goes through the same parser as a
     * file, so a drawn course and an uploaded one are the same thing.
     */
    suspend fun importTrack(goal: TrainingGoal, trackUid: String): Boolean {
        val row = sources.replica.rows("track").firstOrNull { it.uid == trackUid } ?: return false
        val pts = (row.fields["geometry"] as? JsonArray).orEmpty().mapNotNull { e ->
            val a = e as? JsonArray ?: return@mapNotNull null
            if (a.size < 2) return@mapNotNull null
            val lon = (a[0] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val lat = (a[1] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val ele = (a.getOrNull(2) as? JsonPrimitive)?.takeIf { it.doubleOrNull != null }?.content
            "<trkpt lat=\"$lat\" lon=\"$lon\">" + (ele?.let { "<ele>$it</ele>" } ?: "") + "</trkpt>"
        }
        if (pts.size < 2) return false
        val gpx = "<?xml version=\"1.0\"?><gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">" +
            "<trk><trkseg>${pts.joinToString("")}</trkseg></trk></gpx>"
        return importCourse(goal, gpx)
    }

    /** How a fuelled long session went — the log the gut-training ramp adapts from. */
    suspend fun logGut(plannedWorkoutUid: String?, date: String, durationMin: Int?, carbsG: Double, comfort: Int, notes: String?) {
        sources.createValues("gut_training_log", mapOf(
            "planned_workout_uid" to plannedWorkoutUid, "date" to date, "duration_min" to durationMin,
            "carbs_g" to carbsG, "comfort" to comfort, "notes" to notes,
        ))
    }

    /**
     * Load a GPX course into the goal's race plan — `upload_course_gpx`, with
     * no server: the segments and sampled path the server would store, the
     * technicality they imply, the start as the weather pin, and the stale
     * lap paces and watch file cleared. False when the file has no course.
     */
    suspend fun importCourse(goal: TrainingGoal, gpx: String): Boolean {
        val segments = GpxCourse.segments(gpx)
        if (segments.isEmpty()) return false
        val path = GpxCourse.path(gpx)
        val (factor, label) = RacePredictor.technicalityFactor(segments)
        val values = linkedMapOf<String, Any?>(
            "course_segments" to segments.map {
                linkedMapOf("distance_m" to it.distanceM, "elevation_gain_m" to it.elevationGainM, "gradient" to it.gradient)
            },
            "course_path" to path.map { listOf(it.lat, it.lon, it.ele) },
            "technicality_factor" to factor,
            "technicality_label" to label,
            "lap_paces" to null,
            "watch_filename" to null,
            "watch_uploaded_at" to null,
        )
        path.firstOrNull()?.let { values["pin_lat"] = it.lat; values["pin_lon"] = it.lon }
        for ((k, v) in values) set(goal, k, v)
        return true
    }

    /** `delete_course_gpx`: back to the terrain setting. */
    suspend fun removeCourse(goal: TrainingGoal) {
        for (k in listOf("course_segments", "course_path", "technicality_factor", "technicality_label", "lap_paces")) {
            set(goal, k, null)
        }
    }

    /** Set one strategy field, creating the goal's race plan on first use. */
    suspend fun set(goal: TrainingGoal, field: String, value: Any?) {
        val existing = row(goal)
        if (existing == null) {
            sources.createValues("race_plan", mapOf("goal_uid" to goal.uid, field to value))
        } else {
            sources.replica.edit("race_plan", existing.uid, mapOf(field to LocalJson.of(value)))
            sources.changed()
        }
    }

    /** The VDOT the goal's training plan was built at — the running model's one input. */
    suspend fun vdot(goal: TrainingGoal): Double? = sources.replica.rows("plan")
        .firstOrNull { it.str("goal_uid") == goal.uid }
        ?.let { (it.fields["vdot"] as? JsonPrimitive)?.doubleOrNull }

    /**
     * The marathon's training indices — 8-week weekly distance and mean pace
     * (race_predictor/running.py) — from this phone's runs, for [running].
     */
    suspend fun trainingIndices(today: CivilDate): Pair<Double, Double>? =
        LocalRunningEvidence(sources, sources.library).trainingIndices(today)

    /** Max HR for the per-lap ceilings: manual, else auto from history, as race_helpers.py reads it. */
    suspend fun maxHr(): Int? = sources.importThresholds().maxHr?.toInt()

    /**
     * A running event's plan as the Race Plans screen works it out, with the
     * course path it was built from — what the guided race on race day runs
     * (RaceGuide). Null for any goal the screen would show no pacing for.
     */
    suspend fun runningPlan(goal: TrainingGoal, imperial: Boolean, today: CivilDate): Pair<Prediction, Strategy>? {
        if ((goal.eventSport ?: "running") != "running") return null
        val dist = goal.eventDistanceMeters?.takeIf { it > 0 } ?: return null
        val vdot = vdot(goal) ?: return null
        val s = strategy(goal)
        val pred = running(vdot, dist, s.courseType, s.splitSpread, imperial, null, s.segments, s.useGpxDistance,
            runCatching { trainingIndices(today) }.getOrNull())
        return pred to s
    }

    private suspend fun row(goal: TrainingGoal): SyncedRow? =
        sources.replica.rows("race_plan").firstOrNull { it.str("goal_uid") == goal.uid }

    companion object {
        /** `course_factors` in race_plan.py: terrain costs time even with no course file. */
        val COURSE_FACTORS = mapOf("flat" to 1.0, "rolling" to 1.015, "hilly" to 1.04, "mountainous" to 1.08)

        /**
         * A running race on a neutral day: the VDOT prediction — corrected by
         * training volume in the marathon when [indices] are given
         * ([trainingIndices], as the server's race plan does) — scaled by
         * terrain, then split into laps (a mile when [imperial], as the server
         * does) with the chosen spread and, when [maxHr] is given, HR ceilings.
         */
        fun running(
            vdot: Double,
            distanceM: Double,
            courseType: String,
            splitSpread: Double,
            imperial: Boolean,
            maxHr: Int?,
            segments: List<Segment> = emptyList(),
            useGpxDistance: Boolean = false,
            indices: Pair<Double, Double>? = null,
        ): Prediction {
            // The server's order: a loaded course sets the distance (when the
            // plan says to use it) and replaces the terrain factor with the
            // course's own technicality; its segments then shape the laps.
            val distance = if (useGpxDistance && segments.isNotEmpty())
                RacePredictor.courseTotals(segments).distanceM.toDouble() else distanceM
            val terrain = if (segments.isNotEmpty()) RacePredictor.technicalityFactor(segments).first
            else COURSE_FACTORS[courseType] ?: 1.0
            val flat = RacePredictor.predictRunningRaceSec(vdot, distance, indices) * terrain
            val (laps, actual) = RacePredictor.lapPaces(
                predictedSec = flat,
                distanceM = distance,
                splitSpread = splitSpread,
                lapKm = if (imperial) 1.60934 else 1.0,
                segments = segments.ifEmpty { null },
                maxHr = maxHr,
                // A course is paced by its terrain, not by the kilometre —
                // the server's terrain=True; see com.tracks.core.race.Terrain.
                terrain = true,
            )
            return Prediction(actual, laps)
        }
    }
}
