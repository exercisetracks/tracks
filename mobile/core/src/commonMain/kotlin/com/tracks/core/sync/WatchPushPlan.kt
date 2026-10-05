// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.CourseSummary
import com.tracks.core.api.TracksJson
import com.tracks.core.api.Waypoint
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The on-device half of a watch push: what the server's
 * `_course_upload_items`/`_waypoint_upload_items` compute against Postgres,
 * computed instead against the phone's own cached library — see
 * `com.tracks.app.device.WatchManager` for where this plugs in and why the
 * server is not asked anything at all for this half of a sync.
 */

/** A course flagged for the watch that the phone does not yet believe is on it. */
data class CoursePushJob(val id: Int, val name: String, val filename: String)

/**
 * Managed courses waiting on a push.
 *
 * Mirrors `_course_upload_items`'s filter: flagged for the watch, not
 * external (an external course was pulled FROM the watch and has nothing
 * further to push), and not already believed on-device.
 */
fun coursesToPush(courses: List<CourseSummary>): List<CoursePushJob> =
    courses.filter { !it.isExternal && it.loadToDevice && it.deviceStatus != "on_device" }
        .map { CoursePushJob(it.id, it.name, "TRK_${it.id}.fit") }

/**
 * What, if anything, the saved-places file needs — mirrors
 * `_waypoint_upload_items`/`_waypoint_clear_item`'s tri-state exactly. A
 * watch keeps every saved place in one file, so there is no such thing as
 * pushing just one of them: the set either matches what is flagged, or the
 * whole file is rebuilt.
 */
sealed interface WaypointPushJob {
    /** Rebuild `Locations.fit` with exactly these places. */
    data class Rebuild(val waypoints: List<Waypoint>) : WaypointPushJob

    /** Push an empty file — BLE cannot delete, so an empty file is how the last place comes off. */
    data object Clear : WaypointPushJob

    data object NothingToDo : WaypointPushJob
}

/**
 * What the saved-places file on the watch should look like, given what is
 * flagged ([Waypoint.loadToDevice]) and what the phone last believed was
 * actually there ([Waypoint.onWatch]).
 */
fun waypointPushPlan(waypoints: List<Waypoint>): WaypointPushJob {
    val desired = waypoints.filter { it.loadToDevice }
    val actual = waypoints.filter { it.onWatch }
    if (desired.isEmpty()) {
        return if (actual.isEmpty()) WaypointPushJob.NothingToDo else WaypointPushJob.Clear
    }
    return if (desired.map { it.id }.toSet() == actual.map { it.id }.toSet()) {
        WaypointPushJob.NothingToDo
    } else {
        WaypointPushJob.Rebuild(desired)
    }
}

/**
 * One course's own line out of the shared courses GeoJSON document — see
 * [CacheKeys.COURSE_GEOJSON]. Each feature carries its track's id at the
 * top level, not inside `properties` (mirrors `_feature()` on the server).
 *
 * `[lng, lat]` pairs only: the server strips elevation from this particular
 * document before it ever reaches the phone (it is not the profile), so a
 * course built from it carries no per-point altitude — see
 * `com.tracks.device.garmin.CourseFitEncoder`.
 *
 * Null when the document cannot be parsed, or names no feature with this id
 * — an offline push has nothing to fall back to and should say so rather
 * than guess at a line.
 */
fun courseCoordinatesFromGeoJson(geoJson: String, courseId: Int): List<List<Double>>? {
    val root = runCatching { TracksJson.parseToJsonElement(geoJson) }.getOrNull()
        ?.jsonObject ?: return null
    val features = root["features"]?.jsonArray ?: return null
    for (feature in features) {
        val obj = feature.jsonObject
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
        if (id != courseId) continue
        val coordinates = obj["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        return coordinates.mapNotNull { point ->
            val ordinates = point.jsonArray
            if (ordinates.size < 2) return@mapNotNull null
            val lng = ordinates[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val lat = ordinates[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            listOf(lng, lat)
        }
    }
    return null
}
