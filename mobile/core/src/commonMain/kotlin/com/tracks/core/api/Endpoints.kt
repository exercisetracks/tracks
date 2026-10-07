// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

/**
 * Every server path this client calls, as OpenAPI templates.
 *
 * These exist so [ApiContractTest] can check them against the server's own
 * `/openapi.json`. That check is what a generated client would have bought —
 * noticing when the contract moves — without generating two hundred endpoints
 * to get at the dozen that matter.
 *
 * A path used by the client but missing from this list is invisible to that
 * check, so add both together.
 */
object Endpoints {
    const val CAPABILITIES = "/capabilities"
    const val VERSION = "/version"

    const val LOGIN = "/auth/login"
    const val REFRESH = "/auth/refresh"
    const val DEVICE_KEYS = "/auth/device-keys"
    const val DEVICE_KEY_BY_ID = "/auth/device-keys/{key_id}"
    const val DEVICE_UNLOCK = "/auth/device-unlock"
    const val LOGOUT = "/auth/logout"
    const val CHANGE_PASSWORD = "/users/me/password"

    // Music. The library and the music-server connection are ordinary authed
    // paths; /music/ciq/* is what the *watch* calls with its own scoped token,
    // and the phone only ever touches the pairing end of it.
    const val MUSIC_TRACKS = "/music/tracks"
    const val MUSIC_TRACKS_LOAD = "/music/tracks/load"
    const val MUSIC_DEVICE_PLAN = "/music/device-plan"
    const val MUSIC_SERVER = "/music/server"
    const val MUSIC_SMART = "/music/server/smart"
    const val MUSIC_SMART_IMPORT = "/music/server/smart/{kind}/import"
    const val MUSIC_SMART_BY_KIND = "/music/server/smart/{kind}"
    const val MUSIC_REMOTE_PLAYLISTS = "/music/server/playlists"
    const val MUSIC_REMOTE_SEARCH = "/music/server/search"
    const val MUSIC_PLAYLIST_IMPORT = "/music/server/playlists/{playlist_id}/import"
    const val MUSIC_WATCH_CONFIG = "/music/server/watch-config"

    fun musicSmartImport(kind: String) = "/music/server/smart/${encodePathSegment(kind)}/import"
    fun musicSmartByKind(kind: String) = "/music/server/smart/${encodePathSegment(kind)}"
    fun musicPlaylistImport(id: String) = "/music/server/playlists/${encodePathSegment(id)}/import"


    // The replica protocol (spec/sync.yaml, "Wire").
    const val SYNC_PUSH = "/sync/push"
    const val SYNC_PULL = "/sync/pull"
    const val SYNC_BLOB = "/sync/blobs/{sha256}"
    fun syncBlob(sha256: String) = "/sync/blobs/${encodePathSegment(sha256)}"
    const val ACTIVITY_DETAIL = "/activities/{activity_id}"
    const val ACTIVITY_TRACK = "/activities/{activity_id}/track"
    const val ACTIVITY_LAPS = "/activities/{activity_id}/laps"
    const val HEATMAP = "/activities/heatmap"
    const val TRACKS_GEOJSON = "/activities/tracks-geojson"
    const val MAP_STYLE = "/maps/style.json"

    // Offline map regions. The server does the extraction; the phone asks for
    // an area and watches. See [com.tracks.core.api.MapRegion].
    const val REGIONS = "/maps/regions"
    const val REGION_BY_ID = "/maps/regions/{region_id}"
    const val REGION_PROGRESS = "/maps/regions/{region_id}/progress"
    const val REGION_DOWNLOAD = "/maps/regions/download"
    const val REGION_ESTIMATE = "/maps/regions/estimate"
    const val REGION_SUGGEST_NAME = "/maps/regions/suggest-name"

    // Inspecting a point, finding a place, and snapping a line to real trails.
    const val MAP_POINT = "/maps/point"

    // No map forecast endpoints: the phone asks Open-Meteo itself
    // (core/weather/OpenMeteo.kt). The server keeps /maps/point/weather and
    // /maps/point/hourly for phones on older releases.
    const val POI_SEARCH = "/maps/poi/search"

