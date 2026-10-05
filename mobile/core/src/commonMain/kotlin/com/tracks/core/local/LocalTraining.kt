// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.plan.Matching
import com.tracks.core.api.Exercise
import com.tracks.core.api.FlexibilityFlow
import com.tracks.core.api.FlexibilityFlowIn
import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch
import com.tracks.core.api.StrengthProgress
import com.tracks.core.api.TracksJson
import com.tracks.core.api.UserWorkout
import com.tracks.core.api.UserWorkoutExercise
import com.tracks.core.api.UserWorkoutIn
import com.tracks.core.api.WorkoutSessionIn
import com.tracks.core.parse.PyMath
import com.tracks.core.replica.Hlc
import com.tracks.core.spec.EXERCISE_LIBRARY_JSON
import com.tracks.core.spec.STRETCH_LIBRARY_JSON
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Strength and mobility, answered on the phone: the libraries, what the person
 * has built from them, and where they stand on each lift.
 *
 * Each function is the server endpoint of the same purpose — `/exercises`,
 * `/flexibility/stretches`, `/workouts`, `/flexibility/flows`,
 * `/strength/progress` — built from the bundled library (spec/library/) and
 * this phone's replica instead of from tables.
 */
class LocalTraining(private val sources: LocalSources) {

    private val exerciseRows: List<JsonObject> by lazy { EXERCISE_LIBRARY_JSON.map { TracksJson.parseToJsonElement(it).jsonObject } }
    private val stretchRows: List<JsonObject> by lazy { STRETCH_LIBRARY_JSON.map { TracksJson.parseToJsonElement(it).jsonObject } }

    // ── Libraries ───────────────────────────────────────────────────────────

    /** `/exercises`: the library then the person's own, each with their preference. */
    suspend fun exercises(): List<Exercise> {
        val prefs = preferences("exercise_preference")
        val library = exerciseRows.mapNotNull { row ->
            val name = row.str("name") ?: return@mapNotNull null
            decode(row, Exercise.serializer(), "preference" to prefs[name])
        }
        val customs = sources.replica.rows("custom_exercise")
            .sortedBy { it.str("name") }
            .mapNotNull { r ->
                val json = sources.modelJson(r)
                decode(
                    json, Exercise.serializer(),
                    "preference" to prefs[r.str("name")],
                    "is_custom" to true,
                    "custom_id" to sources.idOf(r.uid),
                    "equipment" to (json["equipment"]?.takeIf { it !is JsonNull } ?: JsonArray(listOf(JsonPrimitive("bodyweight")))),
                )
            }
        return library + customs
    }

    /** `/flexibility/stretches`: built-in, then custom, with preferences and the server's defaults. */
    suspend fun stretches(): List<Stretch> {
        val prefs = preferences("stretch_preference")
        fun defaults(json: JsonObject) = arrayOf(
            "equipment" to (json["equipment"]?.takeIf { it !is JsonNull && (it as? JsonArray)?.isNotEmpty() == true }
                ?: JsonArray(listOf(JsonPrimitive("bodyweight")))),
            "movement_pattern" to (json["movement_pattern"]?.takeIf { it !is JsonNull } ?: JsonPrimitive("static_stretch")),
        )
        val library = stretchRows.mapNotNull { row ->
            decode(row, Stretch.serializer(), "preference" to prefs[row.str("name")], *defaults(row))
        }
        val customs = sources.replica.rows("custom_stretch").mapNotNull { r ->
            val json = sources.modelJson(r)
            decode(json, Stretch.serializer(), "preference" to prefs[r.str("name")], "custom_id" to sources.idOf(r.uid), *defaults(json))
        }
        return (library + customs).sortedBy { it.name.lowercase() }
    }

