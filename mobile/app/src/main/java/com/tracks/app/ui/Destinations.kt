// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every page in the app, in one list.
 *
 * ## Why this is no longer a bar plus a drawer of leftovers
 *
 * There used to be two lists: four destinations in a bottom bar, and everything
 * that would not fit behind a "More" tab. That split is a layout constraint
 * pretending to be information architecture. A bottom bar holds five items, so
 * the fifth slot became a door to a second menu — and which side of that door a
 * page landed on was decided by the width of a phone rather than by how often it
 * is used. Health, the training plan, strength and mobility all sat behind
 * "More" not because they matter less but because there were only four slots.
 *
 * A sidebar has no such ceiling. Every page fits in it comfortably, they are all
 * reachable in the same two gestures, and — the reason this now matches the
 * desktop app — the same names appear in the same order on both, so someone who
 * knows one knows the other. See [TracksNavHost] for the shell.
 *
 * The order is the browser's, and so is the list: every page here exists there.
 *
 * There was briefly a tenth, "Record a run" — a stopwatch with GPS behind it,
 * unattached to the plan. Recording did not go away with it; it moved to where
 * the plan already is. A planned run is started from the workout on the
 * dashboard or the calendar, which is the only place that knows what the run is
 * supposed to be. See [com.tracks.app.ui.workout.GuidedWorkoutScreen].
 *
 * Icons come from `material-icons-core`. The extended set has better glyphs for
 * several of these — a barbell, a stretching figure — and costs several
 * megabytes of APK for eight icons, which is not a trade this app makes.
 */
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    // The web sidebar's order and names, exactly (frontend/src/components/
    // Layout.jsx). Health sits second on both — next to the dashboard, the
    // pair opened daily. The pages once had phone-only names ("Training plan",
    // "Mobility", "Map"), which undid the point of matching, so they went.
    // Constant names stay as they were, since routes and saved navigation
    // state use them.
    Dashboard("dashboard", "Dashboard", Icons.Filled.Home),
    Health("health", "Health", Icons.Filled.Favorite),
    Activities("activities", "Activities", Icons.Filled.List),
    Calendar("calendar", "Training", Icons.Filled.DateRange),
    Strength("strength", "Strength", Icons.Filled.Build),
    Mobility("mobility", "Flexibility", Icons.Filled.Refresh),
    RacePlans("race-plans", "Race Plans", Icons.Filled.Star),
    Map("map", "Maps", Icons.Filled.Place),
    Music("music", "Music", Icons.Filled.PlayArrow),
    Settings("settings", "Settings", Icons.Filled.Settings),
    ;

    companion object {
        /**
         * Where the app opens — the dashboard, as on the web.
         *
         * This was Activities for a while, on the reasoning that it is the one
         * screen that renders from the local mirror with no network, so a cold
         * start in the field would never look broken. That optimised the wrong
         * case: opening the app on a normal day, with signal, is overwhelmingly
         * the common one, and landing somewhere other than the web app's home
         * screen is a mismatch every single time to avoid an empty screen that
         * happens rarely. The offline concern is real but belongs in the
         * dashboard's own empty states, not in the choice of start destination.
         */
        val start = Dashboard

        /** The page a route belongs to, or null for a detail screen. */
        fun forRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
