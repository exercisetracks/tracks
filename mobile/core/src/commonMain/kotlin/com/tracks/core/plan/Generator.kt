// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanBase.DEFAULT_RUN_PACES
import com.tracks.core.plan.PlanBase.DEFAULT_VDOT
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * What the endurance planner reads off a training goal. Field names follow
 * `training_goals`; `eventDistanceMeters` stays a [Double] because the column
 * is a float, and a race-day workout carries it as the server stored it.
 */
data class PlanGoal(
    val eventDate: CivilDate?,
    val eventSport: String? = null,
    val eventDistanceMeters: Double? = null,
    val daysPerWeek: Long? = null,
    val planIntensity: Double? = null,
    val mtbDiscipline: String? = null,
    val cyclingDiscipline: String? = null,
    /** A fitness goal's CTL change per week; read only by [FitnessPlan]. */
    val ctlRampPerWeek: Double? = null,
    /** A fitness goal's sports (`fitness_sports`); read only by [FitnessPlan]. */
    val fitnessSports: List<String>? = null,
)

/**
 * The endurance plan generator — a port of
 * `backend/app/calculators/plan/generator/` (dispatch, templates, week, plan,
 * metrics), keeping its control flow and arithmetic order so that a plan
 * generated on the phone is the plan the server generates.
 */
object PlanGenerator {

    /**
     * dispatch.py's sets. Plyometrics, eccentric and agility work, descents,
     * hangboarding and limit bouldering are in neither on purpose — see there.
     */
    val LOW_INTENSITY_TYPES = setOf(
        "easy", "easy_recovery", "easy_spin", "endurance", "aerobic", "skills", "rest",
        "technique", "ut2", "ut1", "vert", "pole_hike", "arc", "back_to_back",
    )

    val HIGH_INTENSITY_TYPES = setOf(
        "intervals", "tempo", "race_pace", "fartlek", "short_quality",
        "sweet_spot", "threshold", "vo2", "micro_bursts", "over_unders",
        "matchbook", "standing_starts", "descent_repeats",
        "sustained_climb", "tt_pace", "sprint", "anaerobic",
        "quality",
        "css", "incline_intervals", "bounding", "ski_intervals", "power_endurance",
    )

    /** Damsted et al. (2018): 10% weekly volume increase. */
    private const val WEEKLY_GAIN_VOLUME = 0.10

    // ── Templates ────────────────────────────────────────────────────────────

