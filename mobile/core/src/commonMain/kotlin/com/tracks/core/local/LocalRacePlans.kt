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
import com.tracks.core.race.SportPlan
import com.tracks.core.race.TriCourse
import com.tracks.core.plan.PlanBase
import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Race plans on the phone: the strategy somebody chose for an event, and the
 * pacing it implies.
 *
 * The strategy (terrain, split, pace-or-HR targets) is a `race_plan` row and
 * syncs. The prediction and the per-lap paces are derived from it with the
 * ported race predictor, every time, as everything derived is — so they need
 * no server and cannot disagree between devices about inputs they share.
 *
 * The fitness they are derived from is race day's, as on the server
 * ([LocalRaceDay], com.tracks.core.plan.RaceDay): today's running fitness
 * plus the plan's gain still to come at the rate it has been done, and race
 * morning's form after the plan's remaining sessions. It used to be the VDOT
 * the plan was last built at, with no freshness, where the server read
 * today's estimate and today's form — two predictions of one race.
 *
 * ## What the phone's prediction leaves out
 *
 * The server's `POST /race-plan/generate` also scales by a weather forecast
 * it fetches; the phone predicts a still, mild day.
 */
class LocalRacePlans(private val sources: LocalSources) {

    data class Strategy(
        val uid: String?,
        val courseType: String = "flat",
        val splitSpread: Double = 0.0,
        /** How hard to race against the prediction, 0.80–1.20 (SportPlan.Inputs.intensity). */
        val intensity: Double = 1.0,
        val paceHrMode: String = "pace",
        val hasCourse: Boolean = false,
        val watchUploadedAt: String? = null,
        /** The loaded course's ~100 m segments; empty when none is loaded. */
        val segments: List<Segment> = emptyList(),
        /** The loaded course's sampled path as (lat, lon), for its map preview; empty when none. */
        val path: List<Pair<Double, Double>> = emptyList(),
        /** The elevation at each point of [path], null where the GPX had none: the course profile's hills. */
        val elevations: List<Double?> = emptyList(),
        val useGpxDistance: Boolean = false,
        /** The user's fuelling overrides; null = the calculated default. */
        val fuelCarbs: Int? = null,
        val fuelFluid: Int? = null,
        val fuelSodium: Int? = null,
        val fuelPotassium: Int? = null,
        /** The homemade mix's recipe (null = by carb rate) and bottle (null = 750 ml). */
        val fuelMixPreset: String? = null,
        val fuelMixBottleMl: Int? = null,
        /** A triathlon's own leg files, each winning over its stretch of the whole course (TriCourse). */
        val triLegFiles: Map<String, TriCourse.LegFile> = emptyMap(),
        /** The user's T1/T2 along the whole course; null = the automatic split. */
        val triTransitionsM: List<Double>? = null,
        /** The race's leg distances, when set; null = the standard split for its distance. */
        val triSplits: SportPlan.TriSplits? = null,
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
        /** The homemade mix's recipe when it is carried, else null. */
        val homemade: FuelPlan.Recipe? = null,
    )

    data class Prediction(
        val seconds: Double,
        val laps: List<RaceLap>,
        /** A triathlon's legs, swim to run; empty for every other sport. */
        val legs: List<SportPlan.Leg> = emptyList(),
        /** Cycling and MTB: the race's average HR ceiling. */
        val targetHrCeiling: Int? = null,
        /** A hill climb's W/kg to aim for. */
        val climbTargetWPerKg: Double? = null,
        /** The sport family it was planned as (PlanBase.sportFamily). */
        val family: String = "running",
        /** What the race-day finish is made of, and today's beside it (SportPlan.projection). */
        val projection: SportPlan.Projection? = null,
    )

