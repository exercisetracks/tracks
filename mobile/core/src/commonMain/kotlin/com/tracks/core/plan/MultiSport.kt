// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanGenerator.SportCtx
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Weeks that mix sports — a port of
 * `backend/app/calculators/plan/generator/multisport.py` (triathlon and
 * multi-sport fitness weeks) and of `plan.py`'s `_generate_triathlon_plan`.
 * One load target per week, shared between the sports; sessions placed by
 * the penalties the Python docstring explains; each sport sized by
 * [PlanGenerator.fillSessions]. Held to the server by spec/fixtures/plan.json.
 */
object MultiSport {

    val IMPACT_FAMILIES = setOf("running", "hiking")
    private val HARD_ROLES = setOf("long", "quality", "brick")

    private val TRI_SHARES = listOf(
        40_000.0 to Triple(0.20, 0.45, 0.35),
        80_000.0 to Triple(0.18, 0.47, 0.35),
        170_000.0 to Triple(0.15, 0.52, 0.33),
    )
    private val TRI_SHARES_FULL = Triple(0.13, 0.55, 0.32)
    private val TRI_LEGS = listOf(
        12_950.0 to Triple(400.0, 10_000.0, 2_500.0),
        25_750.0 to Triple(750.0, 20_000.0, 5_000.0),
        51_500.0 to Triple(1_500.0, 40_000.0, 10_000.0),
        113_000.0 to Triple(1_900.0, 90_000.0, 21_097.0),
        226_000.0 to Triple(3_800.0, 180_000.0, 42_195.0),
    )
    private val TRI_COUNTS = mapOf(
        1L to listOf(0, 1, 0), 2L to listOf(1, 1, 1), 3L to listOf(1, 1, 1), 4L to listOf(1, 2, 2),
        5L to listOf(2, 2, 2), 6L to listOf(2, 3, 3), 7L to listOf(3, 3, 3),
    )
    private val TRI_DAYS = mapOf(
        1L to listOf(5), 2L to listOf(2, 5), 3L to listOf(1, 3, 5), 4L to listOf(1, 3, 5, 6),
        5L to listOf(1, 2, 3, 5, 6), 6L to listOf(1, 2, 3, 4, 5, 6), 7L to listOf(0, 1, 2, 3, 4, 5, 6),
    )

    fun triShares(raceDistanceM: Double): Triple<Double, Double, Double> {
        for ((limit, shares) in TRI_SHARES) if (raceDistanceM < limit) return shares
        return TRI_SHARES_FULL
    }

    fun triLegs(raceDistanceM: Double): Triple<Double, Double, Double> {
        var best = TRI_LEGS[0]
        for (e in TRI_LEGS) if (abs(e.first - raceDistanceM) < abs(best.first - raceDistanceM)) best = e
        return best.second
    }

    class Session(val sport: Int, val role: String, val wtype: String, var day: Int = -1, val order: Int = 0)

    fun easyType(family: String): String = PlanGenerator.easyFill(family)

    /** `quality_types`: the sport's own quality sessions for the phase. */
    fun qualityTypes(ctx: SportCtx, phase: String, weekNum: Long): List<String> {
        for (ph in listOf(phase, "build")) {
            val found = PlanGenerator.rotatingTemplate(ctx.family, ph, weekNum, ctx.mtbDiscipline, ctx.cyclingDiscipline)
                .filter { it in PlanGenerator.HIGH_INTENSITY_TYPES }
            if (found.isNotEmpty()) return found
        }
        return listOf("intervals")
    }

    private fun penalty(s: Session, day: Int, placed: List<Session>, families: List<String>, maxPerDay: Int): Int? {
        val fam = families[s.sport]
        val hard = s.role in HARD_ROLES
        val onDay = placed.filter { it.day == day }
        if (onDay.size >= maxPerDay) return null
        var pen = 0
        for (p in onDay) {
            if (families[p.sport] == fam) pen += 100
            if (hard && p.role in HARD_ROLES) pen += 15
            pen += if (fam == "swimming") 8 else 20
        }
        for (p in placed) {
            if (p.day == day - 1 || p.day == day + 1) {
                val pfam = families[p.sport]
                if (pfam == fam) pen += 3
                if (pfam in IMPACT_FAMILIES && fam in IMPACT_FAMILIES) pen += 4
                if (hard && p.role in HARD_ROLES) pen += 6
            }
        }
        return pen
    }

