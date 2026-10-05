// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.coaching

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.spec.SpecFixtures
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays `spec/fixtures/coaching.json` — the server's own coaching engine —
 * and requires the port to agree exactly: the same context read off the same
 * history, the same situation chosen, the same paragraph variant with the same
 * numbers in it.
 *
 * A phone with no server still gets a coach; it must be the same coach the
 * server would have been, or the advice changes with the device in hand.
 */
class CoachingFixtureTest {
    private val corpus = SpecFixtures.load("coaching")

    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun arr(e: JsonElement?): List<JsonElement> = (e as JsonArray).toList()
    private fun date(v: Any?): CivilDate? =
        (v as String?)?.split("-")?.let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
    private fun num(v: Any?): Double? = when (v) { null -> null; is Long -> v.toDouble(); else -> v as Double }

    @Suppress("UNCHECKED_CAST")
    private fun activities(v: Any?): List<CoachingActivity> = (v as List<Map<String, Any?>>).map {
        CoachingActivity(it["sport"] as String?, date(it["started"]), it["duration_seconds"] as Long?)
    }

    /** A recommendation in the server's `asdict` vocabulary. */
    private fun rec(r: WorkoutRecommendation?): Map<String, Any?>? = r?.let {
        mapOf(
            "sport" to it.sport, "intensity" to it.intensity, "duration_minutes" to it.durationMinutes,
            "distance_km" to it.distanceKm, "hr_min" to it.hrMin, "hr_max" to it.hrMax,
            "description" to it.description, "reasoning" to it.reasoning, "projected_tss" to it.projectedTss,
            "modality" to it.modality, "title" to it.title, "focus" to it.focus, "situation" to it.situation,
        )
    }

    private fun ctx(c: RecommenderContext): Map<String, Any?> = mapOf(
        "today" to c.today.isoformat(), "ctl" to c.ctl, "atl" to c.atl, "tsb" to c.tsb, "ctl_ramp" to c.ctlRamp,
        "injury_risk" to c.injuryRisk, "readiness" to c.readiness, "readiness_confidence" to c.readinessConfidence,
        "dominant_family" to c.dominantFamily, "alt_family" to c.altFamily, "cardio_families" to c.cardioFamilies,
        "consecutive_training_days" to c.consecutiveTrainingDays, "same_sport_streak" to c.sameSportStreak,
        "last_family" to c.lastFamily, "last_modality" to c.lastModality, "last_session_long" to c.lastSessionLong,
        "last_session_posterior" to c.lastSessionPosterior, "days_since_cardio" to c.daysSinceCardio,
        "days_since_strength" to c.daysSinceStrength, "days_since_mobility" to c.daysSinceMobility,
        "strength_sessions_7d" to c.strengthSessions7d, "strength_sessions_14d" to c.strengthSessions14d,
        "cardio_sessions_7d" to c.cardioSessions7d, "mobility_sessions_14d" to c.mobilitySessions14d,
        "total_sessions_14d" to c.totalSessions14d, "base_duration" to c.baseDuration,
        "threshold_hr" to c.thresholdHr, "has_history" to c.hasHistory,
    )

    /** Readiness arrives as an int or a float; the engine reads it as a number. */
    private fun normalise(m: Map<String, Any?>): Map<String, Any?> =
        m.mapValues { (k, v) -> if (k == "readiness" && v is Long) v.toDouble() else v }

