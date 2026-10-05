// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.runtime.Composable

/**
 * Which detail layout renders which sport.
 *
 * The web app dispatches over nineteen of these with a chain of `isRunning(…)`
 * / `isCycling(…)` predicates and nineteen imports. That works, but every new
 * sport edits the dispatcher — so this side is a registry instead: a layout
 * declares the sport types it handles, and adding one is an entry in [LAYOUTS]
 * rather than a change to the code that chooses.
 *
 * The sport *type* strings come from the shared spec
 * (spec/sport_taxonomy.yaml, via `com.tracks.core.spec.sportType`), which is
 * what makes this safe to key on: the phone and the browser classify a ride
 * identically, so they cannot disagree about which layout a given activity
 * deserves. Anything unclaimed falls through to [FallbackLayout], which is why
 * a sport nobody has written a layout for still renders something useful
 * rather than an error — and why it renders a *different* something depending
 * on whether the activity has GPS behind it.
 */
fun interface ActivityLayout {
    @Composable
    fun Render(data: ActivityDetailData)
}

/** One registration: the sport types this layout claims, and the layout. */
data class LayoutRegistration(
    val sportTypes: Set<String>,
    val layout: ActivityLayout,
)

/**
 * Ordered, but order only matters for readability — the sport types are
 * disjoint, and [layoutFor] takes the first claim.
 *
 * Every type in `spec/sport_taxonomy.yaml` is claimed here except `other`,
 * which is what [FallbackLayout] is for. That completeness is the point: until
 * it held four entries, the most-recorded sport in the author's own data —
 * climbing — rendered the same generic distance-and-duration card as an
 * unrecognised sport, which is the bulk of what "the desktop has way more
 * details" meant.
 */
val LAYOUTS: List<LayoutRegistration> = listOf(
    LayoutRegistration(
        sportTypes = setOf("running"),
        layout = { data -> RunningLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("hiking"),
        layout = { data -> HikingLayout(data) },
    ),
    LayoutRegistration(
        // Indoor cycling shares the layout but not the map — the layout reads
        // whether there is GPS rather than being told which of the two it is.
        sportTypes = setOf("cycling", "mtb", "indoor_cycling"),
        layout = { data -> CyclingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("strength"),
        layout = { data -> StrengthLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("climbing"),
        layout = { data -> ClimbingLayout(data) },
    ),
    LayoutRegistration(
        // Bouldering is its own layout rather than a flag on climbing, matching
        // the web app: no ropes means no vertical, and problems are counted
        // rather than measured.
        sportTypes = setOf("bouldering"),
        layout = { data -> BoulderingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("skiing"),
        layout = { data -> SkiingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("nordic_skiing"),
        layout = { data -> NordicSkiingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("swimming"),
        layout = { data -> SwimmingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("rowing"),
        layout = { data -> RowingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("paddling"),
        layout = { data -> PaddlingLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("fitness_equipment"),
        layout = { data -> FitnessEquipmentLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("mind_body"),
        layout = { data -> MindBodyLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("team_sports"),
        layout = { data -> TeamSportsLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("golf"),
        layout = { data -> GolfLayout(data) },
    ),
    LayoutRegistration(
        sportTypes = setOf("triathlon"),
        layout = { data -> TriathlonLayout(data) },
    ),
)

/** The layout for a sport type, or [FallbackLayout] when nothing claims it. */
fun layoutFor(sportType: String): ActivityLayout =
    LAYOUTS.firstOrNull { sportType in it.sportTypes }?.layout
        ?: ActivityLayout { data -> FallbackLayout(data) }