    /**
     * Every named place in a bbox, compact — what a phone downloads once
     * alongside a region so [TracksClient.searchPoi] has a local fallback
     * with no gazetteer to ask. See [ROUTE_OFFLINE_MANIFEST] for the sibling
     * this mirrors, and its own doc comment for why this is one request
     * rather than a manifest of its own: there is no pre-built grid of files
     * to diff against here, only a live query capped to a small area.
     */
    const val POI_OFFLINE = "/maps/poi/offline"
    const val COURSES = "/maps/courses"
    const val COURSES_GEOJSON = "/maps/courses/geojson"
    const val COURSES_IMPORT = "/maps/courses/import"
    const val WAYPOINTS = "/maps/waypoints"
    const val ROUTE_SNAP = "/maps/route/snap"

    /**
     * DEM heights under a line the router did not draw.
     *
     * A snapped route carries its own elevation — BRouter routes on it —
     * so this is for the other kind: a line joined straight through the
     * taps, whose points came off a touchscreen and know nothing about
     * the ground.
     */
    const val ROUTE_ELEVATION = "/maps/route/elevation"

    /**
     * The routing data a phone needs to snap a line with no network.
     *
     * [ROUTE_OFFLINE_MANIFEST] lists the rd5 segments covering a bbox plus the
     * profile files they decode against; the other two hand the bytes over. All
     * three are the *data* half of trail routing — the engine itself is
     * vendored into `:routing-brouter` and runs on the device.
     */
    const val ROUTE_OFFLINE_MANIFEST = "/maps/route/offline/manifest"
    const val ROUTE_OFFLINE_SEGMENT = "/maps/route/offline/segments/{name}"
    const val ROUTE_OFFLINE_PROFILE = "/maps/route/offline/profiles/{name}"

    /**
     * One Terrarium-encoded DEM raster tile — the same bytes and the same URL
     * shape `map_style.json`'s `dem` raster-dem source already uses for
     * MapLibre's 3D terrain, fetched here instead by [TracksClient] itself so
     * [com.tracks.app.map.OfflineDem] can decode elevation out of it directly.
     *
     * Deliberately **not** in [all]: this is served by `go-pmtiles`, not the
     * FastAPI backend — Caddy proxies every tile under this path straight
     * past it, so it never appears in `/openapi.json` and [ApiContractTest] would
     * fail trying to find it there. The style JSON's own embedding of this
     * same path is the closest thing to a contract check this one has.
     */
    const val DEM_TILE = "/tiles/master_dem/{z}/{x}/{y}.webp"

    // Dashboard. Every one of these is a server-side aggregate; see
    // DashboardModels for why none of them is mirrored locally.
    const val METRICS_SUMMARY = "/metrics/summary"
    const val METRICS_BY_SPORT = "/metrics/by-sport"
    const val METRICS_ACTIVITY_CALENDAR = "/metrics/activity-calendar"
    const val METRICS_VO2MAX_HISTORY = "/metrics/vo2max-history"
    const val METRICS_WEEKLY_VOLUME = "/metrics/weekly-volume"
    const val METRICS_TRAINING_LOAD = "/metrics/training-load"
    const val METRICS_READINESS_HISTORY = "/metrics/readiness-history"
    const val COACHING_TODAY = "/coaching/today"
    const val UPCOMING_WORKOUTS = "/coaching/workouts/upcoming"

    const val ACTIVITY_CLIMBS = "/activities/{activity_id}/climbs"
    const val ACTIVITY_SETS = "/activities/{activity_id}/sets"
    const val DAILY_METRICS = "/metrics/daily"

    /** The account's own preferences — units and accent colour live here. */
    const val USER_ME = "/users/me"
    const val USER_SETTINGS = "/users/me/settings"
    const val WEEKLY_PLAN = "/coaching/plan"

    /** The account's calendar subscription URL — a server feature: a calendar app polls it. */
    const val USER_ICS_TOKEN = "/coaching/ics-token"

