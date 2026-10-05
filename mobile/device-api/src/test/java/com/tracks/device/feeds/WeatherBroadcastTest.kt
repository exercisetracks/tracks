// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.content.Intent
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing the weather broadcast that Breezy Weather and friends already send.
 *
 * Robolectric because `Intent` and `JSONObject` are Android classes with no
 * usable stubs on a plain JVM — `org.json` on the desktop JDK behaves
 * differently from Android's, and the difference is exactly in the
 * optional-field handling this parser leans on.
 */
@RunWith(RobolectricTestRunner::class)
class WeatherBroadcastTest {

    private fun intent(action: String, json: String, secondary: String? = null) =
        Intent(action).apply {
            putExtra(WeatherBroadcast.EXTRA_WEATHER_JSON, json)
            secondary?.let { putExtra(WeatherBroadcast.EXTRA_WEATHER_SECONDARY_JSON, it) }
        }

    /** Shaped like what Breezy Weather actually sends. Temperatures in Kelvin. */
    private val breezyPayload = """
        {"timestamp":1786665600,"location":"Harbor","currentTemp":295,
         "todayMinTemp":288,"todayMaxTemp":301,"currentCondition":"Clear",
         "currentConditionCode":800,"windSpeed":12.5,"windDirection":270,
         "currentHumidity":41,
         "forecasts":[{"minTemp":289,"maxTemp":303,"conditionCode":801}]}
    """.trimIndent()

    @Test
    fun `parses the payload a weather app sends`() {
        val reports = WeatherBroadcast.parse(
            intent(WeatherBroadcast.ACTION_GENERIC_WEATHER, breezyPayload),
        )
        assertEquals(1, reports.size)
        val w = reports.single()
        assertEquals("Harbor", w.location)
        assertEquals(800, w.currentConditionCode)
        assertEquals("Clear", w.currentCondition)
        assertEquals(12.5f, w.windSpeedKmh)
        assertEquals(270, w.windDirectionDegrees)
        assertEquals(41, w.humidityPercent)
    }

    @Test
    fun `converts Kelvin to Celsius`() {
        // The wire format is Kelvin, which is not obvious and is a 273-degree
        // mistake waiting to happen.
        val w = WeatherBroadcast.parse(
            intent(WeatherBroadcast.ACTION_GENERIC_WEATHER, breezyPayload),
        ).single()
        assertEquals(22, w.currentTempC)     // 295 K
        assertEquals(15, w.todayMinTempC)    // 288 K
        assertEquals(28, w.todayMaxTempC)    // 301 K
    }

    @Test
    fun `treats zero as absent rather than minus 273`() {
        // Zero is the default both this parser and the senders use for "not
        // supplied". Converting it honestly would put -273 on a watch face.
        assertEquals(0, WeatherBroadcast.kelvinToCelsius(0))
        assertEquals(-1, WeatherBroadcast.kelvinToCelsius(272))
    }

    @Test
    fun `parses the daily forecast`() {
        val w = WeatherBroadcast.parse(
            intent(WeatherBroadcast.ACTION_GENERIC_WEATHER, breezyPayload),
        ).single()
        assertEquals(1, w.forecasts.size)
        assertEquals(16, w.forecasts[0].minTempC)
        assertEquals(30, w.forecasts[0].maxTempC)
        assertEquals(801, w.forecasts[0].conditionCode)
    }

    @Test
    fun `reads secondary locations after the primary`() {
        val reports = WeatherBroadcast.parse(
            intent(
                WeatherBroadcast.ACTION_GENERIC_WEATHER,
                breezyPayload,
                secondary = """[{"location":"Seaside","currentTemp":293,"currentConditionCode":802}]""",
            ),
        )
        assertEquals(listOf("Harbor", "Seaside"), reports.map { it.location })
        assertEquals(20, reports[1].currentTempC)
    }

    @Test
    fun `accepts our own action too`() {
        // So a sender that can be pointed at Tracks directly has somewhere to
        // aim, rather than only the Gadgetbridge-shaped action.
        val reports = WeatherBroadcast.parse(
            intent(WeatherBroadcast.ACTION_TRACKS_WEATHER, breezyPayload),
        )
        assertEquals(1, reports.size)
    }

    @Test
    fun `ignores actions that are not weather`() {
        assertTrue(WeatherBroadcast.parse(intent("android.intent.action.VIEW", breezyPayload)).isEmpty())
        assertTrue(!WeatherBroadcast.handles(null))
    }

    @Test
    fun `malformed json costs a stale forecast, not a crash`() {
        // This runs in a background receiver. Throwing here would take down a
        // process the user never opened.
        assertTrue(WeatherBroadcast.parse(intent(WeatherBroadcast.ACTION_GENERIC_WEATHER, "not json")).isEmpty())
        assertTrue(WeatherBroadcast.parse(Intent(WeatherBroadcast.ACTION_GENERIC_WEATHER)).isEmpty())
    }

    @Test
    fun `missing optional fields are null, not zero`() {
        // A sender that omits wind should not have the watch display a calm day.
        val w = WeatherBroadcast.fromJson(
            JSONObject("""{"location":"X","currentTemp":295,"currentConditionCode":800}"""),
        )
        assertNull(w.windSpeedKmh)
        assertNull(w.windDirectionDegrees)
        assertNull(w.humidityPercent)
        assertTrue(w.forecasts.isEmpty())
    }
}
