// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.core.spec.sportType

/**
 * One way to say what an activity was, as the FIT file would have said it.
 *
 * A correction writes `sport` and `sub_sport` — the file's own vocabulary —
 * rather than a Tracks sport *type*, because the type is derived from those two
 * by the shared taxonomy on every device. Writing the pair means the web, the
 * server and every phone derive the same type from the correction, and no
 * device needs to know the type table to apply it.
 */
data class SportChoice(val sport: String, val subSport: String?) {
    val type: String get() = sportType(sport, subSport)
    val label: String get() = sportLabel(sport, subSport, type)
}

/**
 * The sports a person is likely to correct a recording to.
 *
 * Not every FIT sport — there are dozens, and a picker of all of them is a
 * search problem. These are the ones the watch most often gets wrong, or that
 * Tracks lays out differently: each maps to a distinct taxonomy type or a
 * distinct label within one (pinned by `SportChoicesTest`).
 */
val SPORT_CHOICES: List<SportChoice> = listOf(
    SportChoice("running", "generic"),
    SportChoice("running", "trail"),
    SportChoice("running", "treadmill"),
    SportChoice("walking", "generic"),
    SportChoice("hiking", "generic"),
    SportChoice("cycling", "road"),
    SportChoice("cycling", "gravel_cycling"),
    SportChoice("cycling", "mountain"),
    SportChoice("cycling", "indoor_cycling"),
    SportChoice("swimming", "lap_swimming"),
    SportChoice("swimming", "open_water"),
    SportChoice("training", "strength_training"),
    SportChoice("yoga", null),
    SportChoice("rock_climbing", "generic"),
    SportChoice("rock_climbing", "bouldering"),
    SportChoice("rowing", "generic"),
    SportChoice("kayaking", "generic"),
    SportChoice("cross_country_skiing", "generic"),
    SportChoice("alpine_skiing", "generic"),
    SportChoice("generic", null),
)
