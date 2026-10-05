// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.Py
import com.tracks.core.parse.PyMath
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A workout step as the server builds it: a dict whose ints are [Long] and
 * whose floats are [Double].
 *
 * ## Why maps rather than data classes
 *
 * A planned workout's `steps` is a JSON document the watch encoder, the web
 * calendar and the phone all read, and it syncs as one field. Keys vary by
 * step type (`duration_min` on a run, `reps`/`distance_m` on an interval set,
 * `duration_sec_each` on an effort set), and the server's JSON writes `30` and
 * `30.0` differently — so the port keeps Python's shapes and Python's number
 * types, and `spec/fixtures/plan.json` compares them exactly.
 */
typealias Step = MutableMap<String, Any?>

internal fun step(vararg pairs: Pair<String, Any?>): Step = linkedMapOf(*pairs)

/**
 * The endurance planner's shared vocabulary — a port of
 * `backend/app/calculators/plan/base.py`.
 *
 * Every formula here is the server's, in the server's arithmetic order: a plan
 * generated on the phone must be the plan the server would have generated,
 * workout for workout, or the two replicas of one account disagree about what
 * the athlete is meant to do on Thursday.
 */
object PlanBase {

    // ── Sport family classification ──────────────────────────────────────────

    private val RUNNING = setOf("running", "trail_running", "treadmill_running", "road_running", "virtual_running")
    private val CYCLING = setOf("cycling", "road_biking", "gravel_cycling", "virtual_cycling", "indoor_cycling", "e_biking")
    private val MTB = setOf("mountain_biking", "trail_biking")
    private val SWIMMING = setOf("swimming", "open_water_swimming", "lap_swimming", "pool_swimming")
    private val ROWING = setOf("rowing", "indoor_rowing")
    private val STRENGTH = setOf("strength_training", "strength", "bodybuilding", "powerlifting")
    private val HIKING = setOf("hiking", "walking")
    private val CLIMBING = setOf(
        "climbing", "rock_climbing", "sport_climbing", "indoor_climbing", "bouldering", "mountaineering",
    )
    /** Endurance skiing; bare "skiing" is here — see base.py `_NORDIC_SKI_SPORTS`. */
    private val NORDIC_SKI = setOf(
        "skiing", "cross_country_skiing", "nordic_skiing", "skate_skiing", "classic_skiing",
        "roller_skiing", "backcountry_skiing", "ski_mountaineering", "skimo",
    )
    private val ALPINE_SKI = setOf("alpine_skiing", "downhill_skiing", "resort_skiing", "snowboarding")
    private val PADDLING = setOf(
        "paddling", "kayaking", "kayak", "canoeing", "canoe",
        "stand_up_paddleboarding", "sup", "paddleboarding", "whitewater",
    )

    /** `_sport_family`: a raw sport string to the planner's parent family. */
    fun sportFamily(sport: String): String {
        val s = sport.lowercase().replace(" ", "_")
        return when {
            s in RUNNING -> "running"
            s in MTB -> "mountain_biking"
            s in CYCLING -> "cycling"
            s in SWIMMING -> "swimming"
            s in ROWING -> "rowing"
            s in HIKING -> "hiking"
            s in NORDIC_SKI -> "nordic_skiing"
            s in ALPINE_SKI -> "alpine_skiing"
            s in CLIMBING -> "climbing"
            s in PADDLING -> "paddling"
            s in STRENGTH -> "strength"
            "triathlon" in s -> "triathlon"
            else -> "generic"
        }
    }

    // ── VDOT (Daniels) ───────────────────────────────────────────────────────

    /**
     * Daniels' VDOT. `exp` here is the platform's, which can differ from
     * glibc's in the last place; the raw VDOT is therefore compared within a
     * few ulps, and everything derived from it (paces rounded to a tenth)
     * exactly.
     */
    fun calculateVdot(distanceM: Double, timeSec: Double): Double {
        val t = timeSec / 60
        val v = distanceM / t
        val vo2 = -4.60 + 0.182258 * v + 0.000104 * (v * v)
        val pct = 0.8 + 0.1894393 * exp(-0.012778 * t) + 0.2989558 * exp(-0.1932605 * t)
        return vo2 / pct
    }

    private fun velocityForPctVo2max(vdot: Double, pct: Double): Double {
        val a = 0.000104
        val b = 0.182258
        val c = -(4.60 + vdot * pct)
        return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
    }

    private val ZONES = listOf(
        "recovery" to 0.62, "easy" to 0.70, "marathon" to 0.82,
        "threshold" to 0.90, "interval" to 0.98, "repetition" to 1.05,
    )