    // Health: the entered half of the page, as opposed to what the watch
    // measured. See HealthModels.
    const val INJURIES = "/health/injuries"
    const val INJURY_BY_ID = "/health/injuries/{injury_id}"
    const val INJURY_ACTIVITIES = "/health/injuries/{injury_id}/activities"
    const val HEALTH_DAILY_PATCH = "/health/daily/{metric_date}"

    /**
     * Re-read the files already ingested, for the metrics the parser used to
     * miss. Queued server-side; see the endpoint's own note for why nothing is
     * deleted to make it work.
     */
    const val HEALTH_DAILY_REPARSE = "/health/daily/reparse"

    /**
     * One night's sleep stages.
     *
     * Per night rather than folded into the daily list: the totals are seven
     * numbers a day and this is a few dozen spans, so carrying it on every day
     * of a year's history would multiply the size of the request the health
     * page makes on every load, for a chart that shows one night at a time.
     */
    const val SLEEP_NIGHT = "/health/sleep/{metric_date}"

    /**
     * The stress curve behind the daily averages, for a window of days.
     *
     * Separate from the daily metrics for the same reason [SLEEP_NIGHT] is: a
     * day is some five hundred readings and the row it belongs to is one. The
     * server caps the window — see its handler — because over a year the curve
     * is a solid block of ink and the daily averages are the readable answer
     * at that scale.
     */
    const val STRESS_DETAIL = "/health/stress"
    const val MEDICATIONS = "/medications"
    const val MEDICATION_BY_ID = "/medications/{med_id}"
    const val MEDICATION_LOG = "/medications/log"
    const val MEDICATION_LOG_ENTRY = "/medications/log/{entry_id}"
    const val MEALS = "/meals"
    const val MEAL_LOG = "/meals/log"
    const val PLAN_WORKOUT = "/coaching/plan/workouts/{workout_id}"

    /** Adding a workout the plan did not prescribe. See PlannedWorkoutCreate. */
    const val PLAN_WORKOUTS = "/coaching/plan/workouts"

    /**
     * Goals, and the plan generated from one.
     *
     * The plan carries the VDOT that running pace targets derive from, which is
     * why an offline watch push needs it — see
     * `com.tracks.core.sync.CoachingContext`.
     */
    const val GOALS = "/coaching/goals"
    const val GOAL_PLAN = "/coaching/goals/{goal_id}/plan"
    const val GOAL_PLAN_GENERATE = "/coaching/goals/{goal_id}/plan/generate"

    // Training, the parts of it the phone can now run on its own. The library
    // endpoints are read-mostly and shared with the browser; see TrainingModels.
    const val STRETCHES = "/flexibility/stretches"
    const val CUSTOM_STRETCHES = "/flexibility/custom-stretches"
    const val FLOWS = "/flexibility/flows"
    const val FLOW_BY_ID = "/flexibility/flows/{flow_id}"
    /** The user's own saved workouts — templates, not sessions. */
    const val WORKOUTS = "/workouts"
    const val WORKOUT_BY_ID = "/workouts/{workout_id}"

    const val STRENGTH_EXERCISES = "/strength/exercises"
    const val STRENGTH_CUSTOM_EXERCISES = "/strength/custom-exercises"
    const val STRENGTH_EQUIPMENT = "/strength/equipment"
    const val STRENGTH_ONE_RM = "/strength/exercises/{exercise_name}/1rm"
    const val STRENGTH_HISTORY = "/strength/history"
    const val STRENGTH_PROGRESS = "/strength/progress"

    /**
     * Where a finished session goes.
     *
     * Not under `/strength`, and deliberately so: this is the one write in the
     * training API, and the server does far more with it than store sets — it
     * advances each exercise's estimated max and progression stage, and ticks
     * off the planned workout it completes. Logging a session is what makes the
     * next one heavier.
     */
    const val WORKOUT_SESSIONS = "/workouts/sessions"

    const val PUSH_LIST = "/device-sync/upload-list"
    const val MARK_UPLOADED = "/device-sync/mark-uploaded"

