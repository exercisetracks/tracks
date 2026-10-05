// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

/**
 * What one of a sport is called, counted: "1 run", "3 rides".
 *
 * The donut's centre put the count over the sport's *name*, which is a
 * gerund — "1 Running", "5 Cycling" — and read as broken English. A count
 * wants the noun for one outing, singular or plural to match.
 *
 * Sports with no everyday noun for one outing ("strength training",
 * "paddling") fall back to "session", which is what people call them.
 */
internal fun activityNoun(sport: String?, count: Int): String {
    val (one, many) = NOUNS[sport?.lowercase()] ?: if (sport == null) ACTIVITY else SESSION
    return if (count == 1) one else many
}

private val ACTIVITY = "activity" to "activities"
private val SESSION = "session" to "sessions"

private val NOUNS: Map<String, Pair<String, String>> = mapOf(
    "running" to ("run" to "runs"),
    "trail_running" to ("run" to "runs"),
    "treadmill_running" to ("run" to "runs"),
    "cycling" to ("ride" to "rides"),
    "road_biking" to ("ride" to "rides"),
    "gravel_cycling" to ("ride" to "rides"),
    "indoor_cycling" to ("ride" to "rides"),
    "virtual_cycling" to ("ride" to "rides"),
    "e_biking" to ("ride" to "rides"),
    "mtb" to ("ride" to "rides"),
    "mountain_biking" to ("ride" to "rides"),
    "swimming" to ("swim" to "swims"),
    "open_water_swimming" to ("swim" to "swims"),
    "hiking" to ("hike" to "hikes"),
    "walking" to ("walk" to "walks"),
    "rowing" to ("row" to "rows"),
    "indoor_rowing" to ("row" to "rows"),
    "climbing" to ("climb" to "climbs"),
    "rock_climbing" to ("climb" to "climbs"),
    "bouldering" to ("climb" to "climbs"),
    "skiing" to ("ski day" to "ski days"),
    "alpine_skiing" to ("ski day" to "ski days"),
    "nordic_skiing" to ("ski" to "skis"),
    "cross_country_skiing" to ("ski" to "skis"),
    "yoga" to ("class" to "classes"),
    "workout" to ("workout" to "workouts"),
)
