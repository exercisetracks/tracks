// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.ActivitySummary

/**
 * A multi-day trip: several activities summed into one line.
 *
 * The contract's `trip` entity (spec/sync.yaml) syncs only what a person said —
 * the name, notes and which activities belong — and the totals are derived here
 * from the members on every device, so a member renamed, re-sported or hidden
 * elsewhere changes the trip's numbers everywhere without the trip itself
 * being edited. A trip is a summary for looking back, never a training input:
 * nothing in training load reads it, exactly as the server's `is_merged` rows
 * are kept out of every metric.
 */
data class TripSummary(
    val id: Int,
    val name: String,
    val notes: String?,
    /** Members still present, oldest first. A hidden or deleted member drops out of the totals. */
    val activities: List<ActivitySummary>,
    val startedAt: String?,
    val endedAt: String?,
    val distanceMeters: Double?,
    val durationSeconds: Int?,
    val totalAscent: Double?,
    val totalCalories: Int?,
)

object TripTotals {

    /**
     * Sum the members that exist. A total is null when no member has the value
     * at all — "no distance recorded" must not read as "0 km".
     */
    fun of(id: Int, name: String, notes: String?, members: List<ActivitySummary>): TripSummary {
        val ordered = members.sortedBy { it.startedAt.orEmpty() }
        fun <N : Number> sum(values: List<N?>, add: (N, N) -> N): N? =
            values.filterNotNull().takeIf { it.isNotEmpty() }?.reduce(add)
        return TripSummary(
            id = id,
            name = name,
            notes = notes,
            activities = ordered,
            startedAt = ordered.firstNotNullOfOrNull { it.startedAt },
            endedAt = ordered.lastOrNull { it.startedAt != null }?.startedAt,
            distanceMeters = sum(ordered.map { it.distanceMeters }) { a, b -> a + b },
            durationSeconds = sum(ordered.map { it.durationSeconds }) { a, b -> a + b },
            totalAscent = sum(ordered.map { it.totalAscent }) { a, b -> a + b },
            totalCalories = sum(ordered.map { it.totalCalories }) { a, b -> a + b },
        )
    }
}
