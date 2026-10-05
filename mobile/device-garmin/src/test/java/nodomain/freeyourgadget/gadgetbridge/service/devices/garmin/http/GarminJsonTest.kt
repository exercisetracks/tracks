// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The codec the watch's proxy speaks. A round trip through encode/decode is
 * the cheapest proof the port kept the wire format; the byte-level check pins
 * the one thing a round trip cannot — that the string table and the magic
 * numbers are where the watch expects them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GarminJsonTest {

    @Test
    fun `an object round-trips`() {
        val original = JSONObject()
            .put("url", "https://music.example.com")
            .put("user", "alex")
            .put("n", 42)
            .put("big", 5_000_000_000L)
            .put("ratio", 0.5)
            .put("ok", true)
            .put("nothing", JSONObject.NULL)
            .put("playlists", JSONArray(listOf("~starred", "abc")))
            .put("nested", JSONObject().put("k", "v"))

        val decoded = GarminJson.decode(GarminJson.encode(original)) as JSONObject

        assertEquals("https://music.example.com", decoded.getString("url"))
        assertEquals("alex", decoded.getString("user"))
        assertEquals(42, decoded.getInt("n"))
        assertEquals(5_000_000_000L, decoded.getLong("big"))
        assertEquals(0.5, decoded.getDouble("ratio"), 1e-9)
        assertTrue(decoded.getBoolean("ok"))
        assertTrue(decoded.isNull("nothing"))
        assertEquals("abc", decoded.getJSONArray("playlists").getString(1))
        assertEquals("v", decoded.getJSONObject("nested").getString("k"))
    }

    @Test
    fun `a top-level array round-trips`() {
        // Navidrome's native API answers list requests with a bare array.
        val original = JSONArray().put(JSONObject().put("id", "a").put("name", "One"))
            .put(JSONObject().put("id", "b").put("name", "Two"))

        val decoded = GarminJson.decode(GarminJson.encode(original)) as JSONArray

        assertEquals(2, decoded.length())
        assertEquals("Two", decoded.getJSONObject(1).getString("name"))
    }

    @Test
    fun `strings are written once and referenced`() {
        val bytes = GarminJson.encode(JSONObject().put("a", "x").put("b", "x"))

        // Magic ab cd ab cd, then a 32-bit table length. Three distinct
        // strings ("a", "b", "x"), each a 2-byte length, one byte, and a
        // terminator — a second copy of "x" would make it four.
        assertEquals(0xab.toByte(), bytes[0])
        assertEquals(0xcd.toByte(), bytes[1])
        val tableLength = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        assertEquals(3 * (2 + 1 + 1), tableLength)
    }
}