    /**
     * Seconds per km for each zone, rounded to a tenth with Python's
     * `round(x, 1)`. Not the FIT encoder's `vdotToPaces`, which rounds the
     * scaled value half-even instead — the two agree nearly always, and
     * "nearly" is not what a replica of a plan may be.
     */
    fun vdotToPaces(vdot: Double): Map<String, Double> =
        ZONES.associateTo(LinkedHashMap()) { (z, p) -> z to PyMath.round(1000 / velocityForPctVo2max(vdot, p) * 60, 1) }

    /** `_fmt_pace`, with CPython's float `//` and `%`. */
    fun fmtPace(secPerKm: Double, imperial: Boolean = false): String {
        val sec = if (imperial) secPerKm * 1.60934 else secPerKm
        val (m, s) = PyMath.divmod(sec, 60.0)
        val unit = if (imperial) "mi" else "km"
        return "${m.toLong()}:${s.toLong().toString().padStart(2, '0')}/$unit"
    }

    /** `_fmt_dist_m`. */
    fun fmtDistM(meters: Long, imperial: Boolean = false): String {
        if (imperial) {
            if (meters >= 1600) return "${PyMath.fixed(meters / 1609.344, 2)} mi"
            return "${PyMath.roundToLong(meters * 1.09361)} yd"
        }
        if (meters >= 1000) {
            val km = meters / 1000.0
            return if (meters % 1000 == 0L) "${PyMath.fixed(km, 0)} km" else "${PyMath.fixed(km, 1)} km"
        }
        return "$meters m"
    }

    const val DEFAULT_VDOT = 42.0
    val DEFAULT_RUN_PACES: Map<String, Double> = vdotToPaces(DEFAULT_VDOT)

    /** `_best_vdot_from_pace_bests`: the best VDOT any 3 km+ best implies. */
    fun bestVdotFromPaceBests(paceBests: List<Pair<Long, Double>>): Double? {
        var best: Double? = null
        for ((distM, speed) in paceBests) {
            if (distM < 3000 || speed <= 0) continue
            val v = calculateVdot(distM.toDouble(), distM / speed)
            if (v > 0 && (best == null || v > best)) best = v
        }
        return best
    }

    /**
     * `_css_pace_sec_per_100m` (Wakayoshi 1992). Equal 200 and 400 m times
     * raise, as Python's float division by zero does, rather than invent a pace.
     */
    fun cssPaceSecPer100m(paceBests: List<Pair<Long, Double>>): Double? {
        val bests = LinkedHashMap<Long, Double>()
        for ((d, s) in paceBests) bests[d] = s
        val t400 = bests[400L]?.let { 400 / it }
        val t200 = bests[200L]?.let { 200 / it }
        if (t400 != null && t400 != 0.0 && t200 != null && t200 != 0.0) {
            val gap = t400 - t200
            if (gap == 0.0) throw ArithmeticException("float division by zero")
            val cssMps = (400 - 200) / gap
            return if (cssMps > 0) 100 / cssMps else null
        }
        return null
    }

    // ── Zone descriptors ─────────────────────────────────────────────────────

    /** `_hr_zone`: Friel HR range, or % LTHR when LTHR is unset. */
    fun hrZone(lo: Double, hi: Double, lthr: Long?): String =
        if (lthr != null && lthr > 0) "HR ${(lthr * lo).toLong()}–${(lthr * hi).toLong()} bpm"
        else "${(lo * 100).toLong()}–${(hi * 100).toLong()}% LTHR"

    /** `_pwr_zone`: Coggan power range, or null when FTP is unset. */
    fun pwrZone(lo: Double, hi: Double, ftp: Double?): String? =
        if (ftp != null && ftp > 0) {
            "${(ftp * lo).toLong()}–${(ftp * hi).toLong()} W (${(lo * 100).toLong()}–${(hi * 100).toLong()}% FTP)"
        } else null

    /** `_target_note`: 'HR X–Y · power · RPE label'. */
    fun targetNote(
        loHr: Double, hiHr: Double, lthr: Long?,
        loFtp: Double?, hiFtp: Double?, ftp: Double?, rpe: String,
    ): String {
        val parts = mutableListOf(hrZone(loHr, hiHr, lthr))
        if (loFtp != null && hiFtp != null) pwrZone(loFtp, hiFtp, ftp)?.let { parts += it }
        parts += "RPE $rpe"
        return parts.joinToString(" · ")
    }

    // ── Volume ───────────────────────────────────────────────────────────────

    val SPORT_DEFAULT_WEEKLY_KM = mapOf(
        "running" to 20.0, "cycling" to 60.0, "mountain_biking" to 50.0, "swimming" to 5.0,
        "rowing" to 20.0, "hiking" to 15.0, "nordic_skiing" to 30.0, "alpine_skiing" to 20.0,
        "climbing" to 20.0, "generic" to 20.0, "triathlon" to 20.0,
    )
    internal val SPORT_MAX_WEEKLY_KM = mapOf(
        "running" to 80.0, "cycling" to 250.0, "mountain_biking" to 150.0, "swimming" to 25.0,
        "rowing" to 50.0, "hiking" to 40.0, "nordic_skiing" to 80.0, "alpine_skiing" to 50.0,
        "climbing" to 40.0, "generic" to 60.0, "triathlon" to 80.0,
    )

