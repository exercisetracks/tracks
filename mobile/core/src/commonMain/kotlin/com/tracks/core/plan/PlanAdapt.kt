// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import kotlin.math.pow

/**
 * Port of `backend/app/calculators/plan/adapt.py`: the next few days of
 * endurance sessions adjusted to the form each will be done in -- shortened
 * and made easy, moved, or dropped. The Python explains the rules and
 * docs/adaptive-plan-research.md the reasoning; `spec/fixtures/plan_adapt.json`
 * holds the two to the same answers.
 *
 * Workouts are the generator's maps, `scheduled_date` a [CivilDate].
 */
object PlanAdapt {

    /** One activity of the last few days: its local day, sport and TSS. */
    data class Recent(val day: CivilDate, val sport: String?, val tss: Double)

    private const val CTL_DECAY = 41.0 / 42.0
    private const val ATL_DECAY = 6.0 / 7.0

    const val SHORTEN_TSB = -20.0
    const val MOVE_TSB = -30.0
    const val BIG_SESSION_RATIO = 2.0
    private const val BIG_SESSION_DAYS = 2L
    const val LOW_IMPACT_WEIGHT = 0.7
    const val WINDOW_DAYS = 3L
    private const val LOW_IMPACT_DAYS = 3L
    private const val SHORTEN_FACTOR = 0.7
    private const val MIN_SHORTENED_MIN = 20L
    private const val MAX_MOVE_DAYS = 2L

    private val IMPACT = setOf("running", "hiking", "trail_running")
    private val LOW_IMPACT = setOf("cycling", "mountain_biking", "swimming", "rowing", "nordic_skiing")
    private val NOT_ENDURANCE = setOf(
        "strength_training", "strength", "flexibility_training", "flexibility",
        "mobility", "field_test", "race",
    )

    /** `week.easy_session`, exactly. */
    private val EASY = mapOf(
        "running" to "easy", "cycling" to "endurance", "mountain_biking" to "endurance",
        "swimming" to "aerobic", "rowing" to "ut2", "hiking" to "easy",
        "nordic_skiing" to "endurance", "alpine_skiing" to "aerobic", "climbing" to "arc",
    )

    private fun easySession(family: String) = EASY[family] ?: "easy"

    private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun adaptToFatigue(
        workouts: List<Map<String, Any?>>,
        today: CivilDate,
        ctl: Double,
        atl: Double,
        recent: List<Recent>,
        imperial: Boolean = false,
    ): List<Map<String, Any?>> {
        val rec = recent.filter { it.day <= today }.map { it.copy(sport = (it.sport ?: "").lowercase()) }
        val out = workouts.map { LinkedHashMap(it) }
        if (ctl <= 0) return out

        val windowEnd = today.plusDays(WINDOW_DAYS - 1)
        val candidates = out.filter { adjustable(it) && date(it) >= today && date(it) <= windowEnd }
            .sortedWith(compareBy({ date(it).epochDay }, { (it["workout_type"] as? String) ?: "" }))
        val dropped = mutableListOf<Map<String, Any?>>()
        var bigAnswered = false
        for (w in candidates) {
            val day = date(w)
            val family = PlanBase.sportFamily(((w["sport"] as? String) ?: "").lowercase())
            var tsb = formOn(day, today, ctl, atl, rec, family)
            val big = if (bigAnswered) null else bigUnplanned(day, rec, ctl, family)
            bigAnswered = bigAnswered || big != null
            if (tsb < MOVE_TSB || big != null) {
                val target = freeDay(out, w)
                if (target == null) {
                    dropped += w
                    continue
                }
                w["scheduled_date"] = target
                val why = if (big != null) "after $big in the last two days"
                else "you are carrying a lot of fatigue (form ${roundInt(tsb)})"
                note(w, "Moved to ${DAY_NAMES[target.weekday]}: $why.")
                tsb = formOn(target, today, ctl, atl, rec, family)
                if (tsb < SHORTEN_TSB) shorten(w, out, family, imperial, "form ${roundInt(tsb)}")
            } else if (tsb < SHORTEN_TSB) {
                shorten(w, out, family, imperial, "form ${roundInt(tsb)}")
            }
        }
        return out.filter { w -> dropped.none { it === w } }
    }

    private fun date(w: Map<String, Any?>) = w["scheduled_date"] as CivilDate

    private fun CivilDate.plusDays(n: Long) = CivilDate.fromEpochDay(epochDay + n)

    /** Python's `date.weekday()`: Monday 0. 1970-01-01 was a Thursday. */
    private val CivilDate.weekday: Int get() = (epochDay + 3).mod(7L).toInt()

    private fun roundInt(x: Double): Long = kotlin.math.round(x).toLong()

    private fun adjustable(w: Map<String, Any?>): Boolean {
        val wtype = ((w["workout_type"] as? String) ?: "").lowercase()
        val sport = ((w["sport"] as? String) ?: "").lowercase()
        if (wtype in NOT_ENDURANCE || sport in NOT_ENDURANCE) return false
        return w["is_complete"] != true
    }

