// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The colour conversions, which are the part of the picker that can be wrong
 * silently: a hex field and a spectrum that disagree by a shade still look like
 * a working control, and the track on the map ends up a colour nobody chose.
 */
class ColorMathTest {

    private fun roundTrip(hex: String) {
        assertEquals(hex.uppercase(), hsvToHex(hexToHsv(hex)), "round trip of $hex")
    }

    @Test
    fun `every preset survives a round trip`() {
        TRACK_COLORS.forEach { roundTrip(it) }
    }

    @Test
    fun `the primaries and the greys survive too`() {
        listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF", "#000000", "#808080")
            .forEach { roundTrip(it) }
    }

    @Test
    fun `hue lands where it should`() {
        assertTrue(abs(hexToHsv("#FF0000").hue - 0f) < 0.5f)
        assertTrue(abs(hexToHsv("#00FF00").hue - 120f) < 0.5f)
        assertTrue(abs(hexToHsv("#0000FF").hue - 240f) < 0.5f)
    }

    @Test
    fun `black and white are the degenerate cases`() {
        val black = hexToHsv("#000000")
        assertEquals(0f, black.value)
        assertEquals(0f, black.saturation)
        val white = hexToHsv("#FFFFFF")
        assertEquals(1f, white.value)
        assertEquals(0f, white.saturation)
    }

    @Test
    fun `a hex with no hash is still a hex`() {
        assertEquals("#2563EB", normaliseHex("2563eb"))
        assertEquals("#2563EB", normaliseHex("  #2563EB "))
    }

    @Test
    fun `half typed input is not a colour`() {
        // The text field calls this on every keystroke; anything that is not a
        // complete colour has to leave the spectrum alone rather than move it.
        listOf(null, "", "#", "#12", "#12345", "#1234567", "#12345g", "rebeccapurple")
            .forEach { assertNull(normaliseHex(it), "accepted \"$it\"") }
    }

    @Test
    fun `garbage falls back rather than throwing`() {
        // hexToHsv is fed straight from stored data, which a bad import could
        // have put anything into.
        assertEquals(hexToHsv("#2563EB"), hexToHsv("not a colour"))
    }
}

/**
 * The recents list. Robolectric, because SharedPreferences is the storage and
 * the ordering rules are the whole point of the object.
 */
// Robolectric for a real SharedPreferences and a real org.json, and
// explicitly not the real TracksApplication — its onCreate schedules
// WorkManager, which has no business booting for a colour conversion.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RecentColorsTest {

    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun `starts empty`() {
        assertEquals(emptyList(), RecentColors.read(context))
    }

    @Test
    fun `newest first`() {
        RecentColors.remember(context, "#111111")
        RecentColors.remember(context, "#222222")
        assertEquals(listOf("#222222", "#111111"), RecentColors.read(context))
    }

    @Test
    fun `re-picking an old colour moves it to the front rather than duplicating`() {
        RecentColors.remember(context, "#111111")
        RecentColors.remember(context, "#222222")
        RecentColors.remember(context, "#111111")
        assertEquals(listOf("#111111", "#222222"), RecentColors.read(context))
    }

    @Test
    fun `caps at the limit`() {
        repeat(RecentColors.LIMIT + 5) { RecentColors.remember(context, "#%06X".format(it)) }
        assertEquals(RecentColors.LIMIT, RecentColors.read(context).size)
    }

    @Test
    fun `refuses to store something that is not a colour`() {
        RecentColors.remember(context, "#123456")
        RecentColors.remember(context, "nope")
        assertEquals(listOf("#123456"), RecentColors.read(context))
    }
}

/**
 * Restyling the drawn line. The failure this guards against is not a wrong
 * colour — it is a malformed document, which blanks every track on the map.
 */
// Robolectric for a real SharedPreferences and a real org.json, and
// explicitly not the real TracksApplication — its onCreate schedules
// WorkManager, which has no business booting for a colour conversion.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RestyleCourseTest {

    private val collection = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":7,"properties":{"id":7,"name":"Ridge","color":"#2563EB"},
           "geometry":{"type":"LineString","coordinates":[[1.0,2.0],[3.0,4.0]]}},
          {"type":"Feature","id":9,"properties":{"id":9,"name":"Valley","color":"#16A34A"},
           "geometry":{"type":"LineString","coordinates":[[5.0,6.0],[7.0,8.0]]}}
        ]}
    """.trimIndent()

    private fun colorOf(json: String?, id: Int): String? {
        val features = org.json.JSONObject(json!!).getJSONArray("features")
        for (i in 0 until features.length()) {
            val properties = features.getJSONObject(i).getJSONObject("properties")
            if (properties.getInt("id") == id) return properties.getString("color")
        }
        return null
    }

    @Test
    fun `recolours the track that was asked for`() {
        val out = restyleCourse(collection, courseId = 7, color = "#DC2626")
        assertEquals("#DC2626", colorOf(out, 7))
    }

    @Test
    fun `leaves the others alone`() {
        val out = restyleCourse(collection, courseId = 7, color = "#DC2626")
        assertEquals("#16A34A", colorOf(out, 9))
    }

    @Test
    fun `keeps the geometry`() {
        val out = restyleCourse(collection, courseId = 7, color = "#DC2626")
        val features = org.json.JSONObject(out!!).getJSONArray("features")
        val line = features.getJSONObject(0).getJSONObject("geometry")
        assertEquals(2, line.getJSONArray("coordinates").length())
    }

    @Test
    fun `renames too`() {
        val out = restyleCourse(collection, courseId = 9, name = "Lower valley")
        val features = org.json.JSONObject(out!!).getJSONArray("features")
        assertEquals(
            "Lower valley",
            features.getJSONObject(1).getJSONObject("properties").getString("name"),
        )
    }

    @Test
    fun `a track the document does not draw changes nothing`() {
        assertEquals(collection, restyleCourse(collection, courseId = 404, color = "#000000"))
    }

    @Test
    fun `a patch with neither colour nor name changes nothing`() {
        assertEquals(collection, restyleCourse(collection, courseId = 7))
    }

    @Test
    fun `nothing drawn yet stays nothing`() {
        assertEquals(null, restyleCourse(null, courseId = 7, color = "#000000"))
        assertEquals("", restyleCourse("", courseId = 7, color = "#000000"))
    }

    @Test
    fun `unreadable text is handed back rather than replaced with a broken map`() {
        assertEquals("{oh dear", restyleCourse("{oh dear", courseId = 7, color = "#000000"))
    }
}
