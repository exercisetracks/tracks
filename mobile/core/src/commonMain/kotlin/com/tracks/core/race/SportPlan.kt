// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.cycling.CourseSegment
import com.tracks.core.cycling.Mtb
import com.tracks.core.cycling.RoadCycling
import com.tracks.core.parse.PyMath

/**
 * Every sport's race plan, as the server's `race_plan.sport_plan` works it
 * out, held to it case by case by spec/fixtures/race_plan.json
 * (SportPlanFixtureTest). The phone used to plan running alone and say
 * "Pacing for this sport is on the web" for the rest.
 *
 * No forecast: the phone has no weather, which is the one thing the web's
 * plan adds. Race-morning freshness ([Inputs.freshness],
 * com.tracks.core.plan.RaceDay) is applied as the server applies it, in
 * every sport — the phone used to leave it out, so the two predicted the
 * same race up to 3% apart. See the Python for each sport's model.
 *
 * [Inputs.intensity] is the race-intensity slider, applied as the server's
 * sport_plan applies it: speed on foot and in the water, power on a bike,
 * with each sport's targets and finish following.
 */
object SportPlan {

    /** What the athlete brings: each null when not measured. */
    data class Metrics(
        val vdot: Double? = null,
        val ftp: Double? = null,
        val css: Double? = null,
        val maxHr: Int? = null,
        val lthr: Int? = null,
        /** The marathon's training indices (running only). */
        val runIndices: Pair<Double, Double>? = null,
    )

    data class Inputs(
        val distanceM: Double,
        val courseType: String = "flat",
        val splitSpread: Double = 0.0,
        val paceHr: Boolean = false,
        val imperial: Boolean = false,
        val segments: List<Segment> = emptyList(),
        val mtbDiscipline: String? = null,
        val cyclingDiscipline: String? = null,
        val triathlonSplits: TriSplits? = null,
        /** The whole course's sampled path, which a triathlon's split reads. */
        val path: List<TriCourse.PathPoint> = emptyList(),
        /** A triathlon's own leg files, each winning over its stretch of the whole course. */
        val triLegFiles: Map<String, TriCourse.LegFile> = emptyMap(),
        /** The user's T1/T2 along the whole course; null = the automatic split. */
        val triTransitionsM: List<Double>? = null,
        /**
         * How hard to race against the prediction (the race plan's
         * `intensity`): 1.0 as predicted, 1.05 five per cent faster on foot
         * or in the water and five per cent more power on a bike. Held to
         * [INTENSITY_RANGE].
         */
        val intensity: Double = 1.0,
        /**
         * Race-morning freshness as a time multiplier (RaceDay.freshnessFactor):
         * below 1 when fresh. VDOT and FTP are raised by its complement, CSS
         * and the marathon's volume half scaled by it, as `sport_plan` does.
         */
        val freshness: Double = 1.0,
    )

    data class TriSplits(val swimM: Double, val bikeM: Double, val runM: Double)

    data class Leg(
        val leg: String,
        val distanceM: Double,
        val seconds: Double,
        val targetPace: String?,
        val targetWatts: Int?,
        val targetWattsPctFtp: Int?,
        val laps: List<RaceLap>?,
        /** The swim leg's CSS pace in sec/km, which its graph draws per 100 m; null for the others. */
        val swimSecPerKm: Double? = null,
    )

    data class Result(
        val laps: List<RaceLap>,
        val seconds: Double,
        val legs: List<Leg> = emptyList(),
        val triathlonSplits: TriSplits? = null,
        val technicalityFactor: Double? = null,
        val technicalityLabel: String? = null,
        val targetHrCeiling: Int? = null,
        val climbTargetWPerKg: Double? = null,
    )

    /** Why a sport could not be planned: the threshold it needs is missing. */
    class Missing(message: String) : Exception(message)

    /**
     * A terrain type chosen without a course file — `COURSE_FACTORS` in
     * race_plan.py, which has why these stand beside the grade model.
     */
    private val COURSE_FACTORS = mapOf("flat" to 1.0, "rolling" to 1.015, "hilly" to 1.04, "mountainous" to 1.08)
    private val CY_COURSE_FACTORS = mapOf("flat" to 1.0, "rolling" to 1.008, "hilly" to 1.02, "mountainous" to 1.04)
    private val MTB_COURSE_FACTORS = mapOf("flat" to 1.0, "rolling" to 1.04, "hilly" to 1.10, "mountainous" to 1.20)
    private val MTB_SPEED_KMH = mapOf("xco" to 22.0, "xcm" to 18.0, "enduro" to 12.0, "trail" to 16.0)