    /**
     * The training calendar, as a FIT type-7 schedule file.
     *
     * Fetched *after* [MARK_UPLOADED], not before — the server builds this from
     * workouts it believes the watch already holds, so asking first produces a
     * schedule that points at files the watch has never seen.
     */
    const val SCHEDULE_FIT = "/device-sync/schedule-fit"
    const val SCHEDULE_BUNDLE = "/device-sync/schedule-bundle"
    /**
     * The server's forecast for the watch — the fallback for a phone whose own
     * files hold no activity with GPS to locate a forecast by. See
     * `WatchManager.directForecast`.
     */
    const val WATCH_WEATHER = "/device-sync/weather"
    const val DELETE_LIST = "/device-sync/delete-list"
    const val AGPS = "/device-sync/agps"
    const val WATCH_SYNCED = "/device-sync/synced"

    const val SYNC_AGENTS = "/sync-agents/"
    const val SYNC_PUBKEY = "/sync/pubkey"
    const val SYNC_INGEST = "/sync/ingest"

    /** Both behind the `sync_ingest_batch` feature; a server without it gets [SYNC_INGEST], file by file. */
    const val SYNC_INGEST_MISSING = "/sync/ingest/missing"
    const val SYNC_INGEST_BATCH = "/sync/ingest/batch"

    /**
     * Courses the watch had that Tracks did not put there.
     *
     * A different endpoint from SYNC_INGEST because it is a different
     * thing: not an encrypted activity queued for import, but a route the
     * server parses immediately into a saved track. It is sent in the
     * clear — a course is public cartography the user chose to carry, not
     * a record of where they went.
     */
    const val COURSE_INGEST = "/maps/sync/course-ingest"

    /**
     * The watch's own Locations.fit, handed over whole.
     *
     * One file rather than an inventory, because a watch keeps every saved
     * place in one — so this is not "here is a place", it is "here is what the
     * device currently holds", and the server reconciles against it.
     */
    const val WAYPOINT_INGEST = "/maps/sync/waypoint-ingest"

    /** Every templated path above, for the contract check. */
    val all: List<String> = listOf(
        CAPABILITIES,
        LOGIN, REFRESH, DEVICE_KEYS, DEVICE_KEY_BY_ID, DEVICE_UNLOCK,
        SYNC_PUSH, SYNC_PULL, SYNC_BLOB, ACTIVITY_DETAIL, ACTIVITY_TRACK, ACTIVITY_LAPS,
        HEATMAP, TRACKS_GEOJSON, MAP_STYLE,
        REGIONS, REGION_BY_ID, REGION_PROGRESS, REGION_DOWNLOAD,
        REGION_ESTIMATE, REGION_SUGGEST_NAME,
        MAP_POINT, POI_SEARCH, POI_OFFLINE, ROUTE_SNAP,
        ROUTE_ELEVATION,
        COURSES, WAYPOINTS,
        ROUTE_OFFLINE_MANIFEST, ROUTE_OFFLINE_SEGMENT, ROUTE_OFFLINE_PROFILE,
        METRICS_SUMMARY, METRICS_BY_SPORT, METRICS_ACTIVITY_CALENDAR,
        METRICS_VO2MAX_HISTORY, METRICS_WEEKLY_VOLUME, METRICS_TRAINING_LOAD,
        METRICS_READINESS_HISTORY, COACHING_TODAY, UPCOMING_WORKOUTS,
        ACTIVITY_CLIMBS, ACTIVITY_SETS, DAILY_METRICS, WEEKLY_PLAN, USER_ICS_TOKEN, USER_ME, USER_SETTINGS,
        INJURIES, INJURY_BY_ID, INJURY_ACTIVITIES, HEALTH_DAILY_PATCH, HEALTH_DAILY_REPARSE, SLEEP_NIGHT,
        STRESS_DETAIL,
        MEDICATIONS, MEDICATION_BY_ID, MEDICATION_LOG, MEDICATION_LOG_ENTRY,
        MEALS, MEAL_LOG, PLAN_WORKOUT,
        GOALS, GOAL_PLAN, GOAL_PLAN_GENERATE, PLAN_WORKOUTS,
        PUSH_LIST, MARK_UPLOADED, SCHEDULE_FIT, WATCH_WEATHER, DELETE_LIST, AGPS,
        WATCH_SYNCED,
        SYNC_AGENTS, SYNC_PUBKEY, SYNC_INGEST, SYNC_INGEST_MISSING, SYNC_INGEST_BATCH, COURSE_INGEST, WAYPOINT_INGEST,
        STRETCHES, CUSTOM_STRETCHES, FLOWS, FLOW_BY_ID,
        STRENGTH_EXERCISES, STRENGTH_CUSTOM_EXERCISES, STRENGTH_EQUIPMENT,
        STRENGTH_ONE_RM, STRENGTH_HISTORY, STRENGTH_PROGRESS, WORKOUT_SESSIONS,
        WORKOUTS, WORKOUT_BY_ID,
    )