    /** `place`: every unplaced session a day, hard ones first, easy ones round-robin by sport. */
    fun place(sessions: List<Session>, days: List<Int>, families: List<String>, maxPerDay: Int) {
        val placed = sessions.filter { it.day >= 0 }.toMutableList()
        val todo = sessions.filter { it.day < 0 }
        val ordered = todo.filter { it.role in HARD_ROLES }.toMutableList()
        val easy = todo.filter { it.role !in HARD_ROLES }
        var rnd = 0
        while (ordered.size < todo.size) {
            for (sport in easy.map { it.sport }.toSortedSet()) {
                val mine = easy.filter { it.sport == sport }
                if (rnd < mine.size) ordered += mine[rnd]
            }
            rnd++
        }
        for (s in ordered) {
            var bestDay = -1
            var bestPen = 0
            for (d in days) {
                val pen = penalty(s, d, placed, families, maxPerDay) ?: continue
                if (bestDay < 0 || pen < bestPen) { bestDay = d; bestPen = pen }
            }
            if (bestDay < 0) continue
            s.day = bestDay
            placed += s
        }
    }

    private fun build(
        weekStart: CivilDate, sessions: List<Session>, ctxs: List<SportCtx>, budgets: List<Double>, phase: String,
        buildIdx: Long, raceM: List<Double>, counters: MutableMap<String, MutableMap<String, Long>>,
        today: CivilDate?, intensities: List<Double>, trimDays: Boolean,
    ): MutableList<MutableMap<String, Any?>> {
        data class Keyed(val day: Int, val order: Int, val sport: Int, val seq: Int, val w: MutableMap<String, Any?>)
        val keyed = mutableListOf<Keyed>()
        for ((i, ctx) in ctxs.withIndex()) {
            val mine = sessions.filter { it.sport == i && it.day >= 0 }
                .sortedWith(compareBy<Session>({ it.day }, { it.order }))
            if (mine.isEmpty()) continue
            val slots = mine.map { CivilDate.fromEpochDay(weekStart.epochDay + it.day) to it.wtype }
            val famCounters = counters.getOrPut(ctx.family) { LinkedHashMap() }
            val built = PlanGenerator.fillSessions(slots, ctx, phase, budgets[i], buildIdx, raceM[i],
                famCounters, today, intensities[i], null, trimDays, allowDoubles = trimDays)
            val used = BooleanArray(mine.size)
            var order = 0
            var role: String
            for ((seq, w) in built.withIndex()) {
                val day = ((w["scheduled_date"] as CivilDate).epochDay - weekStart.epochDay).toInt()
                var match = -1
                if (!(w["title"] as String).startsWith(PlanGenerator.DOUBLE_PREFIX)) {
                    for ((k, s) in mine.withIndex()) {
                        if (!used[k] && s.day == day && s.wtype == w["workout_type"]) { match = k; break }
                    }
                }
                if (match >= 0) {
                    used[match] = true
                    order = mine[match].order
                    role = mine[match].role
                } else {
                    role = "easy"
                }
                if (role == "brick" && w["workout_type"] != "brick_run") {
                    w["title"] = "Brick · " + w["title"]
                    val desc = w["description"] as String?
                    w["description"] = (if (!desc.isNullOrEmpty()) desc + "\n" else "") +
                        "Brick: run straight off this ride — shoes ready, change in under five minutes."
                }
                keyed += Keyed(day, order, i, seq, w)
            }
        }
        return keyed.sortedWith(compareBy<Keyed>({ it.day }, { it.order }, { it.sport }, { it.seq }))
            .map { it.w }.toMutableList()
    }

    private fun budgets(weekTss: Double, shares: List<Double>, sessions: List<Session>): List<Double> {
        val active = shares.indices.map { i -> sessions.any { it.sport == i && it.day >= 0 } }
        var total = 0.0
        for ((i, a) in active.withIndex()) if (a) total += shares[i]
        return shares.indices.map { i -> if (active[i] && total > 0) weekTss * shares[i] / total else 0.0 }
    }

    // ── Multi-sport fitness goals ────────────────────────────────────────────

    /** `recent_hours`: hours of [family] over the eight weeks before [today]. */
    fun recentHours(history: List<HistoryActivity>, family: String, today: CivilDate): Double {
        val cutoff = today.epochDay - 56
        var total = 0.0
        for (act in history) {
            val d = act.startedAt ?: continue
            if (PlanBase.sportFamily(act.sport ?: "") != family) continue
            if (d.epochDay < cutoff || d > today) continue
            val secs = act.durationSeconds
            val dist = act.distanceMeters
            if (secs != null && secs != 0.0) {
                total += secs / 3600
            } else if (dist != null && dist != 0.0) {
                total += dist / 1000 / PlanGenerator.easyPaceKmh(family, null)
            }
        }
        return total
    }