    /**
     * `_current_weekly_km`: the median weekly distance over the 8 weeks
     * before [today] — the plan's date, not the wall clock's.
     */
    fun currentWeeklyKm(history: List<HistoryActivity>, family: String, today: CivilDate): Double {
        val cutoff = CivilDate.fromEpochDay(today.epochDay - 56)
        val weekly = LinkedHashMap<Long, Double>()
        for (act in history) {
            val dist = act.distanceMeters
            if (dist == null || dist == 0.0 || act.startedAt == null) continue
            if (sportFamily(act.sport ?: "") != family) continue
            val d = act.startedAt
            if (d < cutoff) continue
            val wk = (d.epochDay - cutoff.epochDay).floorDiv(7L)
            weekly[wk] = (weekly[wk] ?: 0.0) + dist / 1000
        }
        if (weekly.isEmpty()) return 0.0
        val vals = weekly.values.sorted()
        return vals[vals.size / 2]
    }

    fun maxWeeklyKmForRace(raceDistanceM: Double, family: String): Double {
        val base = SPORT_MAX_WEEKLY_KM[family] ?: 60.0
        return when (family) {
            "running" -> when {
                raceDistanceM >= 42000 -> 80.0
                raceDistanceM >= 21000 -> 55.0
                raceDistanceM >= 10000 -> 45.0
                else -> 35.0
            }
            "cycling" -> when {
                raceDistanceM >= 160000 -> 250.0
                raceDistanceM >= 100000 -> 180.0
                else -> 120.0
            }
            "mountain_biking" -> when {
                raceDistanceM >= 100000 -> 150.0
                raceDistanceM >= 60000 -> 120.0
                else -> 90.0
            }
            else -> base
        }
    }

    /** `_target_peak_long_km`. */
    fun targetPeakLongKm(raceDistanceM: Double, family: String): Double {
        val rdKm = raceDistanceM / 1000
        return when (family) {
            "running" -> when {
                rdKm <= 5.0 -> 15.0
                rdKm <= 10.0 -> 18.0
                rdKm <= 21.1 -> pyMax(rdKm * 0.85, 16.0)
                rdKm <= 42.2 -> rdKm * 0.80
                rdKm <= 80.0 -> pyMin(rdKm * 0.55, 45.0)
                else -> 50.0
            }
            "cycling" -> when {
                rdKm <= 60.0 -> rdKm * 0.85
                rdKm <= 160.0 -> pyMin(rdKm * 0.75, 130.0)
                else -> pyMin(rdKm * 0.60, 200.0)
            }
            "mountain_biking" -> when {
                rdKm <= 40.0 -> rdKm * 0.85
                rdKm <= 100.0 -> pyMin(rdKm * 0.75, 70.0)
                else -> pyMin(rdKm * 0.55, 90.0)
            }
            "swimming" -> pyMin(rdKm * 0.85, 12.0)
            else -> pyMin(rdKm * 0.75, (SPORT_MAX_WEEKLY_KM[family] ?: 60.0) * 0.4)
        }
    }

    /** Python's `min(a, b)`: the first argument on a tie. */
    internal fun pyMin(a: Double, b: Double): Double = if (b < a) b else a

    /** Python's `max(a, b)`: the first argument on a tie. */
    internal fun pyMax(a: Double, b: Double): Double = if (b > a) b else a

    // ── Phases and progression ───────────────────────────────────────────────

    /** `_phase_for_week`: block periodisation, base always first. */
    fun phaseForWeek(weekNum: Long, totalWeeks: Long): String {
        if (totalWeeks <= 1) return "taper"
        val taperW = minOf(3L, maxOf(1L, totalWeeks / 5))
        val peakW = if (totalWeeks > 6) minOf(4L, maxOf(1L, (totalWeeks - taperW) / 4)) else 0L
        val buildW = if (totalWeeks > 4) minOf(5L, maxOf(1L, (totalWeeks - taperW - peakW) / 3)) else 0L
        val baseW = maxOf(1L, totalWeeks - taperW - peakW - buildW)
        return when {
            weekNum < baseW -> "base"
            weekNum < baseW + buildW -> "build"
            weekNum < baseW + buildW + peakW -> "peak"
            else -> "taper"
        }
    }