    fun region(id: Int): String = "/maps/regions/$id"
    fun regionProgress(id: Int): String = "/maps/regions/$id/progress"

    fun deviceKey(id: Int): String = "/auth/device-keys/$id"
    fun activityDetail(activityId: Int): String = "/activities/$activityId"
    fun activityTrack(activityId: Int): String = "/activities/$activityId/track"
    fun activityLaps(activityId: Int): String = "/activities/$activityId/laps"
    fun activityClimbs(activityId: Int): String = "/activities/$activityId/climbs"
    fun activitySets(activityId: Int): String = "/activities/$activityId/sets"

    fun sleepNight(date: String): String = "/health/sleep/$date"

    fun workout(workoutId: Int): String = "/workouts/$workoutId"

    fun flow(flowId: Int): String = "/flexibility/flows/$flowId"

    fun goalPlan(goalId: Int): String = "/coaching/goals/$goalId/plan"
    fun goalPlanGenerate(goalId: Int): String = "/coaching/goals/$goalId/plan/generate"

    fun injury(injuryId: Int): String = "/health/injuries/$injuryId"
    fun injuryActivities(injuryId: Int): String = "/health/injuries/$injuryId/activities"
    fun course(trackId: Int): String = "/maps/courses/$trackId"
    fun waypoint(waypointId: Int): String = "/maps/waypoints/$waypointId"
    fun healthDaily(date: String): String = "/health/daily/$date"
    fun medication(medicationId: Int): String = "/medications/$medicationId"
    fun medicationLogEntry(entryId: Int): String = "/medications/log/$entryId"
    fun meal(mealId: Int): String = "/meals/$mealId"
    fun mealLogEntry(entryId: Int): String = "/meals/log/$entryId"
    fun planWorkout(workoutId: Int): String = "/coaching/plan/workouts/$workoutId"

    /**
     * Exercise names are the key, and they contain spaces ("Barbell Back
     * Squat"), so this is the one path segment here that must be encoded.
     * [encodePathSegment] is applied by the caller, not baked in, so the
     * template above still matches the server's OpenAPI path for the contract
     * check.
     */
    fun strengthOneRm(exerciseName: String): String =
        "/strength/exercises/${encodePathSegment(exerciseName)}/1rm"

    /**
     * A routing file by name. The server refuses anything that is not one of
     * its own names, so this only has to be honest about what it was given —
     * encoding is still applied, because an unencoded `..` would otherwise be
     * resolved by the HTTP stack before the server ever saw it.
     */
    fun routingSegment(name: String): String =
        "/maps/route/offline/segments/${encodePathSegment(name)}"

    fun routingProfile(name: String): String =
        "/maps/route/offline/profiles/${encodePathSegment(name)}"

    fun demTile(z: Int, x: Int, y: Int): String = "/tiles/master_dem/$z/$x/$y.webp"
}

/**
 * Percent-encode one path segment.
 *
 * Deliberately small: exercise names are library text — letters, spaces,
 * parentheses, the odd hyphen — not arbitrary user input traversing the URL
 * space. Everything outside the unreserved set is encoded byte-wise from UTF-8,
 * which is the rule that matters and is the same one the browser applies.
 */
internal fun encodePathSegment(raw: String): String = buildString {
    for (byte in raw.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c)
        else append('%').append(((byte.toInt() and 0xFF) / 16).toString(16).uppercase())
            .append(((byte.toInt() and 0xFF) % 16).toString(16).uppercase())
    }
}