    /** `fitness_shares`: half recent history, half an equal split. */
    fun fitnessShares(families: List<String>, history: List<HistoryActivity>, today: CivilDate): List<Double> {
        val n = families.size
        val hours = families.map { recentHours(history, it, today) }
        var total = 0.0
        for (h in hours) total += h
        if (total <= 0) return List(n) { 1.0 / n }
        return List(n) { i -> 0.5 * hours[i] / total + 0.5 / n }
    }

    private fun fitnessDays(dpw: Long): List<Int> {
        val tpl = PlanBase.applyDaysPerWeek(PlanBase.TEMPLATES.getValue("generic").getValue("base"), dpw, "generic")
        return tpl.indices.filter { tpl[it] != "rest" }
    }

    /**
     * `fitness_multisport_week`. [intensities] and [quality] are per sport, for
     * a sport someone is new to ([PlanStart]): its sessions shortened and no
     * quality for it — the week's quality goes to the other sports instead.
     */
    fun fitnessWeek(
        weekStart: CivilDate, phase: String, weekTss: Double, ctxs: List<SportCtx>, shares: List<Double>,
        dpw: Long, weekNum: Long, buildIdx: Long, counters: MutableMap<String, MutableMap<String, Long>>,
        today: CivilDate?, raceM: Double,
        intensities: List<Double> = List(ctxs.size) { 1.0 }, quality: List<Boolean> = List(ctxs.size) { true },
    ): List<MutableMap<String, Any?>> {
        val n = ctxs.size
        val families = ctxs.map { it.family }
        val days = fitnessDays(dpw)
        val nd = days.size

        val counts = IntArray(n)
        if (nd >= n) {
            counts.fill(1)
            repeat(nd - n) {
                var best = 0
                for (i in 1 until n) {
                    if (shares[i] * nd - counts[i] > shares[best] * nd - counts[best]) best = i
                }
                counts[best]++
            }
        } else {
            val order = (0 until n).sortedWith(compareBy<Int>({ -shares[it] }, { it }))
            val keep = order.take(nd - 1).toMutableList()
            val rest = order.drop(nd - 1)
            keep += rest[weekNum.mod(rest.size.toLong()).toInt()]
            for (i in keep) counts[i] = 1
        }

        var top = Double.NEGATIVE_INFINITY
        for (i in 0 until n) if (counts[i] > 0 && shares[i] > top) top = shares[i]
        val candidates = (0 until n).filter { counts[it] > 0 && shares[it] >= top - 0.1 }
        val longSport = candidates[weekNum.mod(candidates.size.toLong()).toInt()]
        val longDay = if (6 in days) 6 else days.last()

        var qTotal = if (nd <= 1) 0 else minOf(if (phase == "build" || phase == "peak") 2 else 1, maxOf(1, (nd * 0.25 + 0.5).toInt()))
        val q = IntArray(n)
        val byShare = (0 until n).sortedWith(compareBy<Int>({ -shares[it] }, { it }))
        for (rnd in 0 until 7) {
            for (i in byShare) {
                if (qTotal <= 0) break
                if (quality[i] && PlanGenerator.qualityReady(ctxs[i]) && q[i] == rnd &&
                    counts[i] - q[i] - (if (i == longSport) 1 else 0) > 0) { q[i]++; qTotal-- }
            }
        }

        val sessions = mutableListOf<Session>()
        for ((i, ctx) in ctxs.withIndex()) {
            var k = counts[i]
            if (i == longSport) {
                sessions += Session(i, "long", "long", day = longDay, order = i)
                k--
            }
            val qt = qualityTypes(ctx, phase, weekNum)
            for (j in 0 until q[i]) sessions += Session(i, "quality", qt[j % qt.size], order = i)
            repeat(k - q[i]) { sessions += Session(i, "easy", easyType(ctx.family), order = i) }
        }
        place(sessions, days, families, maxPerDay = 1)
        return build(weekStart, sessions, ctxs, budgets(weekTss, shares, sessions), phase, buildIdx,
            List(n) { raceM }, counters, today, intensities, true)
    }

    // ── Triathlon ────────────────────────────────────────────────────────────