    /** `_rotating_template`: the week's day plan, rotated by week for variety. */
    fun rotatingTemplate(
        family: String, phase: String, weekNum: Long, mtbDiscipline: String, cyclingDiscipline: String,
    ): List<String> {
        val templates = when (family) {
            "mountain_biking" -> PlanBase.mtbTemplateFor(mtbDiscipline)
            "cycling" -> PlanBase.cyTemplateFor(cyclingDiscipline)
            else -> PlanBase.TEMPLATES[family] ?: PlanBase.TEMPLATES.getValue("generic")
        }
        val base = (templates[phase] ?: templates.getValue("base")).toMutableList()

        if (family == "running") {
            val rotation = weekNum.mod(3L)
            if (rotation == 1L && (phase == "build" || phase == "peak")) {
                if (base.size >= 6) { val t = base[2]; base[2] = base[3]; base[3] = t }
            } else if (rotation == 2L && phase == "base") {
                // Fartlek Thursday → Wednesday (templates.py: it used to add a second one).
                if (base.size >= 6) { base[2] = "fartlek"; base[3] = "rest" }
            }
        }

        if (family == "mountain_biking" && weekNum.mod(2L) == 1L) {
            val i = base.indexOf("skills")
            if (i >= 0) {
                for (j in listOf(i - 1, i + 1)) {
                    if (j in base.indices && base[j] == "endurance") {
                        val t = base[i]; base[i] = base[j]; base[j] = t
                        break
                    }
                }
            }
        }
        return base
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    /** `_build_steps`: one workout's steps from its sport family and type. */
    fun buildSteps(
        workoutType: String, family: String, sport: String, phase: String, durationMin: Long,
        buildIdx: Long, paces: Map<String, Double>?, ftp: Double?, css: Double?, raceDistanceM: Double,
        capacityKm: Double? = null, variation: Long = 0, structuralMin: Long? = null,
        imperial: Boolean = false, lthr: Long? = null,
        mtbDiscipline: String = "trail", cyclingDiscipline: String = "road_race",
    ): List<Step> {
        when (family) {
            "running" -> {
                val p = paces ?: DEFAULT_RUN_PACES
                when (workoutType) {
                    "easy" -> return Running.easy(durationMin, p, capacityKm, variation, structuralMin, imperial)
                    "easy_recovery" -> return Running.recoveryRun(durationMin, p, variation, imperial)
                    "long" -> return Running.long(durationMin, phase, p, capacityKm, structuralMin, imperial, raceDistanceM)
                    "tempo" -> return Running.tempo(durationMin, p, variation, imperial)
                    "intervals" -> return Running.intervals(buildIdx, p, variation, imperial)
                    "race_pace" -> return Running.racePace(p, raceDistanceM, imperial)
                    "fartlek" -> return Running.fartlek(durationMin, p, variation, imperial)
                    "short_quality" -> return Running.shortQuality(p, imperial)
                    "hill_sprints" -> return Running.hillSprints(p, variation, imperial)
                    "brick_run" -> return Running.brick(durationMin, phase, p, raceDistanceM, imperial)
                }
            }
            "cycling" -> {
                when (workoutType) {
                    "easy", "endurance" -> return Cycling.endurance(durationMin, lthr, ftp, variation)
                    "easy_spin", "easy_recovery" -> return Cycling.easySpin(durationMin, lthr, ftp)
                    "tempo" -> return Cycling.tempo(durationMin, lthr, ftp)
                    "sweet_spot" -> return Cycling.sweetSpot(durationMin, lthr, ftp, variation)
                    "threshold" -> return Cycling.threshold(buildIdx, lthr, ftp, variation)
                    "intervals", "vo2" -> return Cycling.vo2(buildIdx, lthr, ftp, variation)
                    "micro_bursts" -> return Cycling.microBursts(lthr, ftp, variation)
                    "over_unders" -> return Cycling.overUnders(lthr, ftp)
                    "anaerobic" -> return Cycling.anaerobic(lthr, ftp, variation)
                    "sprint" -> return Cycling.sprint(lthr, ftp, variation)
                    "sustained_climb" -> return Cycling.sustainedClimb(durationMin, lthr, ftp)
                    "tt_pace" -> return Cycling.ttPace(durationMin, lthr, ftp)
                    "long" -> return Cycling.long(durationMin, phase, lthr, ftp)
                    "race_pace" -> return Cycling.racePace(lthr, ftp, cyclingDiscipline)
                    "short_quality" -> return Cycling.shortQuality(lthr, ftp)
                }
                if (workoutType.startsWith("field_test")) return Mtb.fieldTest(fieldTestType(workoutType), lthr)
            }
            "mountain_biking" -> {
                when (workoutType) {
                    "easy", "easy_spin", "endurance" -> return Mtb.endurance(durationMin, lthr, ftp, variation)
                    "easy_recovery" -> return Mtb.recovery(durationMin)
                    "tempo" -> return Mtb.tempo(durationMin, lthr, ftp)
                    "sweet_spot" -> return Mtb.sweetSpot(durationMin, lthr, ftp)
                    "threshold" -> return Mtb.threshold(buildIdx, lthr, ftp, variation)
                    "intervals" -> return Mtb.intervals(buildIdx, lthr, ftp, variation)
                    "micro_bursts" -> return Mtb.microBursts(lthr, ftp, variation)
                    "over_unders" -> return Mtb.overUnders(lthr, ftp)
                    "matchbook" -> return Mtb.matchbook(lthr, ftp, variation)
                    "standing_starts" -> return Mtb.standingStarts(lthr, ftp)
                    "descent_repeats" -> return Mtb.descentRepeats(durationMin, lthr)
                    "long" -> return Mtb.long(durationMin, phase, lthr, ftp)
                    "race_pace" -> return Mtb.racePace(lthr, ftp, mtbDiscipline)
                    "skills" -> return Mtb.skills(durationMin, mtbDiscipline, variation)
                    "short_quality" -> return Mtb.standingStarts(lthr, ftp)
                }
                if (workoutType.startsWith("field_test")) return Mtb.fieldTest(fieldTestType(workoutType), lthr)
            }
            "swimming" -> {
                val ow = sport.lowercase().replace(" ", "_") == "open_water_swimming"
                when (workoutType) {
                    "easy", "aerobic", "easy_recovery" -> return Swimming.aerobic(durationMin, css, variation, ow)
                    "technique" -> return Swimming.technique(durationMin, css, variation, ow)
                    "css", "tempo", "threshold" -> return Swimming.css(buildIdx, css, variation, ow)
                    "vo2", "intervals" -> return Swimming.vo2(buildIdx, css, ow)
                    "long" -> return Swimming.long(durationMin, phase, css, ow)
                    "race_pace" -> return Swimming.racePace(raceDistanceM, css, ow)
                    "short_quality" -> return Swimming.shortQuality(css, ow)
                }
            }
            "rowing" -> when (workoutType) {
                "ut2", "easy", "easy_recovery", "endurance", "aerobic" -> return Rowing.ut2(durationMin, variation)
                "ut1" -> return Rowing.ut1(durationMin)
                "technique" -> return Rowing.technique(durationMin, variation)
                "threshold", "tempo" -> return Rowing.threshold(buildIdx, variation)
                "race_pace", "intervals", "vo2" -> return Rowing.racePace(buildIdx)
                "sprint" -> return Rowing.sprint(variation)
                "long" -> return Rowing.long(durationMin)
                "short_quality" -> return Rowing.shortQuality()
            }
            "hiking" -> when (workoutType) {
                "easy", "easy_recovery", "aerobic", "endurance" -> return Hiking.easy(durationMin)
                "vert" -> return Hiking.vert(durationMin, buildIdx, imperial)
                "incline_intervals", "intervals", "tempo", "quality", "short_quality", "race_pace" ->
                    return Hiking.inclineIntervals(buildIdx, variation, lthr)
                "descent" -> return Hiking.descent(buildIdx, variation)
                "back_to_back" -> return Hiking.backToBack(durationMin, phase, buildIdx, imperial)
                "long" -> return Hiking.long(durationMin, phase, buildIdx, imperial)
            }
            "nordic_skiing" -> when (workoutType) {
                "endurance", "easy", "easy_recovery", "aerobic" -> return NordicSkiing.endurance(durationMin, sport, variation, lthr)
                "technique" -> return NordicSkiing.technique(durationMin, sport, variation)
                "pole_hike" -> return NordicSkiing.poleHike(durationMin, buildIdx, imperial, lthr)
                "bounding" -> return NordicSkiing.bounding(buildIdx, variation, lthr)
                "intervals", "vo2" -> return NordicSkiing.intervals(buildIdx, sport, lthr)
                "threshold", "tempo" -> return NordicSkiing.threshold(buildIdx, sport, lthr)
                "race_pace" -> return NordicSkiing.racePace(sport, lthr)
                "long" -> return NordicSkiing.long(durationMin, sport, imperial)
                "short_quality" -> return NordicSkiing.shortQuality()
            }
            "alpine_skiing" -> when (workoutType) {
                "aerobic", "easy", "easy_recovery", "endurance" -> return AlpineSkiing.aerobic(durationMin, variation, lthr)
                "eccentric" -> return AlpineSkiing.eccentric(buildIdx, variation)
                "plyometrics" -> return AlpineSkiing.plyometrics(buildIdx, variation)
                "agility" -> return AlpineSkiing.agility(durationMin, variation)
                "ski_intervals", "intervals", "quality", "short_quality", "race_pace" ->
                    return AlpineSkiing.skiIntervals(buildIdx, lthr)
                "long" -> return AlpineSkiing.long(durationMin)
            }
            "climbing" -> when (workoutType) {
                "arc", "easy", "easy_recovery", "endurance", "aerobic" -> return Climbing.arc(durationMin, buildIdx, variation)
                "technique" -> return Climbing.technique(durationMin, variation)
                "hangboard" -> return Climbing.hangboard(buildIdx, variation)
                "limit_bouldering" -> return Climbing.limitBouldering(buildIdx)
                "power_endurance", "intervals", "quality", "race_pace" -> return Climbing.powerEndurance(buildIdx, variation)
                "long" -> return Climbing.long(durationMin)
                "short_quality" -> return Climbing.shortQuality()
            }
        }
        return when (workoutType) {
            "easy", "endurance" -> Generic.easy(durationMin, sport)
            "aerobic" -> Generic.aerobic(durationMin, sport)
            "intervals", "tempo", "quality", "race_pace", "short_quality" -> Generic.quality(durationMin, sport)
            "long" -> Generic.long(durationMin, sport)
            else -> Generic.easy(durationMin, sport)
        }
    }

    private fun fieldTestType(workoutType: String) =
        if (":" in workoutType) workoutType.substringAfter(":") else "ftp20"

    // ── One week ─────────────────────────────────────────────────────────────
    //
    // `week.py`: a week is sized to its load in TSS. The reasoning for every
    // constant (session caps, weights, the 40% long-session share, doubles,
    // trimming) is written there; this keeps its arithmetic order exactly.

    /** `easy_session`: the sport's steady aerobic session. */
    internal fun easyFill(family: String) = when (family) {
        "running" -> "easy"
        "cycling", "mountain_biking" -> "endurance"
        "swimming" -> "aerobic"
        "rowing" -> "ut2"
        "hiking" -> "easy"
        "nordic_skiing" -> "endurance"
        "alpine_skiing" -> "aerobic"
        "climbing" -> "arc"
        else -> "easy"
    }

    /** `_easy_pace_kmh`: the speed a week's kilometres become hours (and load) at. */
    internal fun easyPaceKmh(family: String, paces: Map<String, Double>?): Double = when (family) {
        "running" -> 3600 / (paces ?: DEFAULT_RUN_PACES).getValue("easy")
        "cycling" -> 18.0
        "mountain_biking" -> 15.0
        "swimming" -> 3.0
        "rowing" -> 12.0
        "hiking" -> 4.0
        "nordic_skiing" -> 9.0
        else -> 6.0
    }

    /** `km_to_tss`: kilometres at easy pace, as load. */
    internal fun kmToTss(km: Double, family: String, paces: Map<String, Double>?): Double =
        km / easyPaceKmh(family, paces) * PlanLoad.tssPerHour(PlanLoad.EASY_IF)

    /** `_goal_disciplines`: (mtb, road-cycling) discipline, normalised. */
    internal fun goalDisciplines(mtb: String?, cycling: String?): Pair<String, String> {
        var mtbDiscipline = (mtb?.takeIf { it.isNotEmpty() } ?: "trail").lowercase()
        if (mtbDiscipline !in setOf("xco", "xcm", "enduro", "trail")) mtbDiscipline = "trail"
        var cyclingDiscipline = (cycling?.takeIf { it.isNotEmpty() } ?: "road_race").lowercase()
        if (cyclingDiscipline !in setOf("road_race", "time_trial", "hill_climb", "criterium")) cyclingDiscipline = "road_race"
        return mtbDiscipline to cyclingDiscipline
    }

    /** `_effective_weeks_base`: where the walk-break capacity model starts. */
    internal fun effectiveWeeksBase(family: String, vdot: Double?, baseEffectiveWeeks: Double?): Double =
        if (family == "running" && vdot != null) {
            PlanBase.pyMax(0.0, (vdot - 20.0) * 0.7)
        } else {
            PlanBase.pyMin(PlanBase.pyMax(0.0, baseEffectiveWeeks?.takeIf { it != 0.0 } ?: 0.0) * 0.25, 8.0)
        }

    /** `running_vdots`' answer: the plan's VDOT, the pace VDOT, the capacity VDOT. */
    data class RunningVdots(val plan: Double?, val pace: Double, val capacity: Double?)

    /**
     * `running_vdots` (plan.py): from today's running-fitness estimate, the
     * VDOT the plan stores (only when measured — a profile guess shapes the
     * paces in the notes, not the watch's pace targets), the VDOT its paces
     * come from, and the effort floor alone for walk-break capacity. Without
     * an estimate, the pace bests as before, with the old default.
     */
    internal fun runningVdots(fitness: RunningFitness.Estimate?, paceBests: List<Pair<Long, Double>>): RunningVdots {
        if (fitness == null) {
            val vdot = PlanBase.bestVdotFromPaceBests(paceBests)
            return RunningVdots(vdot, DEFAULT_VDOT, vdot)
        }
        return RunningVdots(if (fitness.measured) fitness.vdot else null, fitness.vdot, fitness.effortVdot)
    }

    /** `SportCtx`: what a sport's builders need that does not change within a week. */
    data class SportCtx(
        val family: String,
        val sport: String,
        val paces: Map<String, Double>? = null,
        val ftp: Double? = null,
        val css: Double? = null,
        val lthr: Long? = null,
        val capacityKm: Double? = null,
        val mtbDiscipline: String = "trail",
        val cyclingDiscipline: String = "road_race",
        val imperial: Boolean = false,
    )

    private val SIZED_WEIGHT = mapOf(
        "long" to 2.0,
        "easy" to 1.0, "endurance" to 1.0, "aerobic" to 1.0,
        "skills" to 0.8, "easy_spin" to 0.7, "easy_recovery" to 0.6,
        "ut2" to 1.0, "ut1" to 0.8, "vert" to 1.0, "back_to_back" to 0.75,
        "pole_hike" to 1.0, "arc" to 0.8,
    )
    private val SESSION_CAPS = mapOf(
        "running" to Triple(120L, 180L, 45L),
        "cycling" to Triple(240L, 360L, 60L),
        "mountain_biking" to Triple(180L, 300L, 75L),
        "swimming" to Triple(120L, 150L, 35L),
        "rowing" to Triple(120L, 180L, 45L),
        "hiking" to Triple(180L, 360L, 90L),
        "nordic_skiing" to Triple(150L, 300L, 60L),
        "alpine_skiing" to Triple(90L, 180L, 45L),
        "climbing" to Triple(150L, 300L, 60L),
    )
    private val DEFAULT_CAPS = Triple(120L, 240L, 35L)
    private const val CAP_REFERENCE_TSS = 420.0
    private const val EASY_CAP_SCALE_MAX = 1.5
    private const val LONG_CAP_SCALE_MAX = 1.25
    private const val EASY_FLOOR = 20L
    private const val LONG_SHARE = 2.0 / 3.0
    private const val QUALITY_MIN = 30.0
    private const val QUALITY_MAX = 40.0
    private const val BISECT_STEPS = 24
    private const val BISECT_HI = 800.0
    internal const val LOAD_CAPACITY_SHARE = 0.4

    /** `_DOUBLES`: who doubles on which days, and the second session's title prefix. */
    internal const val DOUBLE_PREFIX = "Second "
    private val DOUBLES = mapOf(
        "running" to (setOf("easy", "easy_recovery") to "Second run · "),
        "rowing" to (setOf("ut2", "easy") to "Second row · "),
        "swimming" to (setOf("aerobic", "easy") to "Second swim · "),
    )

    /**
     * `RUN_QUALITY_CAPACITY_KM`: a runner gets quality sessions once they can
     * run ~30 min without a walk break (week.py has the reasoning).
     */
    internal const val RUN_QUALITY_CAPACITY_KM = 5.0

    /** `quality_ready`: false only for a runner who cannot yet run continuously. */
    internal fun qualityReady(ctx: SportCtx): Boolean =
        ctx.family != "running" || ctx.capacityKm == null || ctx.capacityKm >= RUN_QUALITY_CAPACITY_KM

    /** `running_capacity_km`: the novice model or 40% of the weekly distance, whichever is more. */
    internal fun runningCapacityKm(effectiveWeeks: Double, weeklyKm: Double): Double =
        PlanBase.pyMax(PlanBase.capacityKm(effectiveWeeks), weeklyKm * LOAD_CAPACITY_SHARE)

    private fun durOffset(wtype: String): Long = if (wtype == "tempo" || wtype == "sweet_spot") 20L else 0L

    private fun workout(day: CivilDate, wtype: String, ctx: SportCtx, steps: List<Step>): MutableMap<String, Any?> {
        val duration = PlanBase.durationFromSteps(steps, ctx.paces)
        val distance = if (ctx.family == "running") PlanBase.distanceFromSteps(steps, ctx.paces) else null
        return linkedMapOf(
            "scheduled_date" to day, "sport" to ctx.sport, "workout_type" to wtype,
            "title" to PlanBase.workoutTitle(wtype, ctx.family, distance, duration, ctx.imperial),
            "description" to PlanBase.workoutDescription(steps),
            "duration_minutes" to duration,
            "distance_meters" to (if (distance != null && distance != 0.0) PyMath.roundToLong(distance) else null),
            "steps" to steps,
        )
    }

    /** `_variations`: each session's builder variation, counted over the days written. */
    private fun variations(
        slots: List<Pair<CivilDate, String>>, counters: MutableMap<String, Long>?, today: CivilDate?, commit: Boolean,
    ): List<Long> {
        val local = LinkedHashMap<String, Long>()
        if (counters != null) local.putAll(counters)
        val out = mutableListOf<Long>()
        for ((day, wtype) in slots) {
            if (today != null && day < today) { out += 0L; continue }
            val occ = local[wtype] ?: 0L
            local[wtype] = occ + 1
            out += occ.mod(6L)
        }
        if (commit && counters != null) counters.putAll(local)
        return out
    }

    private class Sized(val slot: Int, val weight: Double, val floor: Long, val cap: Long, val double: Boolean = false)

    /** `_Week`: one sport's sessions of a week and the arithmetic to size them. */
    private class Week(
        val slots: List<Pair<CivilDate, String>>, val ctx: SportCtx, val phase: String, val weekTss: Double,
        val buildIdx: Long, val raceDistanceM: Double, val variations: List<Long>, intensity: Double, minLongMin: Long?,
    ) {
        val fixedSteps = LinkedHashMap<Int, List<Step>>()
        var fixedTss = 0.0
        val pool = mutableListOf<Sized>()

        init {
            val caps = SESSION_CAPS[ctx.family] ?: DEFAULT_CAPS
            val scale = weekTss / CAP_REFERENCE_TSS
            val easyCap = (caps.first * PlanBase.pyMin(EASY_CAP_SCALE_MAX, PlanBase.pyMax(1.0, scale))).toLong()
            val longCap = (caps.second * PlanBase.pyMin(LONG_CAP_SCALE_MAX, PlanBase.pyMax(1.0, scale))).toLong()
            val longFloor = caps.third
            val n = slots.size
            val meanMin = weekTss / PlanLoad.tssPerHour(PlanLoad.EASY_IF) * 60 / n
            val qMin = PlanBase.pyMin(QUALITY_MAX, PlanBase.pyMax(QUALITY_MIN, meanMin)).toLong()
            var other = 0.0
            for (i in 0 until n) {
                val t = slots[i].second
                if (t !in SIZED_WEIGHT) other += 1.0 else if (t != "long") other += SIZED_WEIGHT.getValue(t)
            }
            for (i in 0 until n) {
                val t = slots[i].second
                if (t !in SIZED_WEIGHT) {
                    val off = durOffset(t)
                    val st = build(i, maxOf(15L, (qMin * intensity).toLong()) + off, qMin + off)
                    fixedSteps[i] = st
                    fixedTss += PlanLoad.workoutTss(st, ctx.paces)
                } else if (t == "long") {
                    var floor = longFloor
                    if (minLongMin != null && minLongMin > floor) floor = minLongMin
                    pool += Sized(i, PlanBase.pyMin(SIZED_WEIGHT.getValue("long"), PlanBase.pyMax(1.0, other * LONG_SHARE)),
                        minOf(floor, longCap), longCap)
                } else {
                    pool += Sized(i, SIZED_WEIGHT.getValue(t), EASY_FLOOR, easyCap)
                }
            }
        }

        fun build(i: Int, minutes: Long, structural: Long, double: Boolean = false): List<Step> {
            val wtype = slots[i].second
            var v = if (wtype == "long") 0L else variations[i]
            if (double) v = (v + 1).mod(6L)
            return buildSteps(wtype, ctx.family, ctx.sport, phase, minutes, buildIdx, ctx.paces, ctx.ftp, ctx.css,
                raceDistanceM, ctx.capacityKm, v, structural, ctx.imperial, ctx.lthr, ctx.mtbDiscipline,
                ctx.cyclingDiscipline)
        }

        fun minutes(u: Double): List<Long> = pool.map { e -> minOf(e.cap, maxOf(e.floor, (e.weight * u).toLong())) }

        fun load(u: Double): Double {
            var total = 0.0
            val ms = minutes(u)
            for ((k, e) in pool.withIndex()) total += PlanLoad.workoutTss(build(e.slot, ms[k], ms[k], e.double), ctx.paces)
            return total
        }

        fun solve(): Double {
            val remaining = weekTss - fixedTss
            var lo = 0.0
            var hi = BISECT_HI
            var loT = load(lo)
            var hiT = load(hi)
            if (loT >= remaining) return lo
            if (hiT <= remaining) return hi
            repeat(BISECT_STEPS) {
                val mid = (lo + hi) / 2
                val t = load(mid)
                if (t < remaining) { lo = mid; loT = t } else { hi = mid; hiT = t }
            }
            return if (remaining - loT <= hiT - remaining) lo else hi
        }
    }

    /** `_fill_sessions`: one sport's sessions of a week, sized to carry [weekTss]. */
    internal fun fillSessions(
        slotsIn: List<Pair<CivilDate, String>>, ctx: SportCtx, phase: String, weekTss: Double, buildIdx: Long,
        raceDistanceM: Double, counters: MutableMap<String, Long>?, today: CivilDate?,
        intensityIn: Double = 1.0, minLongMin: Long? = null, trimDays: Boolean = false,
        allowDoubles: Boolean = true,
    ): List<MutableMap<String, Any?>> {
        val slots = slotsIn.toMutableList()
        if (slots.isEmpty()) return emptyList()
        val intensity = PlanBase.pyMax(0.5, PlanBase.pyMin(1.5, intensityIn))

        if (trimDays) {
            while (slots.size > 2) {
                val wk = Week(slots, ctx, phase, weekTss, buildIdx, raceDistanceM,
                    variations(slots, counters, today, false), 1.0, minLongMin)
                if (wk.fixedTss + wk.load(0.0) <= weekTss * 1.1) break
                var drop = -1
                for (e in wk.pool) {
                    val t = slots[e.slot].second
                    if (t == "long") continue
                    if (drop < 0 || SIZED_WEIGHT.getValue(t) <= SIZED_WEIGHT.getValue(slots[drop].second)) drop = e.slot
                }
                if (drop < 0) break
                slots.removeAt(drop)
            }
        }

        val wk = Week(slots, ctx, phase, weekTss, buildIdx, raceDistanceM,
            variations(slots, counters, today, true), intensity, minLongMin)
        val doubles = if (allowDoubles) DOUBLES[ctx.family] else null
        if (doubles != null && wk.pool.isNotEmpty()) {
            val remaining = weekTss - wk.fixedTss
            if (wk.load(BISECT_HI) < remaining * 0.95) {
                for (e in wk.pool.toList()) {
                    val t = slots[e.slot].second
                    if (t in doubles.first) wk.pool += Sized(e.slot, 0.5, 30L, 60L, double = true)
                }
            }
        }
        val u = wk.solve()

        val steps = HashMap<Pair<Int, Boolean>, List<Step>>()
        for ((i, st) in wk.fixedSteps) steps[i to false] = st
        val chosen = wk.minutes(u)
        for ((k, e) in wk.pool.withIndex()) {
            val base = chosen[k]
            val least = if (slots[e.slot].second == "long") 20L else 15L
            steps[e.slot to e.double] = wk.build(e.slot, maxOf(least, (base * intensity).toLong()), base, e.double)
        }

        val out = mutableListOf<MutableMap<String, Any?>>()
        for (i in slots.indices) {
            val (day, wtype) = slots[i]
            if (today != null && day < today) continue
            out += workout(day, wtype, ctx, steps.getValue(i to false))
            steps[i to true]?.let {
                val w = workout(day, wtype, ctx, it)
                w["title"] = DOUBLES.getValue(ctx.family).second + w["title"]
                out += w
            }
        }
        return out
    }

    /** `_polarise`: the template with its quality capped for the phase. */
    private fun polarise(template: MutableList<String>, family: String, phase: String): MutableList<String> {
        val fill = easyFill(family)
        val totalSessions = template.count { it != "rest" }
        val highSessions = template.count { it in HIGH_INTENSITY_TYPES }
        // Rounded to the nearest session (week.py says why).
        val maxHigh = maxOf(1, (totalSessions * 0.25 + 0.5).toInt())
        if (highSessions > maxHigh && (phase == "base" || phase == "build")) {
            var excess = highSessions - maxHigh
            for (i in template.indices) {
                if (excess <= 0) break
                if (template[i] in HIGH_INTENSITY_TYPES && template[i] != "race_pace") { template[i] = fill; excess-- }
            }
        }
        val qualitySlots = template.count { it in HIGH_INTENSITY_TYPES }
        val maxQuality = when (phase) { "build", "peak" -> 2; else -> 1 }
        if (qualitySlots > maxQuality) {
            var excess = qualitySlots - maxQuality
            for (i in template.indices) {
                if (excess <= 0) break
                if (template[i] in HIGH_INTENSITY_TYPES && template[i] != "race_pace") { template[i] = fill; excess-- }
            }
        }
        return template
    }

    /**
     * `_week_template`: rotated, trimmed to the days, polarised. [quality]
     * false turns every quality session into the sport's easy one — the first
     * weeks of someone new to the sport ([PlanStart]).
     */
    internal fun weekTemplate(
        family: String, phase: String, weekNum: Long, daysPerWeek: Long?, mtbDiscipline: String, cyclingDiscipline: String,
        quality: Boolean = true,
    ): List<String> {
        var template = rotatingTemplate(family, phase, weekNum, mtbDiscipline, cyclingDiscipline).toMutableList()
        if (daysPerWeek != null) template = PlanBase.applyDaysPerWeek(template, daysPerWeek, family).toMutableList()
        val polarised = polarise(template, family, phase)
        if (quality) return polarised
        val fill = easyFill(family)
        return polarised.map { if (it in HIGH_INTENSITY_TYPES) fill else it }
    }

    /** `_generate_week`: one calendar week of dated workouts. */
    fun generateWeek(
        weekStart: CivilDate, weekEnd: CivilDate, phase: String, weekTss: Double, ctx: SportCtx,
        buildIdx: Long, raceDistance: Number, isRaceWeek: Boolean,
        daysPerWeek: Long? = null, occurrenceCounters: MutableMap<String, Long>? = null, today: CivilDate? = null,
        intensity: Double = 1.0, minLongMin: Long? = null, weekNum: Long = 0, trimDays: Boolean = false,
        quality: Boolean = true,
    ): List<MutableMap<String, Any?>> {
        val template = weekTemplate(ctx.family, phase, weekNum, daysPerWeek, ctx.mtbDiscipline, ctx.cyclingDiscipline,
            quality && qualityReady(ctx))
        val daysInWeek = (weekEnd.epochDay - weekStart.epochDay + 1).toInt()
        val slots = mutableListOf<Pair<CivilDate, String>>()
        var race: MutableMap<String, Any?>? = null
        for (dayIdx in 0 until daysInWeek) {
            val day = CivilDate.fromEpochDay(weekStart.epochDay + dayIdx)
            if (isRaceWeek && day == weekEnd) {
                race = linkedMapOf(
                    "scheduled_date" to day, "sport" to ctx.sport, "workout_type" to "race",
                    "title" to "Race Day", "description" to "Race day. Trust your training.",
                    "duration_minutes" to 0L, "distance_meters" to raceDistance, "steps" to mutableListOf<Step>(),
                )
                continue
            }
            val wtype = if (dayIdx < template.size) template[dayIdx] else "easy"
            if (wtype != "rest") slots += day to wtype
        }
        val workouts = fillSessions(slots, ctx, phase, weekTss, buildIdx, raceDistance.toDouble(),
            occurrenceCounters, today, intensity, minLongMin, trimDays).toMutableList()
        if (race != null && (today == null || (race["scheduled_date"] as CivilDate) >= today)) workouts += race
        return workouts
    }

    // ── The plan ─────────────────────────────────────────────────────────────

    /** What a generated plan is: the VDOT it was built at (running only) and its workouts. */
    data class Generated(val vdot: Double?, val workouts: List<MutableMap<String, Any?>>)

    /**
     * `generate_training_plan`: the full day-by-day plan up to the race.
     *
     * [history] is the last 90 days of activities and [today] the plan's own
     * date — both are inputs, so the same inputs give the same plan on any
     * device.
     */
    fun generateTrainingPlan(
        goal: PlanGoal,
        history: List<HistoryActivity>,
        paceBests: List<Pair<Long, Double>>,
        today: CivilDate,
        ftp: Double? = null,
        daysPerWeek: Long? = null,
        baseEffectiveWeeks: Double? = null,
        imperial: Boolean = false,
        thresholdHr: Double? = null,
        /** How often they do this sport (a [PlanStart] level); read only with no history. */
        activityFrequency: String? = null,
        /** Today's running fitness ([RunningFitness]); see [runningVdots]. */
        runningFitness: RunningFitness.Estimate? = null,
    ): Generated {
        val raceDate = goal.eventDate
        // `goal.event_distance_meters or 42195`: a float column, else an int.
        val raceDistance: Number = goal.eventDistanceMeters?.takeIf { it != 0.0 } ?: 42195L
        val raceDistanceM = raceDistance.toDouble()
        val sport = (goal.eventSport?.takeIf { it.isNotEmpty() } ?: "running").lowercase()
        val family = PlanBase.sportFamily(sport)
        val dpw = goal.daysPerWeek?.takeIf { it != 0L } ?: daysPerWeek?.takeIf { it != 0L } ?: 4L
        val planIntensity = PlanBase.pyMax(0.5, PlanBase.pyMin(1.5, goal.planIntensity?.takeIf { it != 0.0 } ?: 1.0))

        val (mtbDiscipline, cyclingDiscipline) = goalDisciplines(goal.mtbDiscipline, goal.cyclingDiscipline)

        val lthr = thresholdHr?.takeIf { it != 0.0 }?.toLong()

        if (raceDate == null || raceDate <= today) return Generated(null, emptyList())

        if (family == "triathlon") {
            return MultiSport.triathlonPlan(goal, history, paceBests, today, ftp, dpw, planIntensity,
                baseEffectiveWeeks, imperial, lthr, runningFitness)
        }

        val planMonday = CivilDate.fromEpochDay(today.epochDay - (today.epochDay + 3).mod(7L))
        val totalDays = raceDate.epochDay - planMonday.epochDay
        val totalWeeks = maxOf(1L, ceil(totalDays / 7.0).toLong())

        val vdots = if (family == "running") runningVdots(runningFitness, paceBests)
        else RunningVdots(null, DEFAULT_VDOT, null)
        val vdot = vdots.plan
        val css = if (family == "swimming") PlanBase.cssPaceSecPer100m(paceBests) else null

        val baseVdot = vdot
        val vdotStep = if (baseVdot != null) PlanBase.pyMin(WEEKLY_GAIN_VOLUME, 1.5 / maxOf(totalWeeks, 1L)) else 0.0

        var ewBase = effectiveWeeksBase(family, vdots.capacity, baseEffectiveWeeks)

        var currentKm = PlanBase.currentWeeklyKm(history, family, today)
        // Measured volume bounds continuous-run capacity from below (plan.py);
        // an onboarding answer moves the capacity model through ewBase instead.
        val measuredKm = currentKm
        var start: PlanStart.Start? = null
        val noHistory = currentKm < 3
        if (noHistory) {
            currentKm = PlanBase.SPORT_DEFAULT_WEEKLY_KM[family] ?: 20.0
            // No history of this sport: start from how often they say they do it.
            start = PlanStart.noHistoryStart(activityFrequency)
            if (start != null) {
                currentKm *= start.weeklyKmShare
                if (vdots.capacity == null) ewBase = PlanBase.pyMax(ewBase, start.effectiveWeeks)
            }
        }
        // Never plan below what the athlete already does (plan.py).
        val maxKm = PlanBase.pyMax(PlanBase.maxWeeklyKmForRace(raceDistanceM, family), currentKm)
        val startKm = PlanBase.pyMin(currentKm, maxKm)

        val targetLong = PlanBase.targetPeakLongKm(raceDistanceM, family)
        val taperCnt = minOf(3L, maxOf(1L, totalWeeks / 5))
        val nonTaperCnt = maxOf(1L, totalWeeks - taperCnt)
        val firstLongKm = PlanBase.pyMin(startKm * 0.35, targetLong)
        val cycleLen = if (family == "mountain_biking" && mtbDiscipline == "enduro") 3L else 4L

        fun guidedLong(wk: Long): Double {
            if (wk >= nonTaperCnt) {
                val factors = doubleArrayOf(0.75, 0.60, 0.45)
                return targetLong * factors[minOf(wk - nonTaperCnt, (factors.size - 1).toLong()).toInt()]
            }
            val progress = (wk + 1).toDouble() / nonTaperCnt
            var guided = firstLongKm + (targetLong - firstLongKm) * progress
            if (wk.mod(cycleLen) == cycleLen - 1) guided *= 0.75
            return PlanBase.pyMax(guided, firstLongKm)
        }

        val workouts = mutableListOf<MutableMap<String, Any?>>()
        var buildIdx = 0L
        val counters = LinkedHashMap<String, Long>()
        val weeklyVolumes = mutableListOf<Double>()

        for (weekNum in 0 until totalWeeks) {
            val weekStart = CivilDate.fromEpochDay(planMonday.epochDay + weekNum * 7)
            val isRaceWeek = weekNum == totalWeeks - 1
            val weekEnd = if (isRaceWeek) raceDate else CivilDate.fromEpochDay(weekStart.epochDay + 6)
            val weeksRemaining = (raceDate.epochDay - weekStart.epochDay) / 7.0
            val phase = PlanBase.phaseForWeek(weekNum, totalWeeks)
            var volKm = PlanBase.weeklyVolumeKm(weekNum, totalWeeks, startKm, maxKm, weeksRemaining, cycleLen)

            if (weekNum > 0 && buildIdx > 0 && (phase == "build" || phase == "peak")) {
                val prevVol = weeklyVolumes.lastOrNull() ?: volKm
                val maxAllowed = prevVol * (if (phase == "build") 1.05 else 1.03)
                volKm = PlanBase.pyMin(volKm, maxAllowed)
            }

            // ACWR safeguard (Gabbett 2016): cap at 1.3 acute:chronic.
            if (weeklyVolumes.size >= 4) {
                val last4 = weeklyVolumes.takeLast(4)
                val chronic = PyMath.sum(last4) / 4
                if (chronic > 0 && volKm / chronic > 1.3) volKm = PyMath.round(chronic * 1.3, 1)
            }
            weeklyVolumes += volKm

            val weekVdot = if (baseVdot != null) baseVdot + weekNum * vdotStep else null
            val weekPaces = if (family == "running") {
                PlanBase.vdotToPaces(weekVdot?.takeIf { it != 0.0 } ?: vdots.pace)
            } else null

            val effWeeks = ewBase + weekNum * (dpw / 5.0)
            val capKm = if (family == "running") runningCapacityKm(effWeeks, measuredKm) else null

            // Sized in load; the long-run guide a floor of at most 40% of the week (plan.py).
            val ctx = SportCtx(family, sport, weekPaces, ftp, css, lthr, capKm, mtbDiscipline, cyclingDiscipline, imperial)
            val minLongMin = (PlanBase.pyMin(guidedLong(weekNum), volKm * 0.40) / easyPaceKmh(family, weekPaces) * 60).toLong()
            workouts += generateWeek(
                weekStart = weekStart, weekEnd = weekEnd, phase = phase,
                weekTss = kmToTss(volKm, family, weekPaces), ctx = ctx, buildIdx = buildIdx,
                raceDistance = raceDistance, isRaceWeek = isRaceWeek, daysPerWeek = dpw,
                occurrenceCounters = counters, today = today,
                intensity = planIntensity * PlanStart.startIntensity(start, weekNum),
                minLongMin = minLongMin, weekNum = weekNum,
                quality = PlanStart.startQuality(activityFrequency, weekNum, noHistory),
            )

            if (weekNum.mod(cycleLen) != cycleLen - 1 && (phase == "build" || phase == "peak")) buildIdx++
        }
        return Generated(vdot, workouts)
    }

    // ── Analytics ────────────────────────────────────────────────────────────

    /** `_polarisation_check`: (low, high, high share). */
    fun polarisationCheck(template: List<String>): Triple<Int, Int, Double> {
        val total = template.count { it != "rest" }
        val high = template.count { it in HIGH_INTENSITY_TYPES }
        return Triple(total - high, high, high.toDouble() / maxOf(total, 1))
    }

    /** `_monotony_scores`: Foster monotony and strain. */
    fun monotonyScores(loads: List<Double>): Map<String, Any> {
        if (loads.size < 2) return mapOf("monotony" to 1.0, "strain" to 0L, "mean_load" to 0L, "sessions" to loads.size.toLong())
        val mean = PyMath.sum(loads) / loads.size
        if (mean == 0.0) return mapOf("monotony" to 1.0, "strain" to 0L, "mean_load" to 0L, "sessions" to loads.size.toLong())
        val variance = PyMath.sum(loads.map { (it - mean) * (it - mean) }) / loads.size
        val sd = if (variance > 0) sqrt(variance) else 0.01
        val monotony = mean / sd
        val strain = PyMath.sum(loads) * monotony
        return mapOf(
            "monotony" to PyMath.round(monotony, 2),
            "strain" to PyMath.round(strain, 1),
            "mean_load" to PyMath.round(mean, 1),
            "sessions" to loads.size.toLong(),
        )
    }
}