    @Test
    fun today_s_recommendations_match_the_server_word_for_word() {
        for ((i, c) in arr(corpus["recommendations"]).withIndex()) {
            val o = c.jsonObject
            @Suppress("UNCHECKED_CAST")
            val g = d(o["goal"]) as Map<String, Any?>?
            val goal = g?.let {
                CoachingGoal(it["goal_type"] as String?, date(it["event_date"]), (it["ctl_ramp_per_week"] as Number?)?.toDouble())
            }
            val today = date(d(o["today"]))!!
            val acts = activities(d(o["activities"]))
            @Suppress("UNCHECKED_CAST")
            val sessions = (d(o["sessions"]) as List<String>).map { date(it)!! }
            val readiness = num(d(o["readiness"]))!!
            val confidence = d(o["confidence"]) as String
            val lthr = num(d(o["lthr"]))
            val res = Coaching.computeRecommendations(
                readiness, confidence, num(d(o["ctl"]))!!, num(d(o["atl"]))!!, num(d(o["ctl7"])),
                acts, goal, lthr, today, (d(o["n"]) as Long).toInt(), sessions,
            )
            @Suppress("UNCHECKED_CAST")
            val signal = d(o["signal"]) as Map<String, Any?>
            assertEquals(num(signal["ctl"]), res.signal.ctl, "case $i ctl")
            assertEquals(num(signal["atl"]), res.signal.atl, "case $i atl")
            assertEquals(num(signal["tsb"]), res.signal.tsb, "case $i tsb")
            assertEquals(num(signal["ctl_ramp"]), res.signal.ctlRamp, "case $i ramp")
            assertEquals(signal["injury_risk_warning"], res.signal.injuryRiskWarning, "case $i warning")
            assertEquals(signal["phase"], res.signal.phase, "case $i phase")

            val context = Coaching.buildContext(
                readiness, confidence, num(d(o["ctl"]))!!, num(d(o["atl"]))!!, res.signal.tsb, res.signal.ctlRamp,
                res.signal.injuryRiskWarning, acts, sessions, lthr, today,
            )
            @Suppress("UNCHECKED_CAST")
            assertEquals(normalise(d(o["context"]) as Map<String, Any?>), ctx(context), "case $i context")
            assertEquals(d(o["recommendations"]), res.recommendations.map(::rec), "case $i recommendations")
        }
    }

    @Test
    fun the_weekly_projection_feeds_each_day_into_the_next_like_the_server() {
        for ((i, c) in arr(corpus["weekly"]).withIndex()) {
            val o = c.jsonObject
            @Suppress("UNCHECKED_CAST")
            val tss = (d(o["tss"]) as List<List<Any?>>).associate { date(it[0])!! to num(it[1])!! }
            val plan = Coaching.computeWeeklyPlan(
                num(d(o["readiness"]))!!, "high", tss, activities(d(o["activities"])), null,
                num(d(o["lthr"])), date(d(o["today"]))!!,
            )
            val got = plan.map {
                mapOf("date" to it.date.isoformat(), "ctl" to it.ctl, "atl" to it.atl, "tsb" to it.tsb,
                    "recommendation" to rec(it.recommendation))
            }
            assertEquals(d(o["plan"]), got, "weekly case $i")
        }
    }

    @Test
    fun paragraphs_render_the_same_variant_with_the_same_numbers() {
        for (c in arr(corpus["render"])) {
            val o = c.jsonObject
            @Suppress("UNCHECKED_CAST")
            val slots = d(o["slots"]) as Map<String, Any?>
            for (x in arr(o["cases"])) {
                val (key, form, seed, want) = d(x) as List<*>
                val s = if ((seed as String).startsWith("tsb")) slots + ("tsb" to seed.removePrefix("tsb").toDouble()) else slots
                val renderSeed = if (seed.startsWith("tsb")) "2026-01-01" else seed
                assertEquals(want, Coaching.render(key as String, form as String, s, renderSeed), "$key/$form/$seed")
            }
        }
    }

    @Test
    fun ctl_and_atl_build_from_zero_like_the_server() {
        for (c in arr(corpus["ctl_atl"])) {
            val o = c.jsonObject
            @Suppress("UNCHECKED_CAST")
            val tss = (d(o["tss"]) as List<List<Any?>>).associate { date(it[0])!! to num(it[1])!! }
            val got = Coaching.computeCtlAtl(tss, date(d(o["end"]))!!)
            val want = d(o["expect"]) as List<*>?
            assertEquals(want?.map(::num), got?.toList(), "$o")
        }
    }
}