    /**
     * `_weekly_volume_km`: 3:1 (or 2:1) build/recovery with a Bosquet taper.
     * `1.10 ** n` goes through the platform `pow`, as Python's goes through C's.
     */
    fun weeklyVolumeKm(
        weekNum: Long, totalWeeks: Long, startKm: Double, maxKm: Double,
        weeksRemaining: Double, cycleLenIn: Long = 4,
    ): Double {
        val cycleLen = maxOf(2L, cycleLenIn)
        val recoveryPos = cycleLen - 1
        val taperW = minOf(3L, maxOf(1L, totalWeeks / 5))
        val nonTaperW = maxOf(1L, totalWeeks - taperW)
        val lastBw = nonTaperW - 1
        val peakKm = pyMin(startKm * 1.10.pow((lastBw - lastBw.floorDiv(cycleLen)).toDouble()), maxKm)
        if (weeksRemaining <= 1) return pyMax(peakKm * 0.40, 5.0)
        if (weeksRemaining <= 2) return pyMax(peakKm * 0.60, 10.0)
        if (weeksRemaining <= 3) return pyMax(peakKm * 0.75, 15.0)
        val cyclePos = weekNum.mod(cycleLen)
        val buildWeek = weekNum - weekNum.floorDiv(cycleLen)
        var target = pyMin(startKm * 1.10.pow(buildWeek.toDouble()), maxKm)
        if (cyclePos == recoveryPos) target *= 0.75
        return PyMath.round(target, 1)
    }

    fun walkWarmup(): Step = step("type" to "walk", "duration_min" to 5L, "note" to "Brisk 5-min walk to warm up")
    fun walkCooldown(): Step = step("type" to "walk", "duration_min" to 5L, "note" to "5-min walk to cool down and recover")

    /** `_capacity_km`: continuous-run capacity, 1.5 × 1.15^effective_weeks. */
    fun capacityKm(effectiveWeeks: Double): Double = 1.5 * 1.15.pow(pyMax(0.0, effectiveWeeks))

    // ── days_per_week ────────────────────────────────────────────────────────

    private val REMOVAL_PRIORITY = mapOf(
        "easy" to 0, "endurance" to 0, "aerobic" to 0, "easy_spin" to 0, "easy_recovery" to 0,
        "short_quality" to 1, "skills" to 1,
        "fartlek" to 2, "sweet_spot" to 2,
        "tempo" to 3, "over_unders" to 3,
        "intervals" to 4, "race_pace" to 4, "vo2" to 4, "threshold" to 4, "micro_bursts" to 4,
        "matchbook" to 4, "standing_starts" to 4, "descent_repeats" to 4, "sustained_climb" to 4,
        "tt_pace" to 4, "sprint" to 4, "anaerobic" to 4,
        "long" to 99, "rest" to 99,
        "ut2" to 0, "arc" to 0, "vert" to 1, "pole_hike" to 1, "technique" to 1, "ut1" to 1,
        "agility" to 2, "descent" to 2, "eccentric" to 2, "back_to_back" to 2,
        "css" to 4, "incline_intervals" to 4, "bounding" to 4, "plyometrics" to 3,
        "ski_intervals" to 4, "hangboard" to 3, "limit_bouldering" to 4, "power_endurance" to 4,
    )

    private val EASY_FILL = mapOf(
        "running" to "easy", "cycling" to "easy_spin", "mountain_biking" to "easy_spin",
        "swimming" to "aerobic", "rowing" to "easy", "hiking" to "easy",
        "generic" to "easy", "triathlon" to "easy",
        "nordic_skiing" to "endurance", "alpine_skiing" to "aerobic", "climbing" to "arc",
    )

    private val QUALITY_TYPES = setOf(
        "fartlek", "intervals", "tempo", "race_pace", "short_quality", "sweet_spot", "quality",
        "threshold", "micro_bursts", "over_unders", "matchbook", "standing_starts", "descent_repeats",
        "vo2", "sustained_climb", "tt_pace", "sprint", "anaerobic",
        "css", "incline_intervals", "bounding", "ski_intervals", "plyometrics",
        "limit_bouldering", "power_endurance", "hangboard",
    )
    private val EASY_TYPES = setOf(
        "easy", "aerobic", "endurance", "easy_spin", "easy_recovery", "skills",
        "technique", "ut2", "ut1", "vert", "pole_hike", "arc",
    )

    private fun maxConsecutiveWorkouts(t: List<String>): Int {
        var best = 0
        var cur = 0
        for (x in t) {
            cur = if (x != "rest") cur + 1 else 0
            best = maxOf(best, cur)
        }
        return best
    }

    private fun adjacentEasyQualityPairs(t: List<String>): Int {
        var count = 0
        for (i in 0 until t.size - 1) {
            val a = t[i]
            val b = t[i + 1]
            if ((a in QUALITY_TYPES && b in EASY_TYPES) || (a in EASY_TYPES && b in QUALITY_TYPES)) count++
        }
        return count
    }

