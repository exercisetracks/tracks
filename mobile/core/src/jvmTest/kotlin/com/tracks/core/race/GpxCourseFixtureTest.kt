// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.spec.SpecFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A course imported on the phone is the course the server would have stored:
 * the same ~100 m segments (which drive the grade-adjusted prediction and the
 * technicality factor) and the same sampled path (the map and profile).
 * Without this, a GPX file could only be imported with a server linked.
 */
class GpxCourseFixtureTest {
    private val cases = SpecFixtures.load("gpx")["cases"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun segments_match_the_servers_parse() {
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val want = c["segments"]!!.jsonArray.map {
                val o = it.jsonObject
                Triple(o.d("distance_m"), o.d("elevation_gain_m"), o.d("gradient"))
            }
            val got = GpxCourse.segments(c["gpx"]!!.jsonPrimitive.content)
                .map { Triple(it.distanceM, it.elevationGainM, it.gradient) }
            assertEquals(want, got, name)
        }
    }

    @Test
    fun the_sampled_path_matches_the_servers() {
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val want = c["path"]!!.jsonArray.map { p ->
                val a = p as JsonArray
                GpxCourse.Point(a[0].jsonPrimitive.doubleOrNull!!, a[1].jsonPrimitive.doubleOrNull!!,
                    (a[2] as? JsonNull)?.let { null } ?: a[2].jsonPrimitive.doubleOrNull)
            }
            assertEquals(want, GpxCourse.path(c["gpx"]!!.jsonPrimitive.content), name)
        }
    }

    private fun JsonObject.d(k: String): Double = this[k]!!.jsonPrimitive.doubleOrNull!!
}
