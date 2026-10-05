// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.plan.Matching
import com.tracks.core.plan.PlanAssembly
import com.tracks.core.plan.PlanBase
import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What matching a workout does besides ticking it — the server's side effects
 * in `api/training_plan/matching.py` — derived, not stored.
 *
 * On the server a match advances the fitness fingerprint, lets a completed FTP
 * field test set the automatic thresholds, and folds the activity's recorded
 * sets into the lift records. All three land in tables that do not sync
 * (`user_fitness_fingerprints`, `user_settings.*_auto`,
 * `user_exercise_strength`), because each is a function of things that do:
 * the matched workouts and the activities' own files. So the phone replays
 * them from exactly those, every time it needs one, in activity order — which
 * is the order the server applied them in as the activities arrived — and two
 * phones holding the same rows derive the same values.
 */
class LocalMatchEffects(private val sources: LocalSources, private val library: LocalLibrary) {

    /** One matched workout, with its activity's parsed summary and detail. */
    class Matched(val workout: SyncedRow, val summary: JsonObject, val detail: JsonObject?, val startedAt: String)

    /** Every workout matched to an activity this phone holds, oldest activity first. */
    suspend fun matched(): List<Matched> =
        sources.replica.rows("planned_workout").mapNotNull { w ->
            val uid = w.str("completed_activity_uid") ?: return@mapNotNull null
            val summary = library.summaryJson(uid) ?: return@mapNotNull null
            Matched(w, summary, library.detailJson(uid), summary.s("started_at") ?: "")
        }.sortedWith(compareBy<Matched>({ it.startedAt }, { it.workout.uid }))

    /** `_update_fingerprint` replayed: the fingerprint for one sport family. */
    suspend fun fingerprint(family: String): PlanAssembly.Fingerprint =
        matched().fold(PlanAssembly.Fingerprint()) { fp, m ->
            if (PlanBase.sportFamily(m.summary.s("sport") ?: "") != family) return@fold fp
            PlanAssembly.advanceFingerprint(
                fp, family,
                PlanAssembly.DoneActivity(m.summary.s("sport"), m.summary.d("distance_meters"), m.summary.l("duration_seconds")),
                PlanAssembly.PlannedTarget(
                    m.workout.str("workout_type") ?: "", m.workout.d("distance_meters"), m.workout.l("duration_minutes"),
                ),
                m.workout.d("completion_pct") ?: 0.0,
            )
        }

    /**
     * `_apply_field_test_result` replayed: what completed field tests set, the
     * latest test winning per value (the server overwrites one key at a time).
     */
    suspend fun fieldTests(ftpMode: String?, thresholdHrMode: String?): Matching.FieldTestUpdate {
        var out = Matching.FieldTestUpdate()
        for (m in matched()) {
            val type = m.workout.str("workout_type") ?: continue
            if (!type.startsWith("field_test:") || m.workout.str("is_complete") != "true") continue
            val curve = m.detail?.get("power_curve") as? JsonObject
            val best20 = (curve?.get("1200") as? JsonPrimitive)?.doubleOrNull
            val u = Matching.fieldTestUpdates(
                type.substringAfter(':'), best20, m.summary.l("max_heart_rate")?.toInt(), ftpMode, thresholdHrMode,
            )
            out = Matching.FieldTestUpdate(u.ftpAuto ?: out.ftpAuto, u.thresholdHrAuto ?: out.thresholdHrAuto)
        }
        return out
    }

    /**
     * `_update_strength_fingerprint`'s inputs: the recorded sets of each matched
     * activity whose workout has no logged session (a logged session already
     * carries the sets, and the server skips those). Returned as events for the
     * lift-record replay, stamped with the activity's start.
     */
    suspend fun strengthSets(): List<Pair<String, List<Matching.SetIn>>> {
        val logged = sources.replica.rows("workout_session").mapNotNullTo(HashSet()) { it.str("planned_workout_uid") }
        return matched().mapNotNull { m ->
            if (m.workout.uid in logged) return@mapNotNull null
            val sets = (m.detail?.get("strength_sets") as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Matching.SetIn(o.s("exercise_name"), o.d("weight_kg"), (o["repetitions"] as? JsonPrimitive)?.intOrNull)
            }
            if (sets.isEmpty()) null else m.startedAt to sets
        }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun SyncedRow.d(k: String): Double? = (fields[k] as? JsonPrimitive)?.doubleOrNull
    private fun SyncedRow.l(k: String): Long? = (fields[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
}
