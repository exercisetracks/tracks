// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.plan.Matching
import com.tracks.core.plan.PlanAssembly
import com.tracks.core.replica.ReplicaStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Ticks off the planned workout an imported activity satisfies — the phone's
 * half of the server's `match_activity_to_workout`.
 *
 * Without it a run recorded on a mountain with no server would sit next to an
 * unticked "Easy run" for as long as the trip lasted, and the plan would only
 * catch up once the file reached a server.
 *
 * The result is written as replica edits to the synced fields
 * (completed_activity_uid, completion_pct, is_complete) — the user's decision
 * that match results sync rather than being derived per device. That is safe
 * with several writers because every device picks the same workout from the
 * same rows ([Matching.pickWorkout] mirrors `pick_workout` exactly) and computes
 * the same percentage, so concurrent writes agree field for field.
 *
 * The fitness fingerprint and field-test threshold updates the server also
 * makes here are not synced entities and stay server-side for now.
 */
class LocalMatching(
    private val replica: ReplicaStore,
    /**
     * UTC offset in a named zone at an instant — [com.tracks.core.time.ZoneOffsets]
     * on a phone. The default is UTC everywhere, for tests that do not care.
     */
    private val zoneOffsets: (zone: String?) -> (epochSeconds: Long) -> Int = { { 0 } },
) {

    /** Match one activity; returns the matched workout's uid, or null when nothing fits. */
    suspend fun match(activityUid: String, startedAt: String?, activity: PlanAssembly.DoneActivity): String? {
        // The day in the account's zone, as the server's match takes it — not
        // the UTC day the start time is stored in. See Matching.localDate.
        val zone = replica.rows("settings").firstOrNull()?.str("timezone")
        val date = startedAt?.let { Matching.localDate(it, zoneOffsets(zone)) } ?: return null
        val rows = replica.rows("planned_workout")
        val candidates = rows.mapNotNull { r ->
            val scheduled = r.str("scheduled_date") ?: return@mapNotNull null
            Matching.Candidate(
                uid = r.uid,
                planUid = r.str("plan_uid"),
                scheduledDate = scheduled.take(10),
                workoutType = r.str("workout_type") ?: "",
                completedActivityUid = r.str("completed_activity_uid"),
                sport = r.str("sport"),
            )
        }
        val picked = Matching.pickWorkout(date, activity.sport, candidates) ?: return null
        val row = rows.first { it.uid == picked.uid }
        val pct = PlanAssembly.completionPct(
            PlanAssembly.PlannedTarget(
                workoutType = picked.workoutType,
                distanceMeters = (row.fields["distance_meters"] as? JsonPrimitive)?.doubleOrNull,
                durationMinutes = (row.fields["duration_minutes"] as? JsonPrimitive)?.longOrNull,
            ),
            activity,
        )
        replica.edit(
            "planned_workout", picked.uid,
            mapOf(
                "completed_activity_uid" to JsonPrimitive(activityUid),
                "completion_pct" to JsonPrimitive(pct),
                "is_complete" to JsonPrimitive(Matching.isComplete(pct)),
            ),
        )
        return picked.uid
    }

    /** An activity as [repairUtcDayMatches] needs it. */
    class Held(val uid: String, val startedAt: String, val done: PlanAssembly.DoneActivity)

    /**
     * Move matches an older build made by the UTC day onto the right workout.
     * Returns how many moved.
     *
     * Until 2026-09-30 both this and the server matched by the UTC day, so an
     * evening activity west of Greenwich ticked off tomorrow's workout and
     * left its own open. New matches are right; this repairs the ones already
     * written, which nothing else would ever revisit — matching runs once, at
     * import.
     *
     * Only a match with the bug's exact signature is touched: the workout is on
     * the activity's UTC day and not on its local one. Nothing in the app links
     * a workout to an activity by hand, so no such pairing can be a person's
     * choice. The fitness effects of a match are derived from the match rows
     * each time ([LocalMatchEffects]), so moving one leaves nothing stale.
     */
    suspend fun repairUtcDayMatches(activities: List<Held>): Int {
        val offsets = zoneOffsets(replica.rows("settings").firstOrNull()?.str("timezone"))
        val byUid = activities.associateBy { it.uid }
        var moved = 0
        for (row in replica.rows("planned_workout")) {
            val held = byUid[row.str("completed_activity_uid") ?: continue] ?: continue
            val scheduled = row.str("scheduled_date")?.take(10) ?: continue
            val local = Matching.localDate(held.startedAt, offsets) ?: continue
            val utc = Matching.localDate(held.startedAt) { 0 }
            if (scheduled == local || scheduled != utc) continue
            replica.edit(
                "planned_workout", row.uid,
                mapOf(
                    "completed_activity_uid" to JsonNull,
                    "completion_pct" to JsonNull,
                    "is_complete" to JsonPrimitive(false),
                ),
            )
            match(held.uid, held.startedAt, held.done)
            moved++
        }
        return moved
    }
}

