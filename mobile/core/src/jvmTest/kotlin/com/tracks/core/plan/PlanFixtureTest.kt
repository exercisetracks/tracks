// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays `spec/fixtures/plan.json` — the server's own endurance planner,
 * assembly passes and fitness fingerprint — and requires the port to agree
 * exactly: the same workouts on the same days, the same step structures, the
 * same notes character for character.
 *
 * Exactness is the point. A plan regenerated on the phone replaces the one
 * the server made (a new `generation`), so any difference is a different plan
 * for the same athlete depending on which device they happened to hold.
 *
 * Values keep their Python types: `30` and `30.0` differ, because the
 * server's JSON writes them differently and the watch encoder reads them.
 */
class PlanFixtureTest {
    private val corpus = SpecFixtures.load("plan")
    private val helpers = corpus["helpers"]!!.jsonObject

    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun arr(e: JsonElement?): List<JsonElement> = (e as JsonArray).toList()
    private fun obj(e: JsonElement?): JsonObject = e!!.jsonObject

    private fun date(v: Any?): CivilDate? =
        (v as String?)?.split("-")?.let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    /** The port's output in the corpus's vocabulary: dates as ISO, ints as Long. */
    private fun plain(v: Any?): Any? = when (v) {
        is CivilDate -> v.isoformat()
        is Int -> v.toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        is Set<*> -> v.map(::plain)
        is Pair<*, *> -> listOf(plain(v.first), plain(v.second))
        is Triple<*, *, *> -> listOf(plain(v.first), plain(v.second), plain(v.third))
        else -> v
    }

    /** The corpus's workouts as the assembly passes take them: dates parsed back. */
    @Suppress("UNCHECKED_CAST")
    private fun workoutsIn(e: JsonElement?): MutableList<MutableMap<String, Any?>> =
        (d(e) as List<Map<String, Any?>>).map { w ->
            LinkedHashMap(w).also { it["scheduled_date"] = date(w["scheduled_date"]) }
        }.toMutableList()

    private fun num(v: Any?): Double? = when (v) { null -> null; is Long -> v.toDouble(); else -> v as Double }
    private fun long(v: Any?): Long? = when (v) { null -> null; is Double -> v.toLong(); else -> v as Long }

    @Test
    fun paces_and_distances_print_as_the_server_prints_them() {
        for (c in arr(helpers["fmt_pace"])) {
            val o = obj(c)
            assertEquals(d(o["expect"]), PlanBase.fmtPace(num(d(o["sec"]))!!, d(o["imperial"]) as Boolean), "$o")
        }
        for (c in arr(helpers["fmt_dist"])) {
            val o = obj(c)
            assertEquals(d(o["expect"]), PlanBase.fmtDistM(d(o["m"]) as Long, d(o["imperial"]) as Boolean), "$o")
        }
    }

    @Test
    fun vdot_paces_match_to_the_tenth() {
        for (c in arr(helpers["vdot_paces"])) {
            val o = obj(c)
            assertEquals(d(o["expect"]), PlanBase.vdotToPaces(num(d(o["vdot"]))!!), "$o")
        }
        for (c in arr(helpers["calculate_vdot"])) {
            val o = obj(c)
            val got = PlanBase.calculateVdot(num(d(o["m"]))!!, num(d(o["s"]))!!)
            assertUlps(num(d(o["expect"]))!!, got, "$o")
        }
    }

    @Test
    fun phases_volumes_and_capacity_match() {
        for (c in arr(helpers["phases"])) {
            val (w, t, p) = d(c) as List<*>
            assertEquals(p, PlanBase.phaseForWeek(w as Long, t as Long), "$c")
        }
        for (c in arr(helpers["volume"])) {
            val x = d(c) as List<*>
            val got = PlanBase.weeklyVolumeKm(x[0] as Long, x[1] as Long, num(x[2])!!, num(x[3])!!, num(x[4])!!, x[5] as Long)
            assertEquals(num(x[6]), got, "$c")
        }
        for (c in arr(helpers["capacity"])) {
            val (ew, cap) = d(c) as List<*>
            assertEquals(num(cap), PlanBase.capacityKm(num(ew)!!), "$c")
        }
    }