    /** Python's `min(xs, key=k)`: the first element with the smallest key. */
    private fun <T> firstMin(xs: List<T>, cmp: Comparator<T>): T {
        var best = xs[0]
        for (x in xs.drop(1)) if (cmp.compare(x, best) < 0) best = x
        return best
    }

    /** `_apply_days_per_week`: trim or fill a weekly template to the day count. */
    fun applyDaysPerWeek(template: List<String>, daysPerWeek: Long, family: String): List<String> {
        val result = template.toMutableList()
        val target = maxOf(1L, minOf(daysPerWeek, 7L)).toInt()

        while (result.count { it != "rest" } > target) {
            val candidates = result.indices.filter { result[it] != "rest" && result[it] != "long" }
            if (candidates.isEmpty()) break
            val minPri = candidates.minOf { REMOVAL_PRIORITY[result[it]] ?: 1 }
            val lowest = candidates.filter { (REMOVAL_PRIORITY[result[it]] ?: 1) == minPri }
            val best = firstMin(lowest, compareBy<Int>(
                { i -> maxConsecutiveWorkouts(result.toMutableList().also { it[i] = "rest" }) },
                { i -> adjacentEasyQualityPairs(result.toMutableList().also { it[i] = "rest" }) },
            ))
            result[best] = "rest"
        }

        val easy = EASY_FILL[family] ?: "easy"
        while (result.count { it != "rest" } < target) {
            var restSlots = (1 until result.size).filter { result[it] == "rest" }
            if (restSlots.isEmpty()) restSlots = if (result[0] == "rest") listOf(0) else emptyList()
            if (restSlots.isEmpty()) break
            val best = firstMin(restSlots, compareBy<Int>(
                { i -> maxConsecutiveWorkouts(result.toMutableList().also { it[i] = easy }) },
                { it },
            ))
            result[best] = easy
        }
        return result
    }

    // ── Weekly templates ─────────────────────────────────────────────────────

    private val RUNNING_T = mapOf(
        "base" to listOf("rest", "easy", "rest", "fartlek", "rest", "easy", "long"),
        "build" to listOf("rest", "easy", "rest", "intervals", "easy_recovery", "tempo", "long"),
        "peak" to listOf("rest", "easy", "rest", "intervals", "easy_recovery", "race_pace", "long"),
        "taper" to listOf("rest", "easy", "rest", "short_quality", "rest", "easy", "long"),
    )
    // The per-sport templates below are base.py's, where the reasoning for
    // each (and for the order of hard sessions in build) is written down.
    private val SWIMMING_T = mapOf(
        "base" to listOf("rest", "technique", "aerobic", "rest", "css", "aerobic", "long"),
        "build" to listOf("rest", "technique", "vo2", "rest", "css", "aerobic", "long"),
        "peak" to listOf("rest", "technique", "vo2", "rest", "race_pace", "aerobic", "long"),
        "taper" to listOf("rest", "technique", "rest", "rest", "short_quality", "aerobic", "long"),
    )
    private val ROWING_T = mapOf(
        "base" to listOf("rest", "ut2", "technique", "threshold", "rest", "ut1", "long"),
        "build" to listOf("rest", "ut2", "threshold", "ut1", "rest", "race_pace", "long"),
        "peak" to listOf("rest", "ut2", "race_pace", "technique", "rest", "sprint", "long"),
        "taper" to listOf("rest", "ut2", "rest", "short_quality", "rest", "technique", "long"),
    )
    private val HIKING_T = mapOf(
        "base" to listOf("rest", "easy", "vert", "rest", "descent", "rest", "long"),
        "build" to listOf("rest", "easy", "incline_intervals", "rest", "vert", "back_to_back", "long"),
        "peak" to listOf("rest", "easy", "incline_intervals", "descent", "rest", "back_to_back", "long"),
        "taper" to listOf("rest", "easy", "rest", "vert", "rest", "easy", "long"),
    )
    private val NORDIC_T = mapOf(
        "base" to listOf("rest", "endurance", "technique", "pole_hike", "rest", "bounding", "long"),
        "build" to listOf("rest", "endurance", "threshold", "technique", "rest", "intervals", "long"),
        "peak" to listOf("rest", "endurance", "intervals", "technique", "rest", "race_pace", "long"),
        "taper" to listOf("rest", "endurance", "rest", "short_quality", "rest", "technique", "long"),
    )
    private val ALPINE_T = mapOf(
        "base" to listOf("rest", "aerobic", "eccentric", "aerobic", "rest", "agility", "long"),
        "build" to listOf("rest", "aerobic", "plyometrics", "agility", "rest", "ski_intervals", "long"),
        "peak" to listOf("rest", "aerobic", "plyometrics", "eccentric", "rest", "ski_intervals", "long"),
        "taper" to listOf("rest", "aerobic", "rest", "agility", "rest", "aerobic", "long"),
    )
    private val CLIMBING_T = mapOf(
        "base" to listOf("rest", "hangboard", "arc", "rest", "technique", "rest", "long"),
        "build" to listOf("rest", "limit_bouldering", "arc", "rest", "power_endurance", "rest", "long"),
        "peak" to listOf("rest", "limit_bouldering", "technique", "rest", "power_endurance", "rest", "long"),
        "taper" to listOf("rest", "technique", "rest", "short_quality", "rest", "rest", "long"),
    )
    private val GENERIC_T = mapOf(
        "base" to listOf("rest", "easy", "rest", "aerobic", "rest", "easy", "long"),
        "build" to listOf("rest", "easy", "easy", "quality", "rest", "quality", "long"),
        "peak" to listOf("rest", "easy", "easy", "quality", "rest", "quality", "long"),
        "taper" to listOf("rest", "easy", "rest", "easy", "rest", "easy", "long"),
    )

