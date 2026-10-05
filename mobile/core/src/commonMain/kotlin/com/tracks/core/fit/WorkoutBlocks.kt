// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.api.StepGroup
import com.tracks.core.api.WorkoutStep

/**
 * How a flat step list divides into blocks — the one rule the encoder, the
 * guided session and the builder all read.
 *
 * Blocks are stored as fields on each member (spec/sync.yaml), so after two
 * phones reorder concurrently a group's members can end up apart. Grouping by
 * *consecutive* uid, with the first member's settings, is the rule every
 * replica applies identically — a split group becomes two groups with the same
 * settings, which is visible and fixable rather than a silent disagreement.
 * Held to `workout_runs` in backend/app/calculators/fit_workout.py.
 */
object WorkoutBlocks {

    /** (group, members) per maximal run sharing a group uid; (null, [step]) otherwise. */
    fun runs(steps: List<WorkoutStep>): List<Pair<StepGroup?, List<WorkoutStep>>> =
        runsBy(steps) { it.group }

    /** [runs] for anything that carries a group — the builder's rows as well as steps. */
    fun <T> runsBy(items: List<T>, groupOf: (T) -> StepGroup?): List<Pair<StepGroup?, List<T>>> {
        val out = mutableListOf<Pair<StepGroup?, MutableList<T>>>()
        for (item in items) {
            val group = groupOf(item)
            val last = out.lastOrNull()
            if (group != null && last?.first != null && last.first!!.uid == group.uid) {
                last.second += item
            } else {
                out += group to mutableListOf(item)
            }
        }
        return out
    }
}