    /** `_TRI_SPLITS` in race_helpers.py: the standard distances, nearest wins. */
    private val TRI_SPLITS = listOf(
        12_950.0 to TriSplits(400.0, 10_000.0, 2_500.0),
        25_750.0 to TriSplits(750.0, 20_000.0, 5_000.0),
        51_500.0 to TriSplits(1_500.0, 40_000.0, 10_000.0),
        113_000.0 to TriSplits(1_900.0, 90_000.0, 21_097.0),
        226_000.0 to TriSplits(3_800.0, 180_000.0, 42_195.0),
    )
    /**
     * `running_course_factor`: what the course does to a running finish on a
     * neutral day — its technicality and its ground with a course
     * ([Terrain.courseGradeFactor], the finish [RacePredictor.lapPaces]
     * gives), else its terrain type. The goal card's badge, so it prices the
     * hills the plan does.
     */
    fun runningCourseFactor(segments: List<Segment>, courseType: String?, distanceM: Double): Double =
        if (segments.isNotEmpty()) {
            RacePredictor.technicalityFactor(segments).first * Terrain.courseGradeFactor(segments, distanceM)
        } else {
            COURSE_FACTORS[courseType] ?: 1.0
        }

    /** The slider's ends, `RACE_INTENSITY_MIN/MAX` in race_helpers.py. */
    val INTENSITY_RANGE = 0.8..1.2

    /** A synced intensity as the planner uses it: 1.0 when unset, clamped to [INTENSITY_RANGE]. */
    fun intensityOf(v: Double?): Double = v?.coerceIn(INTENSITY_RANGE) ?: 1.0

    private const val T1_SEC = 5 * 60
    private const val T2_SEC = 3 * 60

    fun inferTriathlonSplits(totalM: Double): TriSplits = TRI_SPLITS.minBy { kotlin.math.abs(it.first - totalM) }.second

    /**
     * What a race-day prediction is made of (`race_plan.race_projection`):
     * fitness today and on race day, the share of the plan done, the form,
     * and — for a running race, whose whole time follows VDOT — the finish at
     * today's fitness and form. Null for a sport VDOT does not predict.
     */
    data class Projection(
        val vdotToday: Double,
        val vdotRaceDay: Double,
        val adherence: Double?,
        val tsbToday: Double?,
        val tsbRaceDay: Double?,
        val todaySeconds: Double?,
        val todayTime: String?,
    )

    /**
     * `race_projection`. [actualSec] is the race-day plan's finish; today's is
     * it rescaled by the flat prediction's ratio, since intensity, weather and
     * course multiply both alike.
     */
    fun projection(
        family: String, vdot: Double?, vdotToday: Double?, adherence: Double?, runIndices: Pair<Double, Double>?,
        tsbToday: Double?, tsbRaceDay: Double?, actualSec: Double, distanceM: Double,
    ): Projection? {
        if (vdot == null || (family != "running" && family != "triathlon")) return null
        val today = vdotToday?.takeIf { it != 0.0 } ?: vdot
        var todaySec: Double? = null
        if (family == "running") {
            fun flat(v: Double, f: Double) = RacePredictor.predictRunningRaceSec(v * (2.0 - f), distanceM, runIndices, f)
            todaySec = PyMath.round(
                actualSec * flat(today, com.tracks.core.plan.RaceDay.freshnessFactor(tsbToday)) /
                    flat(vdot, com.tracks.core.plan.RaceDay.freshnessFactor(tsbRaceDay)), 1,
            )
        }
        return Projection(
            PyMath.round(today, 1), PyMath.round(vdot, 1), adherence?.let { PyMath.round(it, 2) },
            tsbToday, tsbRaceDay, todaySec, todaySec?.let(RacePredictor::formatTime),
        )
    }

