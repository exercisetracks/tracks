// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.CourseCreate
import com.tracks.core.api.CourseDetail
import com.tracks.core.api.CourseSummary
import com.tracks.core.api.CourseUpdate
import com.tracks.core.api.TracksJson
import com.tracks.core.api.Waypoint
import com.tracks.core.api.WaypointIn
import com.tracks.core.api.WaypointUpdate
import com.tracks.core.parse.PyMath
import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Saved tracks and places — the map's sources — read and written on the phone.
 *
 * The server shapes these through `routes/courses_api/helpers.py`
 * (`_summary`, `_detail`, `_feature`, `_device_status`); this reproduces that
 * shaping over replica rows so the map screens need no change of model.
 */
class LocalMapItems(private val sources: LocalSources) {

    // ── Tracks ──────────────────────────────────────────────────────────────

    suspend fun courses(): List<CourseSummary> =
        sources.replica.rows("track").mapNotNull { summary(it) }.sortedBy { it.name.lowercase() }

    suspend fun course(id: Int): CourseDetail? {
        val row = sources.row("track", id) ?: return null
        return decodeWith(row, CourseDetail.serializer())
    }

    /**
     * Save a track drawn on the phone. The stats the server would compute on
     * create (`course_fit.compute_stats`) are computed here, so the row is
     * whole the moment it exists and syncs as such. Two differences, both
     * because they need the server's elevation model: a line drawn without
     * elevation is not filled from the DEM, and it gets no elevation profile.
     */
    suspend fun createCourse(course: CourseCreate): CourseSummary? {
        val stats = TrackStats.compute(course.coords) ?: return null
        val id = sources.createValues("track", mapOf(
            "name" to (course.name?.trim()?.take(MAX_NAME)?.ifEmpty { null } ?: "Custom Track"),
            "color" to (course.color ?: "#3b82f6"),
            "sport" to (course.sport ?: "hiking").lowercase(),
            "source" to (course.source ?: "builder"),
            "load_to_device" to course.loadToDevice,
            "hidden" to false,
            "is_external" to false,
            "geometry" to stats.coords,
            "distance_m" to stats.distanceM,
            "ascent_m" to stats.ascentM,
            "descent_m" to stats.descentM,
            "bounds" to stats.bounds,
        ))
        return sources.row("track", id)?.let { summary(it) }
    }

    suspend fun updateCourse(id: Int, update: CourseUpdate): CourseSummary? {
        sources.edit("track", id, update, CourseUpdate.serializer())
        return sources.row("track", id)?.let { summary(it) }
    }

    suspend fun deleteCourse(id: Int) = sources.delete("track", id)

    /** Record that a Bluetooth push put this track on the watch. */
    suspend fun markCourseUploaded(id: Int, filename: String, atIso: String) =
        sources.setValues("track", id, mapOf("watch_filename" to filename, "watch_uploaded_at" to atIso, "watch_deleted_at" to null))

    /** `/courses/geojson`: every track's line, for drawing them all at once. */
    suspend fun coursesGeoJson(): String {
        val features = sources.replica.rows("track").mapNotNull { r ->
            val coords = r.fields["geometry"] as? JsonArray ?: return@mapNotNull null
            val id = sources.idOf(r.uid)
            JsonObject(mapOf(
                "type" to JsonPrimitive("Feature"),
                "id" to JsonPrimitive(id),
                "properties" to JsonObject(mapOf(
                    "id" to JsonPrimitive(id),
                    "name" to (r.fields["name"] ?: JsonNull),
                    "color" to (r.fields["color"] ?: JsonNull),
                    "on_device" to JsonPrimitive(onDevice(r)),
                    "turn_by_turn" to (r.fields["turn_by_turn"] ?: JsonPrimitive(false)),
                    "is_external" to (r.fields["is_external"] ?: JsonPrimitive(false)),
                )),
                "geometry" to JsonObject(mapOf(
                    "type" to JsonPrimitive("LineString"),
                    "coordinates" to JsonArray(coords.mapNotNull { c ->
                        val a = c as? JsonArray ?: return@mapNotNull null
                        if (a.size < 2) null else JsonArray(listOf(a[0], a[1]))
                    }),
                )),
            ))
        }
        return JsonObject(mapOf("type" to JsonPrimitive("FeatureCollection"), "features" to JsonArray(features))).toString()
    }

    private fun summary(row: SyncedRow): CourseSummary? = decodeWith(row, CourseSummary.serializer())

