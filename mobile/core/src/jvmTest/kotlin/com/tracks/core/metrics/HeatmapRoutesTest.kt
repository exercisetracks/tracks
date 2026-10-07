// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.api.TracksJson
import com.tracks.core.local.ImportThresholds
import com.tracks.core.local.LocalImporter
import com.tracks.core.local.LocalLibrary
import com.tracks.core.parse.Canonical
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dashboard heatmap on the phone: which routes it draws, and the colours
 * it draws them in. The colours are the web glow layer's
 * (`HeatmapGlowLayer.buildHeatmapVerts`), so one ride reads the same on both.
 */
class HeatmapRoutesTest {

    private fun features(json: String) =
        TracksJson.parseToJsonElement(json).jsonObject["features"]!!.jsonArray.map { it.jsonObject }

    private fun colors(json: String) =
        features(json).map { it["properties"]!!.jsonObject["color"]!!.jsonPrimitive.content }

    /** A straight line north, one point every ~111 m. */
    private fun track(n: Int, value: (Int) -> HeatPoint.(Int) -> HeatPoint = { { this } }) =
        List(n) { i -> HeatPoint(45.0 + i * 0.001, 7.0).let { p -> value(i)(p, i) } }

    @Test
    fun frequency_is_one_orange_line_per_track() {
        val json = HeatmapRoutes.geoJson(listOf(track(5), track(3)), HeatmapMode.Frequency)
        assertEquals(listOf(HeatmapRoutes.FREQUENCY_COLOR, HeatmapRoutes.FREQUENCY_COLOR), colors(json))
    }

    @Test
    fun the_fastest_stretch_is_red_and_the_slowest_blue() {
        // Speed rises steadily along the track, so the ramp runs end to end.
        val t = track(40) { i -> { copy(speed = i.toDouble()) } }
        val c = colors(HeatmapRoutes.geoJson(listOf(t), HeatmapMode.Pace))
        assertEquals(HeatmapRoutes.colorOf(HeatmapMode.Pace, 0), c.first())
        assertEquals("#ef4444", c.last())
        assertEquals("#2962ff", c.first())
    }

    @Test
    fun runs_of_one_colour_are_one_line_and_still_join_up() {
        // Two flat halves: two features, sharing the point where they meet,
        // rather than one per segment.
        val t = track(10) { i -> { copy(heartRate = if (i < 5) 120.0 else 170.0) } }
        val f = features(HeatmapRoutes.geoJson(listOf(t), HeatmapMode.HeartRate))
        assertTrue(f.size in 2..3, "expected a line per colour run, got ${f.size}")
        val coords = f.map { it["geometry"]!!.jsonObject["coordinates"]!!.jsonArray }
        for (i in 1 until coords.size) assertEquals(coords[i - 1].last(), coords[i].first())
    }

    @Test
    fun a_climb_is_purple_a_descent_green() {
        // 111 m apart and 30 m up per step: well past the 20 % clamp.
        val up = track(4) { i -> { copy(altitude = i * 30.0) } }
        val down = track(4) { i -> { copy(altitude = 1000 - i * 30.0) } }
        assertEquals(listOf("#8b5cf6"), colors(HeatmapRoutes.geoJson(listOf(up), HeatmapMode.Gradient)))
        assertEquals(listOf("#10b981"), colors(HeatmapRoutes.geoJson(listOf(down), HeatmapMode.Gradient)))
    }

    @Test
    fun points_without_the_value_are_not_drawn() {
        // The server's heatmap query drops them; guessing a colour for a
        // stretch with no heart rate would invent data.
        val t = track(6) { i -> { copy(heartRate = if (i < 3) null else 150.0) } }
        val f = features(HeatmapRoutes.geoJson(listOf(t), HeatmapMode.HeartRate))
        assertEquals(3, f.sumOf { it["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.size } - (f.size - 1))
    }

    // ── Which routes ─────────────────────────────────────────────────────────

    private fun library(): LocalLibrary = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val lib = LocalLibrary(driver)
        val dir = File(Canonical.root, "spec/fixtures/fit")
        for (name in listOf("activity_ride.fit", "activity_run.fit", "activity_climb.fit")) {
            LocalImporter(lib, { ImportThresholds(null, 165.0) }).import(name, File(dir, name).readBytes())
        }
        lib
    }

    @Test
    fun the_window_drops_activities_started_before_it() = runBlocking {
        // The bug: the phone's card drew every route ever recorded whatever
        // the dashboard's period said.
        val lib = library()
        val all = lib.heatmapTracks(null, null, HeatmapMode.Frequency)
        assertTrue(all.isNotEmpty(), "the fixtures have GPS")

        val days = lib.activityRows().mapNotNull { it.started_at?.take(10) }.sorted()
        assertEquals(all.size, lib.heatmapTracks(null, days.first(), HeatmapMode.Frequency).size)
        val dayAfterLast = LocalDate.parse(days.last()).plusDays(1).toString()
        assertEquals(0, lib.heatmapTracks(null, dayAfterLast, HeatmapMode.Frequency).size)
    }

    @Test
    fun the_sport_filter_limits_it_to_those_activities() = runBlocking {
        val lib = library()
        val one = lib.activityRows().first { lib.heatmapTracks(setOf(it.uid), null, HeatmapMode.Frequency).isNotEmpty() }
        assertEquals(1, lib.heatmapTracks(setOf(one.uid), null, HeatmapMode.Frequency).size)
    }

    @Test
    fun a_track_is_cut_once_and_then_read_back_the_same() = runBlocking {
        // The cache is the point: a second read must not parse the detail
        // again, and must return what the first did.
        val lib = library()
        val first = lib.heatmapTracks(null, null, HeatmapMode.HeartRate)
        val stored = lib.q.selectHeatTracks(null).executeAsList()
        assertTrue(stored.all { it.points != null }, "every activity has a packed track after one read")
        assertEquals(first, lib.heatmapTracks(null, null, HeatmapMode.HeartRate))
    }

    @Test
    fun the_packing_keeps_a_point_to_well_under_a_metre_and_its_gaps() {
        val p = listOf(HeatPoint(45.1234567, -122.7654321, 3.25, null, 1234.5), HeatPoint(-33.9, 151.2))
        val back = HeatTrackCodec.decode(HeatTrackCodec.encode(p))
        assertTrue(kotlin.math.abs(back[0].lat - p[0].lat) < 5e-6)
        assertTrue(kotlin.math.abs(back[0].lng - p[0].lng) < 1e-5)
        assertEquals(null, back[0].heartRate)
        assertEquals(3.25, back[0].speed)
        assertEquals(null, back[1].altitude)
    }
}