    /** The plan for [family] (PlanBase.sportFamily). Throws [Missing] when its threshold is not set. */
    fun plan(family: String, m: Metrics, i: Inputs): Result {
        val lapKm = if (i.imperial) 1.60934 else 1.0
        val segs = i.segments.ifEmpty { null }
        val maxHr = m.maxHr.takeIf { i.paceHr }
        val k = intensityOf(i.intensity)
        val f = i.freshness
        return when (family) {
            "running" -> {
                val vdot = m.vdot ?: throw Missing("Record a few runs to predict a finish.")
                // Speed, not VDOT: the marathon's time is half the volume
                // model, which a VDOT change would not reach (sport_plan).
                var flat = RacePredictor.predictRunningRaceSec(vdot * (2.0 - f), i.distanceM, m.runIndices, f) / k
                var tech: Pair<Double, String>? = null
                if (segs != null) {
                    tech = RacePredictor.technicalityFactor(segs)
                    flat *= tech.first
                } else {
                    flat *= COURSE_FACTORS[i.courseType] ?: 1.0
                }
                val (laps, sec) = RacePredictor.lapPaces(flat, i.distanceM, i.splitSpread, lapKm, segs, maxHr, terrain = true)
                Result(laps, sec, technicalityFactor = tech?.first, technicalityLabel = tech?.second)
            }
            "mountain_biking" -> mtb(m, i, lapKm, segs, maxHr, k, f)
            "cycling" -> cycling(m, i, lapKm, segs, maxHr, k, f)
            "swimming" -> {
                // The pace raced: CSS, quickened or eased by the intensity.
                val css = (m.css ?: throw Missing("Set your CSS in Settings to plan a swim.")) / k
                val sec = RacePredictor.predictSwimTimeSec(css * f, i.distanceM, i.distanceM > 1000)
                val pace = RacePredictor.formatSwimPace(css)
                val perKm = PyMath.roundToLong(css * 10).toDouble()
                val lap = RaceLap(
                    lap = 1, distanceM = PyMath.roundToLong(i.distanceM), targetSecPerKm = perKm, targetPace = pace,
                    gradient = 0.0, gradeMultiplier = 1.0, gradeAdjSec = perKm, gradeAdjPace = pace,
                    cumulativeKm = PyMath.round(i.distanceM / 1000, 2), hrCeiling = null,
                )
                Result(listOf(lap), sec)
            }
            "triathlon" -> triathlon(m, i, lapKm, maxHr, k, f)
            else -> {
                val vdot = m.vdot ?: throw Missing("Record a few runs to predict a finish.")
                val flat = RacePredictor.predictRaceTimeSec(vdot * (2.0 - f), i.distanceM) / k * (COURSE_FACTORS[i.courseType] ?: 1.0)
                val (laps, sec) = RacePredictor.lapPaces(flat, i.distanceM, i.splitSpread, lapKm, maxHr = maxHr)
                Result(laps, sec)
            }
        }
    }

    private fun cycling(m: Metrics, i: Inputs, lapKm: Double, segs: List<Segment>?, maxHr: Int?, k: Double, f: Double): Result {
        val ftp = m.ftp ?: throw Missing("Set your FTP in Settings to plan a ride.")
        val discipline = (i.cyclingDiscipline ?: "road_race").lowercase()
        // Drafting as a power multiplier: the rider's watts carry them as far
        // as ftp / df would solo. Watts are scaled back down for the targets,
        // and their share of FTP is of the real one, which the watch holds.
        val df = RoadCycling.draftingFactor(discipline)
        val ftpEffective = ftp / df * (2.0 - f) * k
        var base = RacePredictor.predictCyclingTimeSec(ftpEffective, i.distanceM, segs)
        var tech: Pair<Double, String>? = null
        if (segs == null) base *= CY_COURSE_FACTORS[i.courseType] ?: 1.0 else tech = RacePredictor.technicalityFactor(segs)
        val (raw, sec) = RacePredictor.cyclingLapTargets(ftpEffective, i.distanceM, i.splitSpread, lapKm, segs, maxHr, base, 0.0)
        val hr = RoadCycling.raceHrCeiling(discipline, m.lthr)
        val laps = raw.map { lap ->
            val watts = lap.targetWatts?.takeIf { it != 0 }?.let { PyMath.roundToLong(it * df).toInt() }
            lap.copy(
                targetWatts = watts ?: lap.targetWatts,
                targetWattsPctFtp = watts?.let { PyMath.roundToLong(it / ftp * 100).toInt() } ?: lap.targetWattsPctFtp,
                gradeMultiplier = PyMath.round(RoadCycling.gradeMultiplier(lap.gradient), 4),
                hrCeiling = hr ?: lap.hrCeiling,
            )
        }
        return Result(
            laps, sec, technicalityFactor = tech?.first, technicalityLabel = tech?.second, targetHrCeiling = hr,
            climbTargetWPerKg = if (discipline == "hill_climb") RoadCycling.hillClimbTargetWPerKg(sec) else null,
        )
    }