    private fun <T> decodeWith(row: SyncedRow, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val json = LinkedHashMap<String, JsonElement>(sources.modelJson(row))
        json["device_status"] = JsonPrimitive(deviceStatus(row))
        for ((k, d) in listOf("distance_m" to 0.0, "ascent_m" to 0.0, "descent_m" to 0.0)) {
            if (json[k] == null || json[k] is JsonNull) json[k] = JsonPrimitive(d)
        }
        return runCatching { TracksJson.decodeFromJsonElement(serializer, JsonObject(json)) }.getOrNull()
    }

    // ── Places ──────────────────────────────────────────────────────────────

    suspend fun waypoints(): List<Waypoint> = sources.replica.rows("waypoint").mapNotNull { r ->
        val json = LinkedHashMap<String, JsonElement>(sources.modelJson(r))
        json["on_watch"] = JsonPrimitive(onDevice(r))
        runCatching { TracksJson.decodeFromJsonElement(Waypoint.serializer(), JsonObject(json)) }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    suspend fun createWaypoint(waypoint: WaypointIn): Waypoint? {
        val id = sources.create("waypoint", waypoint, WaypointIn.serializer())
        return waypoints().firstOrNull { it.id == id }
    }

    suspend fun updateWaypoint(id: Int, update: WaypointUpdate): Waypoint? {
        sources.edit("waypoint", id, update, WaypointUpdate.serializer())
        return waypoints().firstOrNull { it.id == id }
    }

    suspend fun deleteWaypoint(id: Int) = sources.delete("waypoint", id)

    /** Record that a Bluetooth push put these places on the watch (as one Locations file). */
    suspend fun markWaypointsUploaded(ids: List<Int>, filename: String, atIso: String) {
        for (id in ids) {
            sources.setValues("waypoint", id, mapOf("watch_filename" to filename, "watch_uploaded_at" to atIso, "watch_deleted_at" to null))
        }
    }

    // ── Shared ──────────────────────────────────────────────────────────────

    private fun onDevice(r: SyncedRow): Boolean = r.str("watch_uploaded_at") != null && r.str("watch_deleted_at") == null

    /** `_device_status`. */
    private fun deviceStatus(r: SyncedRow): String {
        fun bool(k: String) = (r.fields[k] as? JsonPrimitive)?.content == "true"
        return when {
            bool("is_external") -> if (bool("purge_after_delete")) "pending_remove" else "external"
            onDevice(r) -> if (!bool("load_to_device")) "pending_remove" else "on_device"
            bool("load_to_device") -> "pending_upload"
            else -> "off"
        }
    }

    private companion object {
        const val MAX_NAME = 100
    }
}

/**
 * `course_fit.compute_stats` without the server's elevation model: distance by
 * the same flat-earth approximation, gain and loss over whatever elevations
 * the line carries, rounding as Python rounds.
 */
object TrackStats {
    data class Stats(
        val coords: List<List<Double?>>,
        val distanceM: Double,
        val ascentM: Double,
        val descentM: Double,
        val bounds: List<Double>,
    )

    fun compute(input: List<List<Double>>): Stats? {
        val coords = input.filter { it.size >= 2 }
        if (coords.size < 2) return null
        var dist = 0.0
        for (i in 1 until coords.size) dist += metres(coords[i - 1], coords[i])
        var gain = 0.0
        var loss = 0.0
        var prev: Double? = null
        for (c in coords) {
            val e = c.getOrNull(2) ?: continue
            if (prev != null) {
                val d = e - prev
                if (d > 0) gain += d else loss += -d
            }
            prev = e
        }
        return Stats(
            coords = coords.map { c -> listOf(PyMath.round(c[0], 6), PyMath.round(c[1], 6), c.getOrNull(2)?.let { PyMath.round(it, 1) }) },
            distanceM = PyMath.round(dist, 1),
            ascentM = PyMath.round(gain, 1),
            descentM = PyMath.round(loss, 1),
            bounds = listOf(coords.minOf { it[0] }, coords.minOf { it[1] }, coords.maxOf { it[0] }, coords.maxOf { it[1] }),
        )
    }

    // `math.radians` multiplies by one precomputed constant; `x * PI / 180`
    // rounds twice and can differ in the last bit.
    private const val DEG = PI / 180.0

    /** `_haversine_m` — despite the name, an equirectangular approximation. */
    private fun metres(a: List<Double>, b: List<Double>): Double {
        val dx = (b[0] - a[0]) * cos(((a[1] + b[1]) / 2) * DEG) * 111320.0
        val dy = (b[1] - a[1]) * 110540.0
        return hypot(dx, dy)
    }
}

