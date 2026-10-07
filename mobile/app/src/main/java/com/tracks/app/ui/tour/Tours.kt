// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.tour

import com.tracks.app.ui.Destination

/**
 * One tip: what it says, and which element it points at.
 *
 * [anchor] names a [tourAnchor] somewhere on the page. Null — or an anchor the
 * page is not showing right now, such as the upcoming card before there is a
 * plan — and the tip is shown centred instead, as on the web.
 */
data class TourStep(
    val title: String,
    val body: String,
    val anchor: String? = null,
)

/**
 * The phone's tutorial — the web's (frontend/src/components/tour/tours.js),
 * page for page, rewritten for a phone's controls.
 *
 * Same pages, same order of ideas, same titles wherever the thing being shown
 * is the same thing. The bodies differ where the gesture does: the web's
 * sidebar is the phone's drawer, a click is a tap, a column header is a pill
 * in the bar, and the phone has gestures the browser does not (pull to sync,
 * long-press to draw, drag to reschedule) — which are exactly the things
 * nobody finds without being told.
 *
 * Pure data, so a test can hold it to the pages that exist ([TourContentTest]).
 */
object Tours {
    /**
     * The key a tour is remembered under in the account's `tour_seen` map.
     *
     * Prefixed, because the map is shared with the web (spec/sync.yaml,
     * `settings`) and the two tutorials are not the same tutorial: someone who
     * has clicked through the browser's has still never been shown where the
     * drawer is or that the dashboard pulls down to sync. Unprefixed, finishing
     * either would have silenced both.
     */
    fun seenKey(id: String) = "phone.$id"

    /** The tour for a route, or null for a page that has none. */
    fun idForRoute(route: String?): String? = when (route) {
        null -> null
        ACTIVITY_ROUTE -> "activity"
        else -> Destination.forRoute(route)?.let(::idFor)
    }

    /** The tour that introduces [destination] the first time it is opened. */
    fun idFor(destination: Destination): String = when (destination) {
        Destination.Dashboard -> "dashboard"
        Destination.Health -> "health"
        Destination.Activities -> "activities"
        Destination.Calendar -> "training"
        Destination.Strength -> "strength"
        Destination.Mobility -> "flexibility"
        Destination.RacePlans -> "race-plans"
        Destination.Map -> "maps"
        Destination.Music -> "music"
        Destination.Settings -> "settings"
    }

    /** The activity page — the one detail route with a tour, as on the web. */
    const val ACTIVITY_ROUTE = "activity/{activityId}"