    private val TEMPLATES_MTB = mapOf(
        "xco" to mapOf(
            "base" to listOf("rest", "endurance", "skills", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "micro_bursts", "sweet_spot", "easy_spin", "intervals", "long"),
            "peak" to listOf("rest", "endurance", "standing_starts", "threshold", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "short_quality", "rest", "easy_spin", "long"),
        ),
        "xcm" to mapOf(
            "base" to listOf("rest", "endurance", "skills", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "skills", "sweet_spot", "easy_spin", "intervals", "long"),
            "peak" to listOf("rest", "endurance", "skills", "threshold", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "short_quality", "rest", "easy_spin", "long"),
        ),
        "enduro" to mapOf(
            "base" to listOf("rest", "endurance", "skills", "endurance", "skills", "descent_repeats", "long"),
            "build" to listOf("rest", "endurance", "matchbook", "skills", "easy_spin", "descent_repeats", "long"),
            "peak" to listOf("rest", "endurance", "skills", "matchbook", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "skills", "short_quality", "rest", "skills", "long"),
        ),
        "trail" to mapOf(
            "base" to listOf("rest", "endurance", "skills", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "skills", "sweet_spot", "easy_spin", "intervals", "long"),
            "peak" to listOf("rest", "endurance", "skills", "threshold", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "skills", "rest", "easy_spin", "long"),
        ),
    )

    private val TEMPLATES_CY = mapOf(
        "road_race" to mapOf(
            "base" to listOf("rest", "endurance", "tempo", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "sweet_spot", "endurance", "easy_spin", "threshold", "long"),
            "peak" to listOf("rest", "endurance", "vo2", "tempo", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "short_quality", "rest", "easy_spin", "long"),
        ),
        "time_trial" to mapOf(
            "base" to listOf("rest", "endurance", "tempo", "endurance", "rest", "sweet_spot", "long"),
            "build" to listOf("rest", "endurance", "vo2", "endurance", "easy_spin", "threshold", "long"),
            "peak" to listOf("rest", "endurance", "tt_pace", "tempo", "easy_spin", "threshold", "long"),
            "taper" to listOf("rest", "endurance", "rest", "tt_pace", "rest", "easy_spin", "long"),
        ),
        "hill_climb" to mapOf(
            "base" to listOf("rest", "endurance", "sweet_spot", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "sustained_climb", "endurance", "easy_spin", "threshold", "long"),
            "peak" to listOf("rest", "endurance", "sustained_climb", "tempo", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "sustained_climb", "rest", "easy_spin", "long"),
        ),
        "criterium" to mapOf(
            "base" to listOf("rest", "endurance", "sweet_spot", "endurance", "rest", "tempo", "long"),
            "build" to listOf("rest", "endurance", "anaerobic", "over_unders", "easy_spin", "sprint", "long"),
            "peak" to listOf("rest", "endurance", "anaerobic", "over_unders", "easy_spin", "race_pace", "long"),
            "taper" to listOf("rest", "endurance", "rest", "sprint", "rest", "easy_spin", "long"),
        ),
    )

    /** `_TEMPLATES`, after the module's own reassignments (cycling = road race, MTB = trail). */
    val TEMPLATES: Map<String, Map<String, List<String>>> = mapOf(
        "running" to RUNNING_T,
        "cycling" to TEMPLATES_CY.getValue("road_race"),
        "swimming" to SWIMMING_T,
        "generic" to GENERIC_T,
        "rowing" to ROWING_T,
        "hiking" to HIKING_T,
        "nordic_skiing" to NORDIC_T,
        "alpine_skiing" to ALPINE_T,
        "climbing" to CLIMBING_T,
        "triathlon" to RUNNING_T,
        "mountain_biking" to TEMPLATES_MTB.getValue("trail"),
    )

