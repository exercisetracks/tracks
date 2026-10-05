// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Invariants that span rows, resolved when read instead of enforced on write.
 *
 * A merge only ever looks at one row, and that is deliberate: a rule that
 * refused a write because of *another* row's state would give different
 * answers depending on which row a replica happened to receive first. So an
 * invariant like "one active goal" is not prevented — two offline phones can
 * each activate a different goal, and both writes land — it is *decided* here,
 * deterministically, from state every replica converges on.
 */
object ReadRules {

    /**
     * The active goal: of the live goals marked active, the one whose
     * `is_active` was written last. Ties (the same edit on one row cannot tie
     * with another row's, but a hand-built fixture could) break by uid, so the
     * answer never depends on list order.
     */
    fun activeGoal(goals: Collection<SyncedRow>): String? =
        goals.asSequence()
            .filter { it.entity == "goal" && !it.isTombstone }
            .filter { (it.fields["is_active"] as? JsonPrimitive)?.booleanOrNull == true }
            .maxWithOrNull(compareBy<SyncedRow>({ it.clock["is_active"] ?: "" }, { it.uid }))
            ?.uid

    /**
     * Planned workouts left behind by a regeneration.
     *
     * Regenerating a plan stamps it with a fresh `generation`; every workout it
     * produced carries the same value. One whose generation is not its plan's
     * current one belongs to a plan that has since been rebuilt — typically by
     * another phone, offline, at the same time — and is dead. Whichever replica
     * notices deletes it, and because they all compute the same set, the
     * deletes agree.
     *
     * Hand-added workouts (no plan) and workouts the user moved are never dead, and a workout whose plan
     * this replica has not received yet is left alone: "not known" is not
     * "rebuilt".
     */
    fun deadWorkouts(rows: Collection<SyncedRow>): List<String> {
        val plans = rows.asSequence()
            .filter { it.entity == "plan" && !it.isTombstone }
            .associateBy { it.uid }
        return rows.asSequence()
            .filter { it.entity == "planned_workout" && !it.isTombstone }
            .filter { workout ->
                val planUid = workout.fields["plan_uid"].uidOrNull() ?: return@filter false
                val plan = plans[planUid] ?: return@filter false
                // Moved by the user: kept through any regeneration (merge.py
                // workout_is_dead says why), so never reaped as a leftover.
                if ((workout.fields["moved_by_user"] as? JsonPrimitive)?.booleanOrNull == true) return@filter false
                if (plan.fields["generation"].uidOrNull() == workout.fields["generation"].uidOrNull()) {
                    return@filter false
                }
                // Only a workout from an OLDER generation is dead. A newer
                // generation's workouts can arrive before the plan row that
                // names it, and since a delete wins everywhere, calling those
                // dead would destroy the regeneration that just happened.
                val workoutStamp = workout.clock["generation"] ?: return@filter false
                val planStamp = plan.clock["generation"] ?: return@filter false
                workoutStamp < planStamp
            }
            .map { it.uid }
            .sorted()
            .toList()
    }
}
