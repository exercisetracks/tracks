// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import com.tracks.core.api.Exercise
import org.json.JSONArray
import org.json.JSONObject

/**
 * The parts of a guided strength session that are decisions rather than
 * drawing: where "next" goes, what can stand in for an exercise, what the
 * session added up to, and how it survives the process being killed.
 *
 * Kept out of the ViewModel so each can be tested without one.
 */
object SessionLogic {

    /**
     * The exercise after [from] that still has sets to do, wrapping round.
     *
     * Not simply `from + 1`: a busy rack sends people to the accessories first,
     * so "next" means the next unfinished one — and when everything else is
     * done it stays put rather than cycling through finished work.
     */
    /**
     * A saved workout as a session, following its blocks.
     *
     * A member of a circuit or superset gets one set per round; a rest block
     * becomes the rest after the exercise before it (a rest first in the list
     * has nothing to follow and is dropped). Names the library no longer has
     * are skipped — a custom exercise can be deleted out from under a workout.
     * [standing] gives (last weight, last reps) to prefill from.
     */
    fun plan(
        rows: List<com.tracks.core.api.UserWorkoutExercise>,
        library: Map<String, Exercise>,
        standing: (Exercise) -> Pair<Double?, Int?>?,
    ): List<SessionExercise> {
        val out = mutableListOf<SessionExercise>()
        for (row in rows) {
            if (row.isRest) {
                val last = out.lastOrNull() ?: continue
                out[out.lastIndex] = last.copy(restAfterSeconds = last.restAfterSeconds + row.restSeconds)
                continue
            }
            val exercise = library[row.exerciseName ?: continue] ?: continue
            val group = row.group
            val (weight, lastReps) = standing(exercise) ?: (null to null)
            val sets = (group?.rounds ?: row.targetSets).coerceIn(1, 12)
            val reps = lastReps ?: row.targetReps
            out += SessionExercise(
                exercise = exercise,
                sets = List(sets) { SessionSet(weightKg = weight ?: 0.0, reps = reps) },
                restSeconds = row.restSeconds,
                group = group,
            )
        }
        return out
    }

    /** What ticking a set leads to: where to go, and how long to rest first. */
    data class AfterSet(val moveTo: Int?, val restSeconds: Int)

    /**
     * After set [setIndex] of exercise [index] is done.
     *
     * On its own: rest between sets; after the last set, rest for any rest
     * block that follows. In a circuit or superset: go straight to the next
     * member at the same round with no rest, and after the last member of the
     * round rest for the group's rest and return to the first member.
     * Consecutive members sharing a group uid form the block, as everywhere
     * else (com.tracks.core.fit.WorkoutBlocks).
     */
    fun afterSetDone(session: SessionState, index: Int, setIndex: Int): AfterSet {
        val exercises = session.exercises
        val ex = exercises.getOrNull(index) ?: return AfterSet(null, 0)
        val group = ex.group
        if (group == null) {
            val last = setIndex == ex.sets.lastIndex
            return AfterSet(null, if (last) ex.restAfterSeconds else ex.restSeconds)
        }
        var start = index
        while (start > 0 && exercises[start - 1].group?.uid == group.uid) start--
        var end = index
        while (end < exercises.lastIndex && exercises[end + 1].group?.uid == group.uid) end++
        for (i in index + 1..end) {
            if (exercises[i].sets.getOrNull(setIndex)?.done == false) return AfterSet(i, 0)
        }
        val lastRound = setIndex >= (start..end).maxOf { exercises[it].sets.lastIndex }
        return if (lastRound) {
            AfterSet(null, exercises[end].restAfterSeconds)
        } else {
            AfterSet(start, exercises[start].group?.restSeconds ?: 0)
        }
    }

    fun nextUnfinished(exercises: List<SessionExercise>, from: Int): Int? {
        for (step in 1 until exercises.size) {
            val i = (from + step) % exercises.size
            if (!exercises[i].allDone) return i
        }
        return null
    }

    /**
     * What could replace [current] when its station is taken or it hurts:
     * same movement pattern first, then anything sharing a primary muscle,
     * never something already in the session, and only kit the lifter owns
     * when [onlyMine] (bodyweight always counts).
     */
    fun substitutes(
        current: Exercise,
        library: List<Exercise>,
        inSession: Set<String>,
        myEquipment: Set<String>,
        onlyMine: Boolean,
        limit: Int = 8,
    ): List<Exercise> {
        fun usable(e: Exercise) = !onlyMine || e.equipment.isEmpty() ||
            e.equipment.any { it == "bodyweight" || it in myEquipment }
        val pool = library.filter { it.name != current.name && it.name !in inSession && usable(it) }
        val samePattern = pool.filter { current.movementPattern != null && it.movementPattern == current.movementPattern }
        val sameMuscle = pool.filter { e -> e !in samePattern && e.primaryMuscles.any { it in current.primaryMuscles } }
        return (samePattern + sameMuscle).take(limit)
    }