    /** `triathlon_week`: [ctxs] are (swim, bike, run). */
    fun triathlonWeek(
        weekStart: CivilDate, weekEnd: CivilDate, phase: String, weekTss: Double, ctxs: List<SportCtx>,
        raceDistanceM: Double, isRaceWeek: Boolean, dpwIn: Long, weekNum: Long, buildIdx: Long,
        counters: MutableMap<String, MutableMap<String, Long>>, today: CivilDate?, intensity: Double,
        quality: Boolean = true,
    ): List<MutableMap<String, Any?>> {
        val s3 = triShares(raceDistanceM)
        val shares = listOf(s3.first, s3.second, s3.third)
        val l3 = triLegs(raceDistanceM)
        val legs = listOf(l3.first, l3.second, l3.third)
        val families = ctxs.map { it.family }
        val dpw = maxOf(1L, minOf(7L, dpwIn))
        val (nSwim, nBike, nRun) = TRI_COUNTS.getValue(dpw)
        val last = (weekEnd.epochDay - weekStart.epochDay).toInt() - (if (isRaceWeek) 1 else 0)
        var days = TRI_DAYS.getValue(dpw).filter { it <= last }
        if (days.isEmpty()) days = (0..last).toList()

        val sessions = mutableListOf<Session>()
        val racing = isRaceWeek
        val longRideDay = if (5 in days) 5 else days.last()
        val brick = !racing && dpw >= 2 && (phase == "build" || phase == "peak" || phase == "taper" || weekNum.mod(2L) == 1L)
        val counts = intArrayOf(nSwim, nBike, nRun)

        if (counts[1] > 0) {
            sessions += Session(1, if (brick) "brick" else if (racing) "easy" else "long",
                if (!racing) "long" else easyType("cycling"), day = longRideDay, order = 10)
            counts[1]--
        }
        if (brick && nRun > 0) sessions += Session(2, "brick", "brick_run", day = longRideDay, order = 11)
        if (counts[2] > 0 && !racing) {
            sessions += Session(2, "long", "long", day = if (6 in days) 6 else -1, order = 2)
            counts[2]--
        }
        if (counts[0] >= 3 && raceDistanceM >= 80_000 && !racing) {
            sessions += Session(0, "long", "long", order = 0)
            counts[0]--
        }

        var want = when (phase) {
            "base" -> intArrayOf(1, 1, 0)
            "build", "peak" -> intArrayOf(1, 1, 1)
            "taper" -> intArrayOf(0, 1, 1)
            else -> intArrayOf(1, 1, 0)
        }
        if (racing) want = intArrayOf(0, 0, 0)
        for (i in 0 until 3) {
            val qt = qualityTypes(ctxs[i], phase, weekNum)
            // No quality in the intro weeks, nor for a runner not yet running continuously.
            val k = if (quality && PlanGenerator.qualityReady(ctxs[i])) minOf(want[i], counts[i]) else 0
            for (j in 0 until k) sessions += Session(i, "quality", qt[j % qt.size], order = i)
            counts[i] -= k
            repeat(counts[i]) { sessions += Session(i, "easy", easyType(families[i]), order = i) }
        }

        place(sessions, days, families, maxPerDay = if (racing) 1 else 2)
        val workouts = build(weekStart, sessions, ctxs, budgets(weekTss, shares, sessions), phase, buildIdx,
            legs, counters, today, List(ctxs.size) { intensity }, false)
        if (isRaceWeek && (today == null || weekEnd >= today)) {
            workouts += linkedMapOf(
                "scheduled_date" to weekEnd, "sport" to "triathlon", "workout_type" to "race",
                "title" to "Race Day", "description" to "Race day. Trust your training.",
                "duration_minutes" to 0L, "distance_meters" to raceDistanceM, "steps" to mutableListOf<Step>(),
            )
        }
        return workouts
    }

    private val TRI_PEAK_TSS = listOf(40_000.0 to 420.0, 80_000.0 to 520.0, 170_000.0 to 650.0)
    private const val TRI_PEAK_TSS_FULL = 850.0
    private const val TRI_START_SHARE = 0.5
    private const val WEEKLY_GAIN_VOLUME = 0.10

    private fun triPeakTss(raceDistanceM: Double): Double {
        for ((limit, tss) in TRI_PEAK_TSS) if (raceDistanceM < limit) return tss
        return TRI_PEAK_TSS_FULL
    }