    /** The equipment the person owns, from their settings. */
    suspend fun equipment(): List<String> {
        val row = sources.replica.rows("settings").firstOrNull() ?: return emptyList()
        return (row.fields["equipment_available"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    }

    suspend fun setEquipment(items: List<String>) = sources.writeSetting("equipment_available", items)

    /** Set or clear (null) a library preference — `preferred` / `excluded`. Natural-keyed by name. */
    suspend fun setPreference(stretch: Boolean, name: String, preference: String?) {
        val entity = if (stretch) "stretch_preference" else "exercise_preference"
        sources.createValues(entity, mapOf("exercise_name" to name, "preference" to preference), mapOf("exercise_name" to name))
    }

    /**
     * Create ([id] null) or edit the person's own exercise or stretch — the
     * web's "+ Custom". Plain values keyed by contract field
     * (`custom_exercise` / `custom_stretch` in spec/sync.yaml); anything the
     * contract does not name is dropped rather than refused, so a screen
     * written for the web's form cannot write a column nothing syncs.
     */
    suspend fun saveCustom(stretch: Boolean, id: Int?, values: Map<String, Any?>): Int {
        val entity = if (stretch) "custom_stretch" else "custom_exercise"
        val allowed = com.tracks.core.replica.SyncRegistry.require(entity).fields.toSet()
        val fields = values.filterKeys { it in allowed }.mapValues { LocalJson.of(it.value) }
        return if (id == null) sources.createFields(entity, fields)
        else { sources.editFields(entity, id, fields); id }
    }

    suspend fun deleteCustom(stretch: Boolean, id: Int) =
        sources.delete(if (stretch) "custom_stretch" else "custom_exercise", id)

    private suspend fun preferences(entity: String): Map<String, String?> =
        sources.replica.rows(entity).associate { (it.str("exercise_name") ?: "") to it.str("preference") }

    // ── Saved workouts and flows ────────────────────────────────────────────

    suspend fun savedWorkouts(): List<UserWorkout> =
        sources.list("workout", UserWorkout.serializer()).map { w ->
            w.copy(exercises = sources.children("workout_exercise", w.id).mapIndexedNotNull { i, r ->
                sources.decode(r, UserWorkoutExercise.serializer())?.copy(orderIndex = i)
            })
        }.sortedBy { it.name.lowercase() }

    /** Create ([id] null) or replace a saved workout, exercises included. Returns its id. */
    suspend fun saveWorkout(id: Int?, workout: UserWorkoutIn): Int {
        val wid = if (id == null) {
            sources.create("workout", workout, UserWorkoutIn.serializer())
        } else {
            sources.edit("workout", id, workout, UserWorkoutIn.serializer()); id
        }
        sources.replaceChildren("workout_exercise", wid, workout.exercises, UserWorkoutExercise.serializer())
        return wid
    }

    suspend fun flows(): List<FlexibilityFlow> =
        sources.list("flow", FlexibilityFlow.serializer()).map { f ->
            f.copy(stretches = sources.children("flow_stretch", f.id).mapIndexedNotNull { i, r ->
                sources.decode(r, FlowStretch.serializer())?.copy(orderIndex = i)
            })
        }.sortedBy { it.name.lowercase() }

    suspend fun saveFlow(id: Int?, flow: FlexibilityFlowIn): Int {
        val fid = if (id == null) {
            sources.create("flow", flow, FlexibilityFlowIn.serializer())
        } else {
            sources.edit("flow", id, flow, FlexibilityFlowIn.serializer()); id
        }
        sources.replaceChildren("flow_stretch", fid, flow.stretches, FlowStretch.serializer())
        return fid
    }

    // ── Sessions and standing ───────────────────────────────────────────────

    /**
     * Log a finished session, in the shape the server stores (`session_data`
     * with each set's volume). A log, so written once; the standing it moves
     * is derived from all of them by [standings], not stored.
     */
    suspend fun logSession(session: WorkoutSessionIn, workoutId: Int?, completedAt: String): Int {
        var total = 0.0
        val data = session.exercises.map { ex ->
            JsonObject(mapOf(
                "exercise_name" to JsonPrimitive(ex.exerciseName),
                "sets" to JsonArray(ex.sets.map { s ->
                    val vol = s.weightKg * s.reps
                    total += vol
                    JsonObject(mapOf(
                        "weight_kg" to JsonPrimitive(s.weightKg), "reps" to JsonPrimitive(s.reps),
                        "rpe" to (s.rpe?.let(::JsonPrimitive) ?: JsonNull), "volume" to JsonPrimitive(PyMath.round(vol, 1)),
                    ))
                }),
            ))
        }
        return sources.createFields("workout_session", buildMap {
            put("session_data", JsonArray(data))
            put("completed_at", JsonPrimitive(completedAt))
            put("total_volume_kg", JsonPrimitive(PyMath.round(total, 1)))
            put("session_rpe", session.sessionRpe?.let(::JsonPrimitive) ?: JsonNull)
            put("notes", session.notes?.let(::JsonPrimitive) ?: JsonNull)
            workoutId?.let(sources::uidOf)?.let { put("workout_uid", JsonPrimitive(it)) }
            session.plannedWorkoutId?.let(sources::uidOf)?.let { put("planned_workout_uid", JsonPrimitive(it)) }
        })
    }

    /** A hand-set 1RM. Natural-keyed by exercise, so two phones setting it merge by field. */
    suspend fun setOneRepMax(exerciseName: String, kg: Double) {
        sources.createValues("one_rep_max", mapOf("exercise_name" to exerciseName, "override_1rm_kg" to kg), mapOf("exercise_name" to exerciseName))
    }

    /**
     * `/strength/progress`, derived: the server's per-session update
     * (`api/workouts.py`) folded over every logged session in the order they
     * were completed, with a hand-set 1RM taking effect at the moment it was
     * set (its field stamp). Derived rather than stored so two phones logging
     * sessions offline cannot disagree about where the lifter stands.
     */
    suspend fun standings(): List<StrengthProgress> {
        data class Event(
            val at: String, val session: JsonArray? = null, val name: String? = null, val override: Double? = null,
            val recorded: List<Matching.SetIn>? = null,
        )
        val events = ArrayList<Event>()
        for (r in sources.replica.rows("workout_session")) {
            val at = r.str("completed_at") ?: continue
            events += Event(at, session = r.fields["session_data"] as? JsonArray)
        }
        for (r in sources.replica.rows("one_rep_max")) {
            val kg = (r.fields["override_1rm_kg"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val stamp = r.clock["override_1rm_kg"] ?: continue
            events += Event(isoOfStamp(stamp), name = r.str("exercise_name"), override = kg)
        }
        // Sets the watch recorded, for matched workouts with no logged
        // session — the server folds those in when it matches (see
        // LocalMatchEffects.strengthSets).
        for ((at, sets) in LocalMatchEffects(sources, sources.library).strengthSets()) {
            events += Event(at, recorded = sets)
        }
        events.sortBy { it.at }

        class Row(var e1rm: Double? = null, var lastWeight: Double? = null, var lastReps: Int? = null,
                  var sessions: Int = 0, var volume: Double? = null)
        val rows = LinkedHashMap<String, Row>()
        for (e in events) {
            if (e.override != null && e.name != null) {
                rows.getOrPut(e.name) { Row() }.e1rm = e.override
                continue
            }
            if (e.recorded != null) {
                val existing = rows.mapValues { (_, r) -> r.e1rm to r.sessions }
                for ((name, st) in Matching.strengthFingerprintUpdates(e.recorded, existing)) {
                    val row = rows.getOrPut(name) { Row() }
                    row.e1rm = st.estimated1rmKg
                    row.lastWeight = st.lastWeightKg
                    row.lastReps = st.lastReps
                    row.volume = st.lastSessionVolumeKg
                    row.sessions = st.sessionsCompleted
                }
                continue
            }
            for (ex in e.session.orEmpty()) {
                val o = ex as? JsonObject ?: continue
                val name = o.str("exercise_name") ?: continue
                val sets = (o["sets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                if (sets.isEmpty()) continue
                var best: Double? = null
                var vol = 0.0
                for (s in sets) {
                    val w = (s["weight_kg"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                    val reps = (s["reps"] as? JsonPrimitive)?.intOrNull ?: 0
                    vol += w * reps
                    if (reps in 1..10) {
                        val e1 = if (reps <= 1) w else w * (1 + reps / 30.0)
                        if (best == null || e1 > best) best = e1
                    }
                }
                val last = sets.last()
                val row = rows[name]
                if (row == null) {
                    rows[name] = Row(
                        e1rm = best?.let { PyMath.round(it, 1) },
                        lastWeight = (last["weight_kg"] as? JsonPrimitive)?.doubleOrNull,
                        lastReps = (last["reps"] as? JsonPrimitive)?.intOrNull,
                        sessions = 1, volume = PyMath.round(vol, 1),
                    )
                } else {
                    if (best != null) {
                        val base = row.e1rm ?: best
                        row.e1rm = PyMath.round(base * 0.7 + best * 0.3, 1)
                    }
                    row.lastWeight = (last["weight_kg"] as? JsonPrimitive)?.doubleOrNull
                    row.lastReps = (last["reps"] as? JsonPrimitive)?.intOrNull
                    row.sessions += 1
                    row.volume = PyMath.round(vol, 1)
                }
            }
        }
        return rows.map { (name, r) ->
            StrengthProgress(
                exerciseName = name,
                estimated1rmKg = r.e1rm,
                lastWeightKg = r.lastWeight,
                lastReps = r.lastReps,
                lastSessionVolumeKg = r.volume,
                sessionsCompleted = r.sessions,
                progressionStage = when {
                    r.sessions >= 100 -> "dup"
                    r.sessions >= 20 -> "weekly_undulating"
                    else -> "linear"
                },
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun <T> decode(base: JsonObject, serializer: kotlinx.serialization.KSerializer<T>, vararg extra: Pair<String, Any?>): T? {
        val merged = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in extra) merged[k] = LocalJson.of(v)
        return runCatching { TracksJson.decodeFromJsonElement(serializer, JsonObject(merged.mapValues { LocalJson.integral(it.value) })) }.getOrNull()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** An HLC stamp's wall clock as an ISO instant, to order it against `completed_at`. */
    private fun isoOfStamp(stamp: String): String {
        val ms = Hlc.parse(stamp).wallMs
        return com.tracks.core.fit.decode.FitDateTime(ms * 1000).isoformat()
    }
}