    fun mtbTemplateFor(discipline: String): Map<String, List<String>> =
        TEMPLATES_MTB[discipline.lowercase()] ?: TEMPLATES_MTB.getValue("trail")

    fun cyTemplateFor(discipline: String): Map<String, List<String>> =
        TEMPLATES_CY[discipline.lowercase()] ?: TEMPLATES_CY.getValue("road_race")

    // ── Duration / distance from steps ───────────────────────────────────────

    private fun Map<String, Any?>.num(key: String, default: Long = 0L): Double =
        if (containsKey(key)) Py.num(this[key]) else default.toDouble()

    private fun Map<String, Any?>.long(key: String, default: Long = 0L): Long =
        if (containsKey(key)) Py.int(this[key]) else default

    private val TIMED = setOf("run", "warmup", "cooldown", "walk", "ride", "swim", "activity")

    /** `_duration_from_steps`, accumulating in the server's order. */
    fun durationFromSteps(steps: List<Map<String, Any?>>, paces: Map<String, Double>?): Long {
        val p = paces ?: DEFAULT_RUN_PACES
        var total = 0.0
        for (s in steps) {
            val t = s["type"]
            if (t in TIMED) {
                total += s.num("duration_min")
            } else if (t == "interval_set" || t == "effort_set") {
                val reps = s.long("reps")
                if (s.containsKey("duration_min_each")) {
                    total += (reps * Py.int(s["duration_min_each"])).toDouble()
                    total += (reps * s.long("rest_min")).toDouble()
                } else if (s.containsKey("duration_sec_each")) {
                    total += (reps * (Py.int(s["duration_sec_each"]) + s.long("rest_sec"))).toDouble() / 60
                } else if (s.containsKey("sec_per_km")) {
                    // A swim or erg piece carries its own pace (base.py).
                    total += reps * (Py.int(s["distance_m"]) / 1000.0 * Py.num(s["sec_per_km"]) / 60)
                    total += (reps * s.long("rest_sec")).toDouble() / 60
                } else {
                    val zone = (if (s.containsKey("pace")) s["pace"] else "interval") as String?
                    val pace = zone?.let { p[it] } ?: (p["interval"] ?: 300.0)
                    total += reps * (Py.int(s["distance_m"]) / 1000.0 * pace / 60)
                    total += (reps * s.long("rest_sec")).toDouble() / 60
                }
            } else if (t == "fartlek") {
                total += s.num("duration_min")
            }
        }
        return maxOf(1L, total.toLong())
    }

    /** `_distance_from_steps`: metres a running workout covers at its paces. */
    fun distanceFromSteps(steps: List<Map<String, Any?>>, paces: Map<String, Double>?): Double {
        val p = paces ?: DEFAULT_RUN_PACES
        var total = 0.0
        for (s in steps) {
            val t = s["type"]
            val zone = (s["pace"] as String?)?.takeIf { it.isNotEmpty() }
                ?: (if (s.containsKey("intensity")) s["intensity"] as String? else "easy")
            val pace = zone?.let { p[it] }?.takeIf { it != 0.0 } ?: (p["easy"] ?: 360.0)
            if (t == "run" || t == "warmup" || t == "cooldown") {
                total += s.num("duration_min") / (pace / 1000) * 60
            } else if (t == "interval_set") {
                total += (s.long("reps") * s.long("distance_m")).toDouble()
            }
        }
        return total
    }

    // ── Titles and descriptions ──────────────────────────────────────────────

    private val TYPE_LABEL = mapOf(
        "easy" to "Easy", "easy_recovery" to "Recovery Run", "endurance" to "Endurance Ride",
        "aerobic" to "Aerobic Swim", "long" to "Long Session", "tempo" to "Tempo",
        "intervals" to "Intervals", "race_pace" to "Race-Pace Work", "fartlek" to "Fartlek",
        "short_quality" to "Strides", "sweet_spot" to "Sweet Spot",
        "easy_spin" to "Recovery Spin", "quality" to "Quality Session",
        "skills" to "Skills Session", "threshold" to "Threshold Intervals",
        "micro_bursts" to "Micro-Bursts (30/15)", "over_unders" to "Over-Unders",
        "matchbook" to "Anaerobic Matchbook", "standing_starts" to "Standing-Start Sprints",
        "descent_repeats" to "Descent Repeats", "field_test" to "Field Test",
        "vo2" to "VO2max Intervals", "sustained_climb" to "Sustained Climb",
        "tt_pace" to "TT-Pace Block", "sprint" to "Sprint Repeats", "anaerobic" to "Anaerobic Capacity",
        "hill_sprints" to "Hill Sprints",
        "brick_run" to "Brick · Run off the bike",
        "technique" to "Technique", "css" to "CSS Threshold Set",
        "ut2" to "UT2 Steady State", "ut1" to "UT1 Steady State",
        "vert" to "Vertical Hike", "incline_intervals" to "Incline Intervals",
        "descent" to "Descent Conditioning", "back_to_back" to "Back-to-Back Day 1",
        "pole_hike" to "Ski-Walking with Poles", "bounding" to "Uphill Bounding",
        "eccentric" to "Eccentric Leg Circuit", "plyometrics" to "Plyometrics",
        "agility" to "Agility & Balance", "ski_intervals" to "Ski-Run Intervals",
        "arc" to "ARC Endurance", "hangboard" to "Finger Strength",
        "limit_bouldering" to "Limit Bouldering", "power_endurance" to "Power Endurance",
    )