    /** `_generate_triathlon_plan`: swim, bike and run against one periodised load. */
    internal fun triathlonPlan(
        goal: PlanGoal, history: List<HistoryActivity>, paceBests: List<Pair<Long, Double>>, today: CivilDate,
        ftp: Double?, dpw: Long, planIntensity: Double, baseEffectiveWeeks: Double?, imperial: Boolean, lthr: Long?,
        runningFitness: RunningFitness.Estimate? = null,
    ): PlanGenerator.Generated {
        val raceDate = goal.eventDate!!
        val raceDistanceM = goal.eventDistanceMeters?.takeIf { it != 0.0 } ?: 51_500.0
        val planMonday = CivilDate.fromEpochDay(today.epochDay - (today.epochDay + 3).mod(7L))
        val totalWeeks = maxOf(1L, ceil((raceDate.epochDay - planMonday.epochDay) / 7.0).toLong())

        val vdots = PlanGenerator.runningVdots(runningFitness, paceBests)
        val vdot = vdots.plan
        val css = PlanBase.cssPaceSecPer100m(paceBests)
        val ewBase = PlanGenerator.effectiveWeeksBase("running", vdots.capacity, baseEffectiveWeeks)
        val vdotStep = if (vdot != null) PlanBase.pyMin(WEEKLY_GAIN_VOLUME, 1.5 / maxOf(totalWeeks, 1L)) else 0.0

        var hours = 0.0
        for (fam in listOf("swimming", "cycling", "running")) hours += recentHours(history, fam, today)
        val peak = triPeakTss(raceDistanceM)
        var start = hours / 8 * PlanLoad.tssPerHour(PlanLoad.EASY_IF)
        // No swim/bike/run history: an unanswered newcomer's intro weeks (plan.py).
        val noHistory = start <= 0
        if (noHistory) start = peak * TRI_START_SHARE
        val maxTss = PlanBase.pyMax(peak, start)
        val runKm = PlanBase.currentWeeklyKm(history, "running", today)

        val workouts = mutableListOf<MutableMap<String, Any?>>()
        val counters = LinkedHashMap<String, MutableMap<String, Long>>()
        val weekly = mutableListOf<Double>()
        var buildIdx = 0L
        for (weekNum in 0 until totalWeeks) {
            val weekStart = CivilDate.fromEpochDay(planMonday.epochDay + weekNum * 7)
            val isRaceWeek = weekNum == totalWeeks - 1
            val weekEnd = if (isRaceWeek) raceDate else CivilDate.fromEpochDay(weekStart.epochDay + 6)
            val phase = PlanBase.phaseForWeek(weekNum, totalWeeks)
            var tss = PlanBase.weeklyVolumeKm(weekNum, totalWeeks, start, maxTss,
                (raceDate.epochDay - weekStart.epochDay) / 7.0)
            if (weekNum > 0 && buildIdx > 0 && (phase == "build" || phase == "peak") && weekly.isNotEmpty()) {
                tss = PlanBase.pyMin(tss, weekly.last() * (if (phase == "build") 1.05 else 1.03))
            }
            if (weekly.size >= 4) {
                val chronic = PyMath.sum(weekly.takeLast(4)) / 4
                if (chronic > 0 && tss / chronic > 1.3) tss = PyMath.round(chronic * 1.3, 1)
            }
            weekly += tss

            val weekVdot = if (vdot != null) vdot + weekNum * vdotStep else null
            val paces = PlanBase.vdotToPaces(weekVdot?.takeIf { it != 0.0 } ?: vdots.pace)
            val capKm = PlanGenerator.runningCapacityKm(ewBase + weekNum * (dpw / 5.0), runKm)
            val ctxs = listOf(
                SportCtx(family = "swimming", sport = "swimming", css = css, lthr = lthr, imperial = imperial),
                SportCtx(family = "cycling", sport = "cycling", ftp = ftp, lthr = lthr,
                    cyclingDiscipline = "time_trial", imperial = imperial),
                SportCtx(family = "running", sport = "running", paces = paces, lthr = lthr,
                    capacityKm = capKm, imperial = imperial),
            )
            workouts += triathlonWeek(weekStart, weekEnd, phase, tss, ctxs, raceDistanceM, isRaceWeek, dpw,
                weekNum, buildIdx, counters, today, planIntensity,
                quality = PlanStart.startQuality(null, weekNum, noHistory))
            if (weekNum.mod(4L) != 3L && (phase == "build" || phase == "peak")) buildIdx++
        }
        return PlanGenerator.Generated(vdot, workouts)
    }
}
