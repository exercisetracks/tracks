// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch
import com.tracks.core.fit.WorkoutBlocks

/**
 * A saved flow as the list of holds the player runs, following its blocks.
 *
 * The player knows nothing about blocks: it runs holds, each followed by its
 * rest. So blocks are unrolled here (the watch file loops them instead — see
 * WorkoutFit): a repeat group or superset is its members once per round, the
 * group's rest after each round; a rest block lengthens the rest after the
 * hold before it. One-sided stretches are two holds, left then right, as
 * before — the watch file's per-side holds match.
 */
object FlowPlan {

    fun holds(stretches: List<FlowStretch>, library: Map<String, Stretch>): List<Hold> {
        val out = mutableListOf<Hold>()

        fun restAfterLast(seconds: Int) {
            val last = out.lastOrNull() ?: return
            out[out.lastIndex] = last.copy(restSeconds = last.restSeconds + seconds)
        }

        fun hold(entry: FlowStretch, sets: Int, inGroup: Boolean) {
            val name = entry.exerciseName ?: return
            val stretch = library[name]
            val seconds = entry.durationSeconds
                ?: stretch?.durationPerSideSec
                ?: FlexibilityViewModel.DEFAULT_HOLD_SECONDS
            // Inside a group the next member follows at once; the group's own
            // rest comes after the round.
            val rest = if (inGroup) 0 else entry.restSeconds ?: FlexibilityViewModel.DEFAULT_REST_SECONDS
            // A one-sided stretch is two holds, not one held twice as long, and
            // the runner has to say which side or half of it gets skipped.
            val sides = if (stretch?.eachSide == true) listOf("Left", "Right") else listOf(null)
            for (set in 1..sets) {
                for (side in sides) {
                    out += Hold(
                        stretch = stretch,
                        name = name,
                        seconds = seconds,
                        restSeconds = rest,
                        side = side,
                        set = set.takeIf { sets > 1 },
                        note = entry.coachingNote,
                    )
                }
            }
        }

        for ((group, members) in WorkoutBlocks.runsBy(stretches) { it.group }) {
            if (group != null) {
                if (members.none { !it.isRest }) continue
                repeat(group.rounds.coerceIn(1, FlexibilityViewModel.MAX_SETS)) {
                    for (m in members) {
                        if (m.isRest) restAfterLast(m.durationSeconds ?: 0) else hold(m, 1, inGroup = true)
                    }
                    restAfterLast(group.restSeconds)
                }
                continue
            }
            val entry = members.single()
            if (entry.isRest) {
                restAfterLast(entry.durationSeconds ?: 0)
            } else {
                val stretch = entry.exerciseName?.let(library::get)
                hold(entry, (entry.sets ?: stretch?.sets ?: 1).coerceIn(1, FlexibilityViewModel.MAX_SETS), inGroup = false)
            }
        }
        return out
    }
}