    @Test
    fun templates_rotate_and_trim_to_the_day_count_like_the_server() {
        for (c in arr(helpers["days_per_week"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val t = d(o["template"]) as List<String>
            assertEquals(d(o["expect"]), PlanBase.applyDaysPerWeek(t, d(o["dpw"]) as Long, d(o["family"]) as String), "$o")
        }
        for (c in arr(helpers["rotating"])) {
            val x = d(c) as List<*>
            val got = PlanGenerator.rotatingTemplate(x[0] as String, x[1] as String, x[2] as Long, x[3] as String, x[4] as String)
            assertEquals(x[5], got, "$c")
        }
    }

    @Test
    fun the_small_lookups_match() {
        for (c in arr(helpers["css"])) {
            val o = obj(c)
            assertEquals(num(d(o["expect"])), PlanBase.cssPaceSecPer100m(bests(d(o["bests"]))), "$o")
        }
        for (c in arr(helpers["best_vdot"])) {
            val o = obj(c)
            val want = num(d(o["expect"]))
            val got = PlanBase.bestVdotFromPaceBests(bests(d(o["bests"])))
            if (want == null) assertEquals(null, got) else assertUlps(want, got!!, "$o")
        }
        for (c in arr(helpers["peak_long"])) {
            val x = d(c) as List<*>
            assertEquals(num(x[2]), PlanBase.targetPeakLongKm(num(x[0])!!, x[1] as String), "$c")
        }
        for (c in arr(helpers["max_weekly"])) {
            val x = d(c) as List<*>
            assertEquals(num(x[2]), PlanBase.maxWeeklyKmForRace(num(x[0])!!, x[1] as String), "$c")
        }
        for (c in arr(helpers["families"])) {
            val (s, f) = d(c) as List<*>
            assertEquals(f, PlanBase.sportFamily(s as String), "$c")
        }
        for (c in arr(helpers["titles"])) {
            val x = d(c) as List<*>
            val got = PlanBase.workoutTitle(x[0] as String, x[1] as String, num(x[2]), x[3] as Long, x[4] as Boolean)
            assertEquals(x[5], got, "$c")
        }
        for (c in arr(helpers["polarisation"])) {
            val (t, want) = d(c) as List<*>
            @Suppress("UNCHECKED_CAST")
            val got = PlanGenerator.polarisationCheck(t as List<String>)
            val w = want as List<*>
            assertEquals(listOf(w[0], w[1], num(w[2])), listOf(got.first.toLong(), got.second.toLong(), got.third), "$c")
        }
        for (c in arr(helpers["monotony"])) {
            val (loads, want) = d(c) as List<*>
            assertEquals(want, plain(PlanGenerator.monotonyScores((loads as List<*>).map { num(it)!! })), "$c")
        }
    }

    @Test
    fun mtb_skills_are_drawn_like_cpython_draws_them() {
        for (c in arr(corpus["skills"])) {
            val x = d(c) as List<*>
            val got = Mtb.pickSkills(x[0] as String, (x[1] as Long).toInt(), x[2] as Long)
            assertEquals(x[3], plain(got), "$c")
        }
    }

    @Test
    fun whole_plans_match_the_server_workout_for_workout() {
        var workouts = 0
        for (c in arr(corpus["plans"])) {
            val o = obj(c)
            val name = d(o["name"])
            @Suppress("UNCHECKED_CAST")
            val g = d(o["goal"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val kw = d(o["kw"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val history = historyIn(o["history"])
            val goal = PlanGoal(
                eventDate = date(g["event_date"]),
                eventSport = g["event_sport"] as String?,
                eventDistanceMeters = num(g["event_distance_meters"]),
                daysPerWeek = long(g["days_per_week"]),
                planIntensity = num(g["plan_intensity"]),
                mtbDiscipline = g["mtb_discipline"] as String?,
                cyclingDiscipline = g["cycling_discipline"] as String?,
            )
            val got = PlanGenerator.generateTrainingPlan(
                goal, history, bests(d(o["bests"])), date(d(o["today"]))!!,
                ftp = num(kw["ftp"]), daysPerWeek = long(kw["days_per_week"]),
                baseEffectiveWeeks = num(kw["base_effective_weeks"]),
                imperial = kw["imperial"] as Boolean? ?: false, thresholdHr = num(kw["threshold_hr"]),
                runningFitness = runningFitness(kw["running_fitness"]),
            )
            val wantVdot = num(d(o["vdot"]))
            if (wantVdot == null) assertEquals(null, got.vdot, "$name") else assertUlps(wantVdot, got.vdot!!, "$name")
            @Suppress("UNCHECKED_CAST")
            val want = d(o["workouts"]) as List<Any?>
            val have = plain(got.workouts) as List<Any?>
            assertEquals(want.size, have.size, "$name: workout count")
            for (i in want.indices) assertEquals(want[i], have[i], "$name: workout $i")
            workouts += want.size
        }
        assertTrue(workouts > 1500, "the corpus should exercise well over a thousand workouts, had $workouts")
    }

    @Test
    fun field_tests_land_on_the_same_sessions() {
        val plans = arr(corpus["plans"]).associateBy { d(obj(it)["name"]) }
        for (c in arr(corpus["field_tests"])) {
            val o = obj(c)
            val source = workoutsIn(obj(plans.getValue(d(o["plan"])))["workouts"])
            val got = PlanAssembly.injectFieldTests(source, d(o["sport"]) as String?)
            assertEquals(d(o["expect"]), plain(got), "${d(o["plan"])}")
        }
    }

    @Test
    fun custom_workouts_take_over_the_same_strength_days() {
        for (c in arr(corpus["custom"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val customs = (d(o["customs"]) as List<List<Any?>>).map {
                PlanAssembly.CustomWorkout(it[0]!!, it[1] as String, (it[2] as List<String>).toSet(), it[3] as List<Map<String, Any?>>)
            }
            val got = PlanAssembly.attachCustomWorkouts(
                workoutsIn(o["existing"]), workoutsIn(o["strength"]), customs, (d(o["recent"]) as List<*>).toSet() as Set<Any>,
            )
            assertEquals(d(o["expect"]), plain(got), "${d(o["name"])}")
        }
    }

    @Test
    fun stretch_flows_go_on_the_same_days_with_the_same_titles() {
        for (c in arr(corpus["stretch"])) {
            val o = obj(c)
            val calls = mutableListOf<List<Any?>>()
            // The same stub the generator used: its answer depends only on how
            // many times it has been called, so the call order is checked too.
            val got = PlanAssembly.injectStretchFlows(workoutsIn(o["workouts"])) { r ->
                calls += listOf(r.sport, r.varietyKey, r.isStrength, r.primaryMuscles, r.cooldownTheme)
                val n = (calls.size % 3) + 1
                val steps = (0 until n).map { i ->
                    linkedMapOf<String, Any?>(
                        "exercise_name" to "S$i", "duration_seconds" to (30L + 15 * i),
                        "sets" to (1L + i % 2), "each_side" to (i % 2 == 0),
                    )
                }.toMutableList<Map<String, Any?>>()
                if (r.varietyKey.endsWith("-09")) steps += mapOf("exercise_name" to "defaulted")
                PlanAssembly.FlowMeta(
                    steps,
                    if (calls.size % 2 == 1) null else "Flow ${calls.size}",
                    if (calls.size % 4 == 0) "" else "tag ${calls.size}",
                )
            }
            assertEquals(d(o["calls"]), plain(calls), "stretch planner calls")
            assertEquals(d(o["expect"]), plain(got))
        }
    }

    @Test
    fun the_fitness_fingerprint_advances_like_the_server() {
        val fp = obj(corpus["fingerprint"])
        for (c in arr(fp["completion"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val a = d(o["activity"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val w = d(o["workout"]) as Map<String, Any?>
            val got = PlanAssembly.completionPct(
                PlanAssembly.PlannedTarget(w["workout_type"] as String, num(w["distance_meters"]), long(w["duration_minutes"])),
                PlanAssembly.DoneActivity(a["sport"] as String?, num(a["distance_meters"]), long(a["duration_seconds"])),
            )
            assertEquals(num(d(o["expect"])), got, "$o")
        }
        for (seq in arr(fp["fold"])) {
            var state = PlanAssembly.Fingerprint()
            for (s in arr(seq)) {
                val o = obj(s)
                @Suppress("UNCHECKED_CAST")
                val a = d(o["activity"]) as Map<String, Any?>
                state = PlanAssembly.advanceFingerprint(
                    state, d(o["family"]) as String,
                    PlanAssembly.DoneActivity(null, num(a["distance_meters"]), long(a["duration_seconds"])),
                    PlanAssembly.PlannedTarget(d(o["type"]) as String, null, null),
                    num(d(o["pct"]))!!,
                )
                @Suppress("UNCHECKED_CAST")
                val want = d(o["expect"]) as Map<String, Any?>
                assertEquals(num(want["effective_weeks"]), state.effectiveWeeks, "$o")
                assertEquals(num(want["vdot"]), state.vdot, "$o")
                assertEquals(want["sessions"], state.sessionsCompleted, "$o")
            }
        }
    }

    @Test
    fun stamping_gives_every_workout_the_plans_new_generation() {
        val ws = mutableListOf<MutableMap<String, Any?>>(linkedMapOf("title" to "a"), linkedMapOf("title" to "b"))
        val out = PlanAssembly.stamp(52.349, ws, "0192f3a0-0000-7000-8000-000000000001")
        assertEquals(52.3, out.vdot)
        assertTrue(out.workouts.all { it["generation"] == out.generation })
    }

    @Test
    fun a_fitness_goals_weekly_load_matches_the_server() {
        for (c in arr(obj(corpus["fitness"])["targets"])) {
            val o = obj(c)
            val got = FitnessPlan.weekTargets(
                num(d(o["ctl"]))!!, num(d(o["atl"]))!!, num(d(o["ramp"])), date(d(o["today"]))!!, date(d(o["anchor"])),
            ).map {
                mapOf("week_start" to it.weekStart.isoformat(), "recovery" to it.recovery,
                    "ramp" to it.ramp, "tss" to it.tss, "ctl_end" to it.ctlEnd)
            }
            @Suppress("UNCHECKED_CAST")
            val want = (d(o["expect"]) as List<Map<String, Any?>>).map { m -> m.mapValues { (k, v) -> if (k == "tss" || k == "ctl_end" || k == "ramp") num(v) else v } }
            assertEquals(want, got, "$o")
        }
    }

    /** A fitness plan rebuilt on the phone replaces the server's, so it must be the server's. */
    @Test
    fun fitness_plans_match_the_server_workout_for_workout() {
        var workouts = 0
        for (c in arr(obj(corpus["fitness"])["plans"])) {
            val o = obj(c)
            val name = d(o["name"])
            @Suppress("UNCHECKED_CAST")
            val g = d(o["goal"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val kw = d(o["kw"]) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val got = FitnessPlan.generate(
                PlanGoal(
                    eventDate = null,
                    eventSport = g["event_sport"] as String?,
                    daysPerWeek = long(g["days_per_week"]),
                    mtbDiscipline = g["mtb_discipline"] as String?,
                    cyclingDiscipline = g["cycling_discipline"] as String?,
                    ctlRampPerWeek = num(g["ctl_ramp_per_week"]),
                    fitnessSports = g["fitness_sports"] as List<String>?,
                ),
                bests(d(o["bests"])), date(d(o["today"]))!!, num(d(o["ctl"]))!!, num(d(o["atl"]))!!,
                anchor = date(d(o["anchor"])),
                ftp = num(kw["ftp"]), daysPerWeek = long(kw["days_per_week"]),
                baseEffectiveWeeks = num(kw["base_effective_weeks"]),
                imperial = kw["imperial"] as Boolean? ?: false, thresholdHr = num(kw["threshold_hr"]),
                history = historyIn(o["history"]),
                runningFitness = runningFitness(kw["running_fitness"]),
            )
            val wantVdot = num(d(o["vdot"]))
            if (wantVdot == null) assertEquals(null, got.vdot, "$name") else assertUlps(wantVdot, got.vdot!!, "$name")
            @Suppress("UNCHECKED_CAST")
            val want = d(o["workouts"]) as List<Any?>
            val have = plain(got.workouts) as List<Any?>
            assertEquals(want.size, have.size, "$name: workout count")
            for (i in want.indices) assertEquals(want[i], have[i], "$name: workout $i")
            workouts += want.size
        }
        assertTrue(workouts > 150, "the fitness corpus should exercise a few hundred workouts, had $workouts")
    }

    /** The week is sized until this estimate carries its load, so it must be the server's to the bit. */
    @Test
    fun a_workouts_load_is_estimated_as_the_server_estimates_it() {
        var n = 0
        for (c in arr(corpus["load"])) {
            val o = obj(c)
            @Suppress("UNCHECKED_CAST")
            val steps = d(o["steps"]) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val paces = (d(o["paces"]) as Map<String, Any?>?)?.mapValues { num(it.value)!! }
            assertEquals(num(d(o["expect"])), PlanLoad.workoutTss(steps, paces), "load case $n")
            n++
        }
        assertTrue(n > 500, "the load corpus should cover hundreds of workouts, had $n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun historyIn(e: JsonElement?): List<HistoryActivity> =
        (d(e) as List<Map<String, Any?>>?).orEmpty().map {
            HistoryActivity(it["sport"] as String?, date(it["started"]), num(it["distance"]), num(it["duration"]))
        }

    /** A case's `running_fitness` kwarg, as the API passes the estimate in. */
    private fun runningFitness(v: Any?): RunningFitness.Estimate? {
        @Suppress("UNCHECKED_CAST") val m = v as Map<String, Any?>? ?: return null
        return RunningFitness.Estimate(num(m["vdot"])!!, m["source"] as String, m["measured"] as Boolean,
            num(m["effort_vdot"]))
    }

    private fun bests(v: Any?): List<Pair<Long, Double>> =
        (v as List<*>).map { val p = it as List<*>; (p[0] as Long) to num(p[1])!! }

    /**
     * Raw VDOT goes through `exp`, whose last place differs between the JVM
     * and glibc; anything derived from it is rounded and compared exactly.
     */
    private fun assertUlps(want: Double, got: Double, message: String) {
        val ulps = kotlin.math.abs(want.toRawBits() - got.toRawBits())
        assertTrue(ulps <= 4, "$message: $got is $ulps ulps from $want")
    }
}
