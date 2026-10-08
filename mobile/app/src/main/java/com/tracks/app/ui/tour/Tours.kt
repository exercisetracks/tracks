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
                "Tap here, or swipe right from anywhere on the page, to open the menu: Dashboard, " +
                    "Health, Activities, Training, Strength, Flexibility, Race Plans and Maps. Swipe " +
                    "left to close it. Each page shows its own quick tips the first time you open it.",
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
                "Fitness and fatigue",
                "Every workout earns a training-stress score from how long and how hard it was. " +
                    "Fitness is the average of those over the last six weeks, so it rises slowly with " +
                    "steady training and fades slowly when you stop. Fatigue is the same over one " +
                    "week, so it jumps after a hard block and drops within days of rest.",
                anchor = "dashboard-fitness",
            ),
            TourStep(
                "Reading form",
                "Form is fitness minus fatigue. Below zero you are carrying more fatigue than " +
                    "fitness — normal while building, and the dip is where you get fitter. Around " +
                    "zero to slightly positive is fresh: that is where you want to be on race day. " +
                    "Far below zero for weeks is a sign to ease off. Press and hold the chart to read " +
                    "any day.",
                anchor = "dashboard-fitness",
            ),
            TourStep(
                "Week by week",
                "How much you trained each week, Monday to Sunday. The bars are distance and the " +
                    "line is time, each on its own scale, so a long slow week and a short fast one " +
                    "read fairly. An even row of bars is consistency; a spike then a gap is not.",
                anchor = "dashboard-volume",
            ),
            TourStep(
                "Activity history",
                "One square per day, darker the more you trained, so streaks and breaks show at a " +
                    "glance. On long windows it becomes a year-by-year grid.",
                anchor = "dashboard-history",
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
            TourStep(
                "Like or rule out",
                "The heart marks an exercise you like: your training plan picks it first whenever it " +
                    "fits the session. The no-entry sign rules one out — the plan never schedules it, " +
                    "for an injury or a machine you hate. Tap again to clear either. Neither hides " +
                    "it here; filter the library by Preferred or Excluded to see them.",
                anchor = "strength-exercise",
            ),
            TourStep(
                "Build a workout",
                "Tap New to open the builder. Name it, add exercises from the library, and set each " +
                    "one's sets, reps and weight. Drag to reorder; add a Superset or Repeat block to " +
                    "group exercises, and Rest blocks between them. Save it and start it any time for " +
                    "a guided session — with Schedule in my plan ticked, your training plan uses it too.",
                anchor = "strength-workouts",
            ),
            TourStep(
                "Filter by muscle",
                "Swipe left from anywhere on this page to pull in the muscle map, and tap muscles to " +
                    "show only exercises that work them. Swipe right to put it away — the same " +
                    "swipe right, from the page itself, opens the menu.",
                anchor = "strength-library",
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
            TourStep(
                "Like or rule out",
                "The heart marks a stretch you like: the mobility sessions in your plan pick it first. " +
                    "The no-entry sign rules one out, so the plan never schedules it. Tap again to " +
                    "clear either.",
                anchor = "flex-library",
            ),
            TourStep(
                "Build a flow",
                "Tap New to open the builder: name the flow, add stretches, set each hold's length, " +
                    "drag to reorder and add rests. Save it and start it for a timed, guided routine.",
                anchor = "flex-flows",
            ),
            TourStep(
                "Filter by muscle",
                "Swipe left from anywhere on this page to pull in the muscle map; tap muscles to show " +
                    "only stretches for them. Swipe right to put it away.",
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
                "Every race goal with a date gets a plan here on its own — there is nothing to " +
                    "generate. No events yet? Create a race goal under Training. Tap an event to open " +
                    "its plan.",
                anchor = "raceplans-card",
            ),
            TourStep(
                "Pacing",
                "The plan predicts your finish from your fitness and splits it into lap targets. " +
                    "The pacing slider sets how your second half compares to the first — even, or " +
                    "faster for a negative split. The course shapes every lap: import a GPX file, use " +
                    "a saved track, or draw one on the map — or, without one, pick how hilly it is.",
            ),
            TourStep(
                "Fuelling",
                "How much carbohydrate, fluid and sodium to take each hour, worked out from the " +
                    "race's length and weather; change any target if you know yours. Tap + to add the " +
                    "gels, drinks and bars you use, tick the ones you'll carry, and the plan turns the " +
                    "targets into a timeline of what to take when.",
            ),
            TourStep(
                "On the watch",
                "With a watch, the plan goes to it on the next sync, and it coaches you to each " +
                    "lap's target during the race — by pace, or pace with a heart-rate ceiling.",
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