    data class Summary(val setsDone: Int, val exercisesDone: Int, val volumeKg: Double, val minutes: Int)

    /** What the session added up to, for the screen shown before it is logged. */
    fun summary(session: SessionState, nowMillis: Long): Summary {
        val done = session.exercises.map { ex -> ex.sets.filter { it.done && it.reps > 0 } }
        return Summary(
            setsDone = done.sumOf { it.size },
            exercisesDone = done.count { it.isNotEmpty() },
            volumeKg = done.sumOf { sets -> sets.sumOf { it.weightKg * it.reps } },
            minutes = if (session.startedAtMillis > 0) ((nowMillis - session.startedAtMillis) / 60_000).toInt() else 0,
        )
    }

    /** Seconds left on a rest that ends at [endsAt], or null once it is over. */
    fun restRemaining(endsAt: Long?, nowMillis: Long): Int? {
        if (endsAt == null) return null
        val left = endsAt - nowMillis
        return if (left <= 0) null else ((left + 999) / 1000).toInt()
    }

    // ── Surviving process death ─────────────────────────────────────────────
    //
    // A session is twenty minutes to an hour, and Android kills backgrounded
    // apps freely — a phone left on the bench while someone does a set of
    // pull-ups is exactly the phone that gets reclaimed. So the session is
    // written down on every change and read back on the next launch.
    //
    // Exercises are stored by name and resolved against the library when read:
    // the library ships with the app, and storing whole rows would freeze a
    // stale copy of an exercise's cues into the snapshot. The rest timer is a
    // wall-clock deadline for the same reason — a countdown in memory is lost
    // with the process, and would be wrong across a device sleep anyway.

    fun encode(session: SessionState): String = JSONObject().apply {
        put("current", session.current)
        put("started_at", session.startedAtMillis)
        session.restEndsAt?.let { put("rest_ends_at", it) }
        put("reviewing", session.reviewing)
        put("exercises", JSONArray().apply {
            session.exercises.forEach { ex ->
                put(JSONObject().apply {
                    put("name", ex.exercise.name)
                    put("rest", ex.restSeconds)
                    put("rest_after", ex.restAfterSeconds)
                    ex.group?.let { g ->
                        put("group", JSONObject().put("uid", g.uid).put("kind", g.kind)
                            .put("rounds", g.rounds).put("rest", g.restSeconds))
                    }
                    put("sets", JSONArray().apply {
                        ex.sets.forEach { s ->
                            put(JSONObject().put("kg", s.weightKg).put("reps", s.reps).put("done", s.done))
                        }
                    })
                })
            }
        })
    }.toString()

    /** Null when the text is unreadable or none of its exercises still exist. */
    fun decode(text: String, library: List<Exercise>): SessionState? = runCatching {
        val o = JSONObject(text)
        val byName = library.associateBy { it.name }
        val list = o.getJSONArray("exercises")
        val exercises = (0 until list.length()).mapNotNull { i ->
            val e = list.getJSONObject(i)
            val exercise = byName[e.getString("name")] ?: return@mapNotNull null
            val sets = e.getJSONArray("sets")
            SessionExercise(
                exercise = exercise,
                sets = (0 until sets.length()).map { j ->
                    val s = sets.getJSONObject(j)
                    SessionSet(s.getDouble("kg"), s.getInt("reps"), s.getBoolean("done"))
                },
                restSeconds = e.optInt("rest", DEFAULT_REST_SECONDS),
                restAfterSeconds = e.optInt("rest_after", 0),
                group = e.optJSONObject("group")?.let { g ->
                    com.tracks.core.api.StepGroup(
                        g.getString("uid"), g.optString("kind", "repeat"),
                        g.optInt("rounds", 1), g.optInt("rest", 0),
                    )
                },
            )
        }
        if (exercises.isEmpty()) return null
        SessionState(
            exercises = exercises,
            current = o.optInt("current", 0).coerceIn(0, exercises.lastIndex),
            startedAtMillis = o.optLong("started_at", 0),
            restEndsAt = if (o.has("rest_ends_at")) o.getLong("rest_ends_at") else null,
            reviewing = o.optBoolean("reviewing", false),
        )
    }.getOrNull()
}