    /** `_FAMILY_TYPE_LABEL`: the same slot named for the sport, checked first. */
    private val FAMILY_TYPE_LABEL = mapOf(
        ("swimming" to "long") to "Long Swim", ("swimming" to "vo2") to "VO2 Set",
        ("swimming" to "race_pace") to "Race-Pace Set", ("swimming" to "short_quality") to "Sharpening Set",
        ("rowing" to "long") to "Long Row", ("rowing" to "threshold") to "AT Pieces",
        ("rowing" to "race_pace") to "2k-Pace Pieces", ("rowing" to "sprint") to "AN Sprints",
        ("rowing" to "short_quality") to "Race Sharpener", ("rowing" to "easy") to "Easy Row",
        ("hiking" to "long") to "Long Hike", ("hiking" to "easy") to "Easy Hike",
        ("nordic_skiing" to "endurance") to "Endurance Ski", ("nordic_skiing" to "easy") to "Easy Ski",
        ("nordic_skiing" to "long") to "Long Ski", ("nordic_skiing" to "intervals") to "Uphill VO2 Intervals",
        ("nordic_skiing" to "short_quality") to "Sprints & Race Pace",
        ("alpine_skiing" to "aerobic") to "Aerobic Base", ("alpine_skiing" to "easy") to "Aerobic Base",
        ("alpine_skiing" to "long") to "Long Aerobic Day",
        ("climbing" to "long") to "Volume Day", ("climbing" to "easy") to "ARC Endurance",
        ("climbing" to "short_quality") to "Short Power Session",
    )

    private val FIELD_TEST_LABELS = mapOf(
        "ftp20" to "FTP Test (20 min)", "pmax5" to "5-Min Power Test",
        "rsa" to "RSA Sprint Test", "wprime1" to "1-Min W' Test",
    )

    /** `_workout_title`. The km/mi figure is Python's `str(round(x, n))`. */
    fun workoutTitle(
        workoutType: String, family: String, distanceM: Double?, durationMin: Long, imperial: Boolean = false,
    ): String {
        val base = if (workoutType.startsWith("field_test:")) {
            FIELD_TEST_LABELS[workoutType.substringAfter(":")] ?: TYPE_LABEL.getValue("field_test")
        } else {
            FAMILY_TYPE_LABEL[family to workoutType]
                ?: TYPE_LABEL[workoutType] ?: Py.title(workoutType.replace("_", " "))
        }
        if (family == "running" && distanceM != null && distanceM != 0.0 && distanceM > 0) {
            return if (imperial) "$base — ${Py.floatRepr(PyMath.round(distanceM / 1609.344, 2))} mi"
            else "$base — ${Py.floatRepr(PyMath.round(distanceM / 1000, 1))} km"
        }
        return "$base — $durationMin min"
    }

    /** `_workout_description`: one line per step, blanks dropped. */
    fun workoutDescription(steps: List<Map<String, Any?>>): String {
        val parts = mutableListOf<String>()
        for (s in steps) {
            val t = s["type"] as String?
            val note = (if (s.containsKey("note")) s["note"] else "") as String? ?: ""
            when (t) {
                "walk" -> parts += "Walk: ${Py.str(s["duration_min"])} min — $note"
                "run", "ride", "swim", "activity" -> {
                    val dur = s["duration_min"]
                    parts += if (Py.truthy(dur)) "${Py.str(dur)} min: $note" else note
                }
                "warmup", "cooldown" -> parts += "${Py.title(t)}: ${Py.str(s["duration_min"])} min — $note"
                "interval_set", "effort_set", "fartlek" -> parts += note
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

/**
 * One past activity, as the planner reads history: sport, the UTC date it
 * started, its distance, and how long it took — the last for sharing a
 * multi-sport week by the hours each sport gets (MultiSport.recentHours).
 */
data class HistoryActivity(
    val sport: String?,
    val startedAt: CivilDate?,
    val distanceMeters: Double?,
    val durationSeconds: Double? = null,
)