    val all: Map<String, List<TourStep>> = mapOf(
        "dashboard" to listOf(
            TourStep(
                "Welcome to Tracks",
                "This is your fitness command center. Here's a quick tour of what each part does — " +
                    "you can replay it any time from Settings.",
            ),
            TourStep(
                "Your sections",
                "Tap here, or swipe in from the left edge, to open the menu: Dashboard, Health, " +
                    "Activities, Training, Strength, Flexibility, Race Plans and Maps. Each one shows " +
                    "its own quick tips the first time you open it.",
                anchor = "nav",
            ),
            TourStep(
                "Pick a time window",
                "Every stat and chart on this page follows these pills — from the last week all the " +
                    "way to your lifetime totals.",
                anchor = "dashboard-period",
            ),
            TourStep(
                "Your headline numbers",
                "Totals and per-activity averages for the selected period: activity count, distance, " +
                    "time, and more.",
                anchor = "dashboard-overview",
            ),
            TourStep(
                "What's next",
                "Today's planned or recommended workout, with your readiness and VO₂ max. Tap a " +
                    "planned workout to start it — the phone guides you through it with GPS and cues.",
                anchor = "dashboard-upcoming",
            ),
            TourStep(
                "Your fitness trend",
                "Fitness (CTL), Fatigue (ATL), and Form over time. It climbs as you train and dips " +
                    "when you rest. Tap the ? beside any title for what its number means.",
                anchor = "dashboard-fitness",
            ),
            TourStep(
                "Filter by sport",
                "Tap a sport to narrow the charts above to just that one; tap it again to see " +
                    "everything.",
                anchor = "dashboard-sports",
            ),
            TourStep(
                "Pull to sync",
                "Drag this page down to pull new activities, sleep, and readiness off your watch. " +
                    "Without a watch, the same gesture refreshes the page.",
            ),
            TourStep(
                "Settings & replay",
                "Profile, training zones, your watch, backups — and the switch for these tips — live " +
                    "in Settings, at the bottom of the menu.",
                anchor = "nav",
            ),
        ),

        "health" to listOf(
            TourStep(
                "Every reading on its scale",
                "Each dial shows today's reading against its range — green is good, red is worth a " +
                    "look. Tap any dial to see its history and what the number means.",
                anchor = "health-dials",
            ),
            TourStep(
                "Pick the window",
                "How far back every history on the page looks, from the last week to your lifetime.",
                anchor = "health-range",
            ),
            TourStep(
                "Your nights",
                "Resting heart rate, HRV, breathing, stress and sleep — recorded while you wear the " +
                    "watch overnight. Tap Sleep to see each night stage by stage.",
                anchor = "health-vitals",
            ),
            TourStep(
                "Log today",
                "Weight, water, and food in one sheet. Save a meal and it becomes a one-tap choice " +
                    "next time.",
                anchor = "health-log",
            ),
            TourStep(
                "Medications",
                "Add what you take and tick off each dose. Reminders arrive as notifications, and " +
                    "the history shows what was taken when.",
                anchor = "health-meds",
            ),
            TourStep(
                "Injury tracking",
                "Log injuries onto a timeline and see the activities that may have contributed to " +
                    "each one.",
                anchor = "health-injuries",
            ),
        ),

        "activities" to listOf(
            TourStep(
                "Sort your list",
                "Order by date or by name. The controls stay in the bar, so they are always in reach " +
                    "however far down you scroll.",
                anchor = "activities-sort",
            ),
            TourStep(
                "Filter by type",
                "Show only some sports — pick as many as you like. A line above the list says when " +
                    "something is hidden, with a button to clear it.",
                anchor = "activities-type",
            ),
            TourStep(
                "Open the details",
                "Tap any activity for its map, splits, and charts.",
                anchor = "activities-list",
            ),
            TourStep(
                "Group a trip",
                "Long-press an activity to start selecting, tap a few more, then merge them into one " +
                    "trip — a multi-day hike, say, or a race and its warm-up.",
                anchor = "activities-list",
            ),
        ),

        "activity" to listOf(
            TourStep(
                "One activity, laid out for its sport",
                "A run shows pace and splits, a ride power, a climb its routes — each sport gets the " +
                    "cards that matter for it. Scroll for the map and the charts.",
                anchor = "activity-body",
            ),
            TourStep(
                "Rename, re-sport, hide",
                "Tap the name or the pencil to rename the activity, add notes, correct its sport, " +
                    "hide it from your stats, or delete it.",
                anchor = "activity-edit",
            ),
        ),

        "training" to listOf(
            TourStep(
                "Training goals",
                "Goals shape your coaching. Set what you're working toward and Tracks tailors its " +
                    "recommendations to match.",
            ),
            TourStep(
                "One active goal",
                "You keep one goal active at a time. Choose a race with a date and Tracks builds a full " +
                    "plan through Base, Build, Peak, and Taper phases. Tap the goal to change it.",
                anchor = "goals-active",
            ),
            TourStep(
                "Week or month",
                "The week shows every session in full; the month is the overview. The plan rebuilds " +
                    "itself whenever the goal or your settings change.",
                anchor = "plan-view",
            ),
            TourStep(
                "Your plan, day by day",
                "Tap a workout to start it guided. Long-press and drag it to move it to another day — " +
                    "or hold it still to pick a day further away. If you have a watch, the plan follows " +
                    "to it on its own.",
                anchor = "plan-calendar",
            ),
            TourStep(
                "Every goal",
                "Add a weekly volume target, a general fitness push, or a race. Activate any goal to " +
                    "switch the plan to it.",
                anchor = "goals-list",
            ),
        ),

        "strength" to listOf(
            TourStep(
                "Strength workouts",
                "Your saved workouts. Start one for a guided session, or build a new one from the " +
                    "exercise library.",
                anchor = "strength-workouts",
            ),
            TourStep(
                "Find an exercise",
                "Search by name, pick muscles on the body map, or show only what your equipment " +
                    "allows. Add your own movements here too.",
                anchor = "strength-library",
            ),
            TourStep(
                "Log & progress",
                "Tap an exercise for how-to guidance and your 1-rep-max history. Tick a few to start a " +
                    "session or save them as a workout — logged sessions feed the progression engine, " +
                    "so your weights climb over time.",
                anchor = "strength-exercise",
            ),
        ),

        "flexibility" to listOf(
            TourStep(
                "Flexibility & mobility",
                "Flows string stretches into a guided routine with a timer for each hold. Start one, " +
                    "or tap New to build your own.",
                anchor = "flex-flows",
            ),
            TourStep(
                "Guided & custom",
                "Browse the stretch library by name or muscle, open any stretch for instructions, or " +
                    "create your own. Tick a few to run them as a flow.",
                anchor = "flex-library",
            ),
        ),

        "race-plans" to listOf(
            TourStep(
                "Race pacing plans",
                "Pacing guides for your events — built from your fitness, freshness, and the course " +
                    "profile.",
                anchor = "raceplans-intro",
            ),
            TourStep(
                "Open an event",
                "Tap an event for its predicted finish, lap targets, and fuelling. Give it a course " +
                    "from a saved track or draw one on the map. No events yet? Create one under Training.",
                anchor = "raceplans-card",
            ),
        ),

        "maps" to listOf(
            TourStep(
                "Your map",
                "Everywhere you've been, drawn as a heatmap. Pinch to zoom, twist to rotate, and drag " +
                    "two fingers up to tilt into 3D.",
            ),
            TourStep(
                "Where you are",
                "Tap once to find yourself, again to follow your heading, and again to stop.",
                anchor = "map-locate",
            ),
            TourStep(
                "Search",
                "Find a place by name and fly to it.",
                anchor = "map-search",
            ),
            TourStep(
                "Tracks & waypoints",
                "Everything you've saved, and what is on your watch. Send tomorrow's route to the wrist " +
                    "from here.",
                anchor = "map-library",
            ),
            TourStep(
                "Map options",
                "Download areas for offline use, toggle trails and overlays, and show the legend.",
                anchor = "map-menu",
            ),
            TourStep(
                "Draw a route",
                "Long-press anywhere to start a route there — it snaps to trails. Keep long-pressing to " +
                    "add points, then save it to your tracks. A plain tap on the map shows what is there, " +
                    "with the weather.",
            ),
        ),

        "music" to listOf(
            TourStep(
                "Your music server",
                "Connect a music server and the watch can play from it directly — no cable, no " +
                    "uploading.",
                anchor = "music-server",
            ),
            TourStep(
                "The watch app",
                "Tracks Music is sent to the watch straight from this phone, then signs in to your " +
                    "music server by itself. Find it on the watch under Music › Music Providers.",
                anchor = "music-watch",
            ),
            TourStep(
                "The USB library",
                "Files uploaded in the web app, carried over the cable. Choose what goes on the watch " +
                    "here.",
                anchor = "music-library",
            ),
        ),

        "settings" to listOf(
            TourStep(
                "Tutorial controls",
                "Toggle the tips on or off, or replay the whole tutorial from the start. The rest of " +
                    "this page tunes your profile, zones, watch, and appearance.",
                anchor = "settings-tutorial",
            ),
            TourStep(
                "Your watch",
                "Pair and sync your watch, and choose which notifications reach it. No watch? Switch it " +
                    "off and the app stops asking about one.",
                anchor = "settings-watch",
            ),
            TourStep(
                "A server is optional",
                "This phone is a complete Tracks on its own. Link a server to get the web app, map " +
                    "downloads, and an off-phone copy of everything.",
                anchor = "settings-server",
            ),
            TourStep(
                "Keep a backup",
                "Without a server, this phone holds the only copy of your history. Save an encrypted " +
                    "backup somewhere else.",
                anchor = "settings-backup",
            ),
        ),
    )
}