    private fun mtb(m: Metrics, i: Inputs, lapKm: Double, segs: List<Segment>?, maxHr: Int?, k: Double, f: Double): Result {
        val discipline = (i.mtbDiscipline ?: "trail").lowercase()
        val ftp = m.ftp?.let { it * (2.0 - f) * k }
        var base = if (ftp != null) {
            RacePredictor.predictCyclingTimeSec(ftp, i.distanceM, segs)
        } else {
            i.distanceM / ((MTB_SPEED_KMH[discipline] ?: 16.0) * 1000 / 3600) / k
        }
        var tech: Pair<Double, String>? = null
        if (segs == null) base *= MTB_COURSE_FACTORS[i.courseType] ?: 1.10 else tech = RacePredictor.technicalityFactor(segs)
        val hr = Mtb.raceHrCeiling(discipline, m.lthr)
        val (laps, sec) = if (ftp != null) {
            val (raw, s) = RacePredictor.cyclingLapTargets(ftp, i.distanceM, i.splitSpread, lapKm, segs, maxHr, base, 0.0)
            raw.map { it.copy(hrCeiling = hr ?: it.hrCeiling, gradeMultiplier = PyMath.round(Mtb.gradeMultiplier(it.gradient), 4)) } to s
        } else {
            val (raw, s) = Mtb.hrOnlyLaps(
                i.distanceM, base, lapKm, i.splitSpread, discipline, m.lthr,
                segs?.map { CourseSegment(it.distanceM, it.gradient) }, i.courseType,
            )
            raw.map {
                RaceLap(it.lap, it.distanceM, it.targetSecPerKm, it.targetPace, it.gradient, it.gradeMultiplier,
                    it.gradeAdjSec, it.gradeAdjPace, it.cumulativeKm, it.hrCeiling)
            } to s
        }
        return Result(laps, sec, technicalityFactor = tech?.first, technicalityLabel = tech?.second, targetHrCeiling = hr)
    }

    private fun triathlon(m: Metrics, i: Inputs, lapKm: Double, maxHr: Int?, k: Double, f: Double): Result {
        val splits = i.triathlonSplits ?: inferTriathlonSplits(i.distanceM)
        // Each leg's course — its own file, else its stretch of the whole —
        // sets its length and shapes its targets (TriCourse).
        val courses = TriCourse.legCourses(i.segments, i.path, i.triLegFiles, i.triTransitionsM, splits).legs
        fun segsOf(leg: String) = courses[leg]?.segments?.ifEmpty { null }
        val swimSegs = segsOf("swim")
        val bikeSegs = segsOf("bike")
        val runSegs = segsOf("run")
        val swimM = swimSegs?.let(TriCourse::totalM) ?: splits.swimM
        val bikeM = bikeSegs?.let(TriCourse::totalM) ?: splits.bikeM
        val runM = runSegs?.let(TriCourse::totalM) ?: splits.runM
        val legs = mutableListOf<Leg>()
        var total = 0.0
        m.css?.let { it / k }?.let { css ->
            val sec = RacePredictor.predictSwimTimeSec(css * f, swimM, openWater = true)
            legs += Leg("swim", swimM, sec, RacePredictor.formatSwimPace(css), null, null, null, css * 10)
            total += sec + T1_SEC
        }
        m.ftp?.let { ftp ->
            val raced = ftp * (2.0 - f) * k
            var sec = RacePredictor.predictCyclingTimeSec(raced, bikeM, bikeSegs)
            // Its terrain type, as a ride's, where the leg has no course.
            if (bikeSegs == null) sec *= CY_COURSE_FACTORS[i.courseType] ?: 1.0
            val kph = bikeM / sec * 3.6
            val (raw, _) = RacePredictor.cyclingLapTargets(raced, bikeM, i.splitSpread, lapKm, bikeSegs, maxHr, sec)
            val laps = if (bikeSegs != null) {
                raw.map { it.copy(gradeMultiplier = PyMath.round(RoadCycling.gradeMultiplier(it.gradient), 4)) }
            } else raw
            val watts = PyMath.roundToLong(raced * 0.85).toInt()
            legs += Leg("bike", bikeM, sec, "${PyMath.round(kph, 1)} km/h avg", watts,
                PyMath.roundToLong(watts / ftp * 100).toInt(), laps)
            total += sec + T2_SEC
        }
        m.vdot?.let { vdot ->
            // About 5% slower for the swim and the bike before it.
            var sec = RacePredictor.predictRaceTimeSec(vdot * (2.0 - f) * 0.95, runM) / k
            if (runSegs != null) sec *= RacePredictor.technicalityFactor(runSegs).first
            else sec *= COURSE_FACTORS[i.courseType] ?: 1.0
            val (laps, courseSec) = RacePredictor.lapPaces(sec, runM, i.splitSpread, lapKm, runSegs, maxHr, terrain = runSegs != null)
            // The leg's time is the one its laps are paced to, hills in (sport_plan).
            if (runSegs != null) sec = courseSec
            legs += Leg("run", runM, sec, laps.firstOrNull()?.targetPace, null, null, laps)
            total += sec
        }
        if (legs.isEmpty()) throw Missing("Set your FTP or CSS, or record a few runs, to plan a triathlon.")
        return Result(legs.firstNotNullOfOrNull { it.laps }.orEmpty(), total, legs, splits)
    }
}