    private fun formOn(day: CivilDate, today: CivilDate, ctl: Double, atl: Double, recent: List<Recent>, family: String): Double {
        val ahead = (day.epochDay - today.epochDay).toDouble()
        val ctlD = ctl * CTL_DECAY.pow(ahead)
        var atlD = atl * ATL_DECAY.pow(ahead)
        if (family in IMPACT) {
            val since = today.plusDays(-(LOW_IMPACT_DAYS - 1))
            for (r in recent) {
                if (r.day < since || PlanBase.sportFamily(r.sport ?: "") !in LOW_IMPACT) continue
                val share = r.tss * (1 - ATL_DECAY) * ATL_DECAY.pow((day.epochDay - r.day.epochDay).toDouble())
                atlD -= (1 - LOW_IMPACT_WEIGHT) * share
            }
        }
        return ctlD - atlD
    }

    private fun bigUnplanned(day: CivilDate, recent: List<Recent>, ctl: Double, family: String): String? {
        val since = day.plusDays(-BIG_SESSION_DAYS)
        for (r in recent) {
            if (r.day < since || r.day > day) continue
            val sport = r.sport ?: ""
            if (PlanBase.sportFamily(sport) == family) continue
            if (r.tss > BIG_SESSION_RATIO * ctl) return "a big ${sport.replace('_', ' ')} session"
        }
        return null
    }

    private fun freeDay(workouts: List<MutableMap<String, Any?>>, w: Map<String, Any?>): CivilDate? {
        val day = date(w)
        val weekEnd = day.plusDays((6 - day.weekday).toLong())
        for (step in 1..MAX_MOVE_DAYS) {
            val target = day.plusDays(step)
            if (target > weekEnd) break
            val busy = workouts.any { o -> o !== w && date(o) == target && adjustable(o) }
            if (!busy) return target
        }
        return null
    }

    private fun shorten(w: MutableMap<String, Any?>, workouts: List<Map<String, Any?>>, family: String, imperial: Boolean, why: String) {
        val minutes = (w["duration_minutes"] as? Number)?.toLong() ?: 0L
        if (minutes <= 0) return
        val target = maxOf(MIN_SHORTENED_MIN, kotlin.math.round(minutes * SHORTEN_FACTOR / 5.0).toLong() * 5)
        if (target >= minutes) return
        val easy = easySession(family)
        @Suppress("UNCHECKED_CAST")
        val steps = (w["steps"] as? List<Map<String, Any?>>).orEmpty()
        val warm = steps.filter { it["type"] == "walk" }
        val walkMin = warm.sumOf { (it["duration_min"] as? Number)?.toLong() ?: 0L }
        val mainMin = maxOf(5L, target - walkMin)
        val main = LinkedHashMap(easyStep(workouts, family)).apply { this["duration_min"] = mainMin }
        val newSteps = buildList {
            if (warm.isNotEmpty()) add(warm.first())
            add(main)
            if (warm.size > 1) add(warm.last())
        }
        val speed = easySpeed(workouts, family)
        val distance = speed?.let { kotlin.math.round(it * (mainMin / 60.0) * 1000).toLong() }
        val duration = newSteps.sumOf { (it["duration_min"] as? Number)?.toLong() ?: 0L }
        w["workout_type"] = easy
        w["steps"] = newSteps
        w["duration_minutes"] = duration
        w["distance_meters"] = distance
        w["title"] = PlanBase.workoutTitle(easy, family, distance?.toDouble(), duration, imperial)
        w["description"] = PlanBase.workoutDescription(newSteps)
        note(w, "Shortened and made easy: you are carrying fatigue ($why).")
    }

    private fun easyStep(workouts: List<Map<String, Any?>>, family: String): Map<String, Any?> {
        val easy = easySession(family)
        for (o in workouts) {
            if (o["workout_type"] == easy && PlanBase.sportFamily(((o["sport"] as? String) ?: "").lowercase()) == family) {
                @Suppress("UNCHECKED_CAST")
                for (s in (o["steps"] as? List<Map<String, Any?>>).orEmpty()) {
                    if (s["type"] != "walk") return s.filterKeys { it != "duration_min" }
                }
            }
        }
        val kind = if (family == "running") "run" else "steady"
        return linkedMapOf("type" to kind, "pace" to "easy", "note" to "Easy, conversational effort")
    }

    private fun easySpeed(workouts: List<Map<String, Any?>>, family: String): Double? {
        if (family != "running") return null
        val easy = easySession(family)
        for (o in workouts) {
            val dist = (o["distance_meters"] as? Number)?.toDouble() ?: continue
            val dur = (o["duration_minutes"] as? Number)?.toLong() ?: continue
            if (o["workout_type"] != easy || dist == 0.0 || dur == 0L) continue
            @Suppress("UNCHECKED_CAST")
            val walk = (o["steps"] as? List<Map<String, Any?>>).orEmpty()
                .filter { it["type"] == "walk" }.sumOf { (it["duration_min"] as? Number)?.toLong() ?: 0L }
            val moving = dur - walk
            if (moving > 0) return dist / 1000.0 / (moving / 60.0)
        }
        return null
    }

    private fun note(w: MutableMap<String, Any?>, text: String) {
        w["description"] = "$text\n${(w["description"] as? String) ?: ""}".trim()
    }
}