    suspend fun strategy(goal: TrainingGoal): Strategy {
        // When the race reached the watch is its race-day session's mark: the
        // race rides the calendar (RaceDayWorkout). The plan's own field is
        // from when it was a file of its own, read only where no session is.
        val sessionUploadedAt = raceWorkoutId(goal)?.let { sources.row("planned_workout", it)?.str("watch_uploaded_at") }
        val row = row(goal) ?: return Strategy(uid = null, watchUploadedAt = sessionUploadedAt)
        return Strategy(
            uid = row.uid,
            courseType = row.str("course_type") ?: "flat",
            splitSpread = (row.fields["split_spread"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            intensity = SportPlan.intensityOf((row.fields["intensity"] as? JsonPrimitive)?.doubleOrNull),
            paceHrMode = row.str("pace_hr_mode") ?: "pace",
            hasCourse = row.fields["course_segments"].let { it != null && it.toString() != "null" && it.toString() != "[]" },
            watchUploadedAt = sessionUploadedAt ?: row.str("watch_uploaded_at"),
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
            // Read point for point with [path] above, and skipping the same
            // malformed points, so the two lists stay aligned.
            elevations = (row.fields["course_path"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonArray ?: return@mapNotNull null
                if ((p.getOrNull(0) as? JsonPrimitive)?.doubleOrNull == null) return@mapNotNull null
                if ((p.getOrNull(1) as? JsonPrimitive)?.doubleOrNull == null) return@mapNotNull null
                listOf((p.getOrNull(2) as? JsonPrimitive)?.doubleOrNull)
            }.map { it.first() },
            useGpxDistance = (row.fields["use_gpx_distance"] as? JsonPrimitive)?.booleanOrNull == true,
            fuelCarbs = row.int("fuel_carbs_per_hour"),
            fuelFluid = row.int("fuel_fluid_ml_per_hour"),
            fuelSodium = row.int("fuel_sodium_mg_per_hour"),
            fuelPotassium = row.int("fuel_potassium_mg_per_hour"),
            fuelInterval = row.int("fuel_interval_min"),
            fuelProductUids = (row.fields["fuel_product_uids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            triLegFiles = (row.fields["tri_leg_courses"] as? JsonObject).orEmpty().mapNotNull { (leg, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                leg to TriCourse.LegFile(segmentsOf(o["segments"]), pathOf(o["path"]))
            }.toMap(),
            triTransitionsM = (row.fields["tri_transitions_m"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 },
            triSplits = (row.fields["triathlon_splits_m"] as? JsonObject)?.let { o ->
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                val sw = d("swim_m"); val bk = d("bike_m"); val rn = d("run_m")
                if (sw != null && bk != null && rn != null) SportPlan.TriSplits(sw, bk, rn) else null
            },
            fuelMixPreset = row.str("fuel_mix_preset"),
            fuelMixBottleMl = row.int("fuel_mix_bottle_ml"),
        )
    }

    private fun segmentsOf(e: kotlinx.serialization.json.JsonElement?): List<Segment> =
        (e as? JsonArray).orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            Segment(
                distanceM = (o["distance_m"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                gradient = (o["gradient"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                elevationGainM = (o["elevation_gain_m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }

    private fun pathOf(e: kotlinx.serialization.json.JsonElement?): List<TriCourse.PathPoint> =
        (e as? JsonArray).orEmpty().mapNotNull { x ->
            val p = x as? JsonArray ?: return@mapNotNull null
            TriCourse.PathPoint(
                (p.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                (p.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                (p.getOrNull(2) as? JsonPrimitive)?.doubleOrNull,
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
            potassiumMg = it.int("potassium_mg") ?: 0,
        )
    }.sortedBy { (it.name ?: "").lowercase() }

    /** A product as the user types it in, before it has a uid. */
    data class ProductDraft(
        val name: String,
        val kind: String = "gel",
        val carbsG: Double = 0.0,
        val sodiumMg: Int = 0,
        val potassiumMg: Int = 0,
        val caffeineMg: Int = 0,
        val fluidMl: Int = 0,
    )

    suspend fun addProduct(p: ProductDraft) {
        sources.createValues("fuel_product", mapOf(
            "name" to p.name, "kind" to p.kind, "carbs_g" to p.carbsG, "sodium_mg" to p.sodiumMg,
            "potassium_mg" to p.potassiumMg, "caffeine_mg" to p.caffeineMg, "fluid_ml" to p.fluidMl,
        ))
    }

    /**
     * The fuel plan for [goal] — `fuel_plan_for` in backend/app/api/fuel.py,
     * worked out here from synced rows: the same targets, timeline and
     * gut-training ramp the web shows.
     */
    suspend fun fuel(goal: TrainingGoal, prediction: Prediction?): Fuel =
        fuel(goal, strategy(goal), prediction, fuelInputs(goal))

    /** The rows a fuel plan is worked out from: the products, and the sessions and logs its gut training reads. */
    data class FuelInputs(
        val products: List<FuelPlan.Product>,
        val workouts: List<FuelPlan.Workout>,
        val logs: List<FuelPlan.Log>,
    )

    suspend fun fuelInputs(goal: TrainingGoal): FuelInputs {
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
        return FuelInputs(products(), workouts, logs)
    }

    /** [fuel] from rows already read ([fuelInputs]): no I/O, so it can follow a slider. */
    fun fuel(goal: TrainingGoal, s: Strategy, prediction: Prediction?, inputs: FuelInputs): Fuel {
        val minutes = (prediction?.seconds ?: 0.0) / 60.0
        val tg = FuelPlan.targets(minutes, null, null, s.fuelCarbs, s.fuelFluid, s.fuelSodium, s.fuelInterval, s.fuelPotassium)
        val all = inputs.products.associateBy { it.uid }
        val chosen = s.fuelProductUids.mapNotNull { all[it] }
        val carryMix = FuelPlan.HOMEMADE_UID in s.fuelProductUids
        val timeline = if (minutes > 0) {
            FuelPlan.timeline(minutes, tg, chosen, prediction?.laps?.map { FuelPlan.Lap(it.distanceM.toDouble(), it.targetSecPerKm) }, carryMix)
        } else FuelPlan.Timeline(emptyList(), FuelPlan.Amounts(0, 0, 0, 0), FuelPlan.Amounts(0, 0, 0, 0))
        // fuel_plan_for on the server: the bottles are what the race drinks.
        val homemade = timeline.mix?.let {
            val drunk = timeline.items.filter { i -> i.productUid == FuelPlan.HOMEMADE_UID }.sumOf { i -> i.fluidMl }
            FuelPlan.homemadeRecipe(FuelPlan.mixPreset(tg.carbsGPerH, s.fuelMixPreset),
                s.fuelMixBottleMl?.takeIf { b -> b > 0 } ?: FuelPlan.DEFAULT_BOTTLE_ML, it, tg.carbsGPerH, drunk)
        }
        val gut = goal.eventDate?.let { race -> FuelPlan.gutTraining(tg.carbsGPerH, race, inputs.workouts, inputs.logs) }.orEmpty()
        return Fuel(tg, timeline, all.values.toList(), gut, homemade)
    }

    /**
     * Use a saved map track as the course — `course_from_track` on the
     * server: the geometry becomes GPX and goes through the same parser as a
     * file, so a drawn course and an uploaded one are the same thing.
     */
    suspend fun importTrack(goal: TrainingGoal, trackUid: String, leg: String? = null): Boolean {
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
        return importCourse(goal, gpx, leg)
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
    suspend fun importCourse(goal: TrainingGoal, gpx: String, leg: String? = null): Boolean {
        val segments = GpxCourse.segments(gpx)
        if (segments.isEmpty()) return false
        val path = GpxCourse.path(gpx)
        if (leg != null) {
            // One triathlon leg's own file (`_store_course` with a leg): it
            // wins over its stretch of the whole course.
            require(leg in TriCourse.LEGS) { "leg must be swim, bike or run" }
            val s = strategy(goal)
            val legs = s.triLegFiles + (leg to TriCourse.LegFile(segments, path.map { TriCourse.PathPoint(it.lat, it.lon, it.ele) }))
            set(goal, "tri_leg_courses", legFilesJson(legs))
            if (!s.hasCourse) path.firstOrNull()?.let { set(goal, "pin_lat", it.lat); set(goal, "pin_lon", it.lon) }
            for (k in listOf("lap_paces", "watch_filename", "watch_uploaded_at")) set(goal, k, null)
            return true
        }
        val (factor, label) = RacePredictor.technicalityFactor(segments)
        val values = linkedMapOf<String, Any?>(
            "course_segments" to segments.map {
                linkedMapOf("distance_m" to it.distanceM, "elevation_gain_m" to it.elevationGainM, "gradient" to it.gradient)
            },
            "course_path" to path.map { listOf(it.lat, it.lon, it.ele) },
            "technicality_factor" to factor,
            "technicality_label" to label,
            // A new whole course is split afresh: the old T1/T2 were along another file.
            "tri_transitions_m" to null,
            "lap_paces" to null,
            "watch_filename" to null,
            "watch_uploaded_at" to null,
        )
        path.firstOrNull()?.let { values["pin_lat"] = it.lat; values["pin_lon"] = it.lon }
        for ((k, v) in values) set(goal, k, v)
        return true
    }

    /**
     * `delete_course_gpx`: back to the terrain setting — or, with [leg], one
     * triathlon leg's own file gone, the leg back on its stretch of the whole.
     */
    suspend fun removeCourse(goal: TrainingGoal, leg: String? = null) {
        if (leg != null) {
            set(goal, "tri_leg_courses", legFilesJson(strategy(goal).triLegFiles - leg).takeIf { it.isNotEmpty() })
            set(goal, "lap_paces", null)
            return
        }
        for (k in listOf("course_segments", "course_path", "technicality_factor", "technicality_label",
            "tri_transitions_m", "lap_paces")) {
            set(goal, k, null)
        }
    }

    private fun legFilesJson(legs: Map<String, TriCourse.LegFile>) = legs.mapValues { (_, f) ->
        linkedMapOf(
            "segments" to f.segments.map {
                linkedMapOf("distance_m" to it.distanceM, "elevation_gain_m" to it.elevationGainM, "gradient" to it.gradient)
            },
            "path" to f.path.map { listOf(it.lat, it.lon, it.ele) },
        )
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

    /**
     * The goal card's predicted finish, the server's `get_predicted_time`:
     * race day's VDOT ([LocalRaceDay]) over the plan's course, on a neutral
     * day — no weather or freshness — at the race's intensity. Running only,
     * as the card is. The course's hills are in it
     * ([SportPlan.runningCourseFactor]), so the card and the plan agree on a
     * hilly course; it used to price every race flat.
     */
    suspend fun predictedSeconds(goal: TrainingGoal, today: CivilDate): Double? {
        if (goal.goalType != "event") return null
        val goalM = goal.eventDistanceMeters?.takeIf { it > 0 } ?: return null
        if (PlanBase.sportFamily((goal.eventSport ?: "running").lowercase()) != "running") return null
        val fitness = LocalRaceDay(sources).fitness(goal, today)
        val vdot = fitness.vdot?.takeIf { it != 0.0 } ?: return null
        val s = strategy(goal)
        val distance = if (s.useGpxDistance && s.segments.isNotEmpty())
            RacePredictor.courseTotals(s.segments).distanceM.toDouble() else goalM
        return RacePredictor.predictRunningRaceSec(vdot, distance, fitness.runIndices) /
            SportPlan.intensityOf(s.intensity) * SportPlan.runningCourseFactor(s.segments, s.courseType, distance)
    }

    /**
     * The marathon's training indices — 8-week weekly distance and mean pace
     * (race_predictor/running.py) — from this phone's runs, for [running].
     */
    suspend fun trainingIndices(today: CivilDate): Pair<Double, Double>? =
        LocalRunningEvidence(sources, sources.library).trainingIndices(today)

    /**
     * Any sport's race, as the server's `sport_plan` plans it ([SportPlan]):
     * the athlete's thresholds as `_get_athlete_metrics` reads them, and the
     * course's distance in place of the goal's when the plan says so. Throws
     * [SportPlan.Missing] naming the threshold the sport needs.
     *
     * CSS is the manual one only: the server works its automatic CSS out from
     * swim history, which the phone does not do yet, so an "auto" CSS plans
     * no swim here — the message says to set one.
     */
    suspend fun plan(goal: TrainingGoal, s: Strategy, imperial: Boolean, today: CivilDate): Prediction =
        plan(goal, s, imperial, inputs(goal, today))

    /**
     * What a race's plan reads from the phone's data, apart from its own
     * strategy: the thresholds, race day's fitness and race morning's form.
     * None of it moves with the strategy, so the Race Plans screen reads it
     * once and re-plans from it as a slider moves ([plan] with these is pure
     * arithmetic) — reading it again for every step was what made the
     * intensity slider slow. [raceDay] is shared across a screen's races so
     * the running history behind them is read once (LocalRaceDay).
     */
    data class Inputs(
        val thresholds: ImportThresholds,
        val css: Double?,
        val fitness: LocalRaceDay.Fitness?,
        val form: LocalRaceDay.Form,
    )

    suspend fun inputs(goal: TrainingGoal, today: CivilDate, raceDay: LocalRaceDay = LocalRaceDay(sources)): Inputs {
        val family = PlanBase.sportFamily((goal.eventSport ?: "running").lowercase())
        val t = sources.importThresholds()
        val css = if (sources.setting("css_mode") == "manual") sources.setting("css_manual")?.toDoubleOrNull() else null
        // Race day's running fitness and race morning's form, as the server's
        // `_get_athlete_metrics` and `_race_form` read them.
        val fitness = if (family == "running" || family == "triathlon") raceDay.fitness(goal, today) else null
        val form = raceDay.form(goal, today, fitness?.vdotToday)
        return Inputs(t, css, fitness, form)
    }

    /** [plan] from [Inputs] already read: no I/O, so it can follow a slider. */
    fun plan(goal: TrainingGoal, s: Strategy, imperial: Boolean, inputs: Inputs): Prediction {
        val family = PlanBase.sportFamily((goal.eventSport ?: "running").lowercase())
        val goalM = goal.eventDistanceMeters?.takeIf { it > 0 } ?: throw SportPlan.Missing("Add a distance to predict a finish.")
        val distance = if (s.useGpxDistance && s.segments.isNotEmpty())
            RacePredictor.courseTotals(s.segments).distanceM.toDouble() else goalM
        val (t, css, fitness, form) = inputs
        val r = SportPlan.plan(
            family,
            SportPlan.Metrics(fitness?.vdot, t.ftp, css, t.maxHr?.toInt(), t.thresholdHr?.toInt(), fitness?.runIndices),
            SportPlan.Inputs(
                distanceM = distance, courseType = s.courseType, splitSpread = s.splitSpread,
                paceHr = s.paceHrMode == "pace_hr", imperial = imperial, segments = s.segments,
                mtbDiscipline = goal.mtbDiscipline, cyclingDiscipline = goal.cyclingDiscipline,
                triathlonSplits = s.triSplits,
                path = s.path.zip(s.elevations) { (lat, lon), ele -> TriCourse.PathPoint(lat, lon, ele) },
                triLegFiles = s.triLegFiles, triTransitionsM = s.triTransitionsM,
                intensity = s.intensity,
                freshness = com.tracks.core.plan.RaceDay.freshnessFactor(form.raceDay),
            ),
        )
        val projection = SportPlan.projection(
            family, fitness?.vdot, fitness?.vdotToday, fitness?.adherence, fitness?.runIndices,
            form.today, form.raceDay, r.seconds, distance,
        )
        return Prediction(r.seconds, r.laps, r.legs, r.targetHrCeiling, r.climbTargetWPerKg, family, projection)
    }

    /** Max HR for the per-lap ceilings: manual, else auto from history, as race_helpers.py reads it. */
    suspend fun maxHr(): Int? = sources.importThresholds().maxHr?.toInt()

    /**
     * A running event's plan as the Race Plans screen works it out — [plan]
     * itself, so what the guided race on race day paces (RaceGuide) is what
     * the screen showed, to the second — with the course path it was built
     * from. Null for any goal the screen would show no running pacing for.
     */
    suspend fun runningPlan(goal: TrainingGoal, imperial: Boolean, today: CivilDate): Pair<Prediction, Strategy>? {
        if (PlanBase.sportFamily((goal.eventSport ?: "running").lowercase()) != "running") return null
        if ((goal.eventDistanceMeters ?: 0.0) <= 0.0) return null
        val s = strategy(goal)
        val pred = try {
            plan(goal, s, imperial, today)
        } catch (_: SportPlan.Missing) {
            return null
        }
        return pred to s
    }

    /**
     * The race behind each race-day entry in [workouts], by workout id, for
     * the schedule bundle to encode it as the race
     * (com.tracks.core.sync.buildScheduleBundle) — the server's
     * `_race_day_fit`: the race plan as the Race Plans screen works it out,
     * with its fuelling reminders, its pace-or-HR choice and max HR. A race
     * this cannot plan (a swim or triathlon, which are paced by leg; no
     * fitness yet) is left out, and keeps the calendar's placeholder.
     */
    suspend fun raceDayWorkouts(
        workouts: List<com.tracks.core.api.PlannedWorkout>,
        today: CivilDate,
    ): Map<Int, com.tracks.core.sync.RaceDayWorkout> {
        val races = workouts.filter { it.workoutType == "race" }
        if (races.isEmpty()) return emptyMap()
        val goals = sources.goals()
        val plans = sources.replica.rows("plan").filter { !it.isTombstone }
        val imperial = sources.setting("units") == "imperial"
        val maxHr = maxHr()
        val out = LinkedHashMap<Int, com.tracks.core.sync.RaceDayWorkout>()
        for (w in races) {
            val planUid = sources.row("planned_workout", w.id)?.str("plan_uid") ?: continue
            val goalUid = plans.firstOrNull { it.uid == planUid }?.str("goal_uid") ?: continue
            val goal = goals.firstOrNull { it.uid == goalUid } ?: continue
            val family = PlanBase.sportFamily((goal.eventSport ?: "running").lowercase())
            if (family == "swimming" || family == "triathlon") continue
            val s = strategy(goal)
            val pred = try {
                plan(goal, s, imperial, today)
            } catch (_: SportPlan.Missing) {
                continue
            }
            if (pred.laps.isEmpty()) continue
            val fuel = runCatching { fuel(goal, pred) }.getOrNull()?.timeline?.items.orEmpty()
            out[w.id] = com.tracks.core.sync.RaceDayWorkout(
                laps = pred.laps.map {
                    com.tracks.core.fit.WorkoutFit.RaceLap(it.lap, it.distanceM.toDouble(), it.targetSecPerKm, it.hrCeiling, it.label)
                },
                family = family,
                hrTargets = s.paceHrMode == "pace_hr",
                maxHr = maxHr,
                fuel = fuel.map { com.tracks.core.fit.WorkoutFit.RaceFuel(it.distanceM, it.name, it.carbsG) },
            )
        }
        return out
    }

    /**
     * The race-day planned workout for [goal] — the plan's `race` session on
     * the event's day — by its local id, for the guided race to open; null
     * when the plan has none (no plan, or one built before races were
     * scheduled). The Race Plans screen's Start button opens it, so the run
     * ticks the race off the plan when it is saved.
     */
    suspend fun raceWorkoutId(goal: TrainingGoal): Int? {
        val planUid = LocalRaceDay(sources).planUid(goal) ?: return null
        val day = goal.eventDate?.take(10) ?: return null
        val row = sources.replica.rows("planned_workout").firstOrNull {
            !it.isTombstone && it.str("plan_uid") == planUid && it.str("workout_type") == "race" &&
                it.str("scheduled_date")?.take(10) == day
        } ?: return null
        return sources.idOf(row.uid)
    }

    /** A race's course on its way to the watch's course list: see [raceCourses]. */
    data class RaceCourse(
        /** Its key in the watch library, [raceCourseRef]: negative, so never a track's. */
        val ref: Int,
        val name: String,
        val sport: String,
        /** (lat, lon, elevation or null) per point, as the race plan keeps its course. */
        val points: List<Triple<Double, Double, Double?>>,
    )

    /**
     * The courses of the races whose race day is on the watch's calendar —
     * from today to [daysAhead] days on, as the calendar reaches — for the
     * watch's course list, where a map shows them while they are followed.
     * A Garmin workout file has no field for a course, so the race day
     * (RaceDayWorkout) and its course travel side by side, and come off
     * together once the race is past. The race plan's course, under the
     * race's name; a triathlon with no whole course sends each leg file of
     * its own. The server's `_race_course_items` offers the same to a cable
     * and the browser.
     */
    suspend fun raceCourses(
        today: CivilDate,
        daysAhead: Int = com.tracks.core.sync.SCHEDULE_DAYS_AHEAD,
    ): List<RaceCourse> {
        val from = today.isoformat()
        val until = CivilDate.fromEpochDay(today.epochDay + daysAhead).isoformat()
        val out = ArrayList<RaceCourse>()
        for (goal in sources.goals()) {
            val day = goal.eventDate?.take(10) ?: continue
            if (goal.goalType != "event" || day < from || day > until) continue
            val s = strategy(goal)
            if (s.uid == null) continue
            val name = goal.eventName?.trim()?.ifEmpty { null } ?: "Race"
            val whole = s.path.zip(s.elevations) { (lat, lon), ele -> Triple(lat, lon, ele) }
            if (whole.size >= 2) {
                out += RaceCourse(raceCourseRef(goal.id, 0), name, (goal.eventSport ?: "running").lowercase(), whole)
                continue
            }
            RACE_LEGS.forEachIndexed { i, (leg, sport) ->
                val path = s.triLegFiles[leg]?.path.orEmpty()
                if (path.size >= 2) {
                    out += RaceCourse(raceCourseRef(goal.id, i + 1), "$name · ${leg.replaceFirstChar { it.uppercase() }}",
                        sport, path.map { Triple(it.lat, it.lon, it.ele) })
                }
            }
        }
        return out
    }

    private suspend fun row(goal: TrainingGoal): SyncedRow? =
        sources.replica.rows("race_plan").firstOrNull { it.str("goal_uid") == goal.uid }

    companion object {
        /** A triathlon's legs as courses: each sent alone where the race has no whole course. */
        private val RACE_LEGS = listOf("swim" to "swimming", "bike" to "cycling", "run" to "running")

        /**
         * A race course's key in the watch library: negative, so it can never
         * be a track's (whose keys are local ids, all positive), and one per
         * leg — 0 the whole course, 1–3 a triathlon's legs.
         */
        fun raceCourseRef(goalId: Int, leg: Int): Int = -(goalId * 4 + leg)

        /** Whether a watch-library key is a race course's ([raceCourseRef]). */
        fun isRaceCourseRef(ref: Int): Boolean = ref < 0

        /** `course_factors` in race_plan.py: terrain costs time even with no course file. */
        val COURSE_FACTORS = mapOf("flat" to 1.0, "rolling" to 1.015, "hilly" to 1.04, "mountainous" to 1.08)

        /**
         * A running race on a neutral day: the VDOT prediction — corrected by
         * training volume in the marathon when [indices] are given
         * ([trainingIndices], as the server's race plan does) — scaled by
         * terrain, then split into laps (a mile when [imperial], as the server
         * does) with the chosen spread and, when [maxHr] is given, HR ceilings.
         * [intensity] is the race's, as SportPlan applies it to running: the
         * speed, so the guided race coaches the plan the screen shows.
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
            intensity: Double = 1.0,
        ): Prediction {
            // The server's order: a loaded course sets the distance (when the
            // plan says to use it) and replaces the terrain factor with the
            // course's own technicality; its segments then shape the laps.
            val distance = if (useGpxDistance && segments.isNotEmpty())
                RacePredictor.courseTotals(segments).distanceM.toDouble() else distanceM
            val terrain = if (segments.isNotEmpty()) RacePredictor.technicalityFactor(segments).first
            else COURSE_FACTORS[courseType] ?: 1.0
            val flat = RacePredictor.predictRunningRaceSec(vdot, distance, indices) / SportPlan.intensityOf(intensity) * terrain
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
