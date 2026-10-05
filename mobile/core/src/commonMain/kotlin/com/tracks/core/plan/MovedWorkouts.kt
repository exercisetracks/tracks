// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import kotlin.math.abs

/**
 * Keeping workouts the user moved on the day they were moved to, through a
 * regeneration — a port of `backend/app/calculators/plan/moved.py`, held to it
 * by spec/fixtures/moved_workouts.json. The Python docstring has the matching
 * rule and why; in short, each moved workout (dated today or later, by date
 * then uid) claims the regenerated session of the same role in its own
 * Monday–Sunday week — same type first, then same sport, then nearest date,
 * earlier date, first built — takes its content unless it is complete, and
 * the claimed session is not written.
 */
object MovedWorkouts {

    /** What a moved workout is matched on. */
    data class Moved(
        val uid: String,
        val scheduledDate: CivilDate,
        val sport: String?,
        val workoutType: String?,
        val isComplete: Boolean,
    )

    /** The sessions still to write, and the content for each refreshed moved uid. */
    data class Result(
        val remaining: List<MutableMap<String, Any?>>,
        val refresh: Map<String, Map<String, Any?>>,
    )

    val ROLES: Map<String, String> = mapOf(
        "long" to "long", "long_run" to "long",
        "easy" to "easy", "easy_spin" to "easy", "easy_recovery" to "easy", "recovery" to "easy",
        "endurance" to "easy", "aerobic" to "easy", "skills" to "easy",
        "tempo" to "quality", "threshold" to "quality", "intervals" to "quality",
        "fartlek" to "quality", "race_pace" to "quality", "short_quality" to "quality",
        "sweet_spot" to "quality", "sprint" to "quality", "over_unders" to "quality",
        "tt_pace" to "quality", "sustained_climb" to "quality", "descent_repeats" to "quality",
        "matchbook" to "quality", "quality" to "quality",
        "strength" to "strength", "custom_strength" to "strength",
        "mobility" to "mobility", "flexibility" to "mobility",
        "race" to "race",
        // A brick run is its own part of the week (moved.py).
        "brick_run" to "brick",
    )

    val CONTENT_FIELDS = listOf(
        "sport", "workout_type", "title", "description", "duration_minutes", "distance_meters", "steps",
    )

    fun role(workoutType: String?): String {
        val t = workoutType ?: ""
        if (t.startsWith("field_test")) return "test"
        return ROLES[t] ?: "type:$t"
    }

    private fun monday(d: CivilDate) = CivilDate.fromEpochDay(d.epochDay - (d.epochDay + 3).mod(7L))

    fun keep(generated: List<MutableMap<String, Any?>>, moved: List<Moved>, today: CivilDate): Result {
        val claimed = HashSet<Int>()
        val refresh = LinkedHashMap<String, Map<String, Any?>>()
        // (date, uid): the same order the server walks them in, so two moved
        // workouts wanting one session resolve the same way on both.
        for (m in moved.sortedWith(compareBy({ it.scheduledDate }, { it.uid }))) {
            val day = m.scheduledDate
            if (day < today) continue
            val week = monday(day)
            val want = role(m.workoutType)
            var best: Pair<List<Comparable<*>>, Int>? = null
            generated.forEachIndexed { i, g ->
                val gDay = g["scheduled_date"] as CivilDate
                if (i in claimed || monday(gDay) != week) return@forEachIndexed
                if (role(g["workout_type"] as String?) != want) return@forEachIndexed
                val key = listOf<Comparable<*>>(
                    if (g["workout_type"] == m.workoutType) 0 else 1,
                    if (g["sport"] == m.sport) 0 else 1,
                    abs(gDay.epochDay - day.epochDay),
                    gDay.epochDay,
                    i,
                )
                if (best == null || compareKeys(key, best!!.first) < 0) best = key to i
            }
            val hit = best ?: continue
            claimed += hit.second
            if (!m.isComplete) {
                val g = generated[hit.second]
                refresh[m.uid] = CONTENT_FIELDS.associateWith { g[it] }
            }
        }
        return Result(generated.filterIndexed { i, _ -> i !in claimed }, refresh)
    }

    /** What a completed workout is matched on. */
    data class Completed(val uid: String, val scheduledDate: CivilDate, val sport: String?, val workoutType: String?)

    /**
     * The sessions left to write once each completed workout dated today or
     * later has claimed the session of its role on its own day — same type
     * first, then same sport, then first built; walked by (date, uid). A
     * port of `keep_completed` (moved.py has why), held by the same corpus.
     */
    fun keepCompleted(
        generated: List<MutableMap<String, Any?>>,
        completed: List<Completed>,
        today: CivilDate,
    ): List<MutableMap<String, Any?>> {
        val claimed = HashSet<Int>()
        for (c in completed.sortedWith(compareBy({ it.scheduledDate }, { it.uid }))) {
            if (c.scheduledDate < today) continue
            val want = role(c.workoutType)
            var best: Pair<List<Comparable<*>>, Int>? = null
            generated.forEachIndexed { i, g ->
                if (i in claimed || g["scheduled_date"] as CivilDate != c.scheduledDate) return@forEachIndexed
                if (role(g["workout_type"] as String?) != want) return@forEachIndexed
                val key = listOf<Comparable<*>>(
                    if (g["workout_type"] == c.workoutType) 0 else 1,
                    if (g["sport"] == c.sport) 0 else 1,
                    i,
                )
                if (best == null || compareKeys(key, best!!.first) < 0) best = key to i
            }
            best?.let { claimed += it.second }
        }
        return generated.filterIndexed { i, _ -> i !in claimed }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compareKeys(a: List<Comparable<*>>, b: List<Comparable<*>>): Int {
        for (i in a.indices) {
            val c = (a[i] as Comparable<Any>).compareTo(b[i] as Any)
            if (c != 0) return c
        }
        return 0
    }
}
