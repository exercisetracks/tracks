// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.weather

import com.tracks.core.fit.decode.CivilDate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's own Open-Meteo client, which replaced three server endpoints.
 *
 * The server's versions were the reference: these tests pin the same
 * reshaping — field renames, the WMO-to-OpenWeatherMap codes the watch needs,
 * local wall-clock to epoch — because each of them failed silently once
 * before. A renamed field produced a confident 0 C in August; an hourly strip
 * that kept the morning's hours opened the watch glance on the past.
 */
class OpenMeteoTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String = RESPONSE) =
        OpenMeteo(MockEngine { req ->
            requests += req
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    @Test
    fun `a point forecast keeps the provider's values under the app's names`() = runTest {
        val w = assertNotNull(client().point(46.87, -113.99))

        assertEquals(21.5, w.current?.temperatureC)
        assertEquals(19.0, w.current?.apparentC)
        assertEquals(3.2, w.current?.windMps)
        assertEquals(2, w.current?.weatherCode)
        assertEquals(listOf("2026-10-06", "2026-10-07"), w.daily.map { it.date })
        assertEquals(24.0, w.daily[0].tempMaxC)
        assertEquals(40.0, w.daily[1].precipProbPct)
    }

    @Test
    fun `only the coordinate asked about is sent, rounded, and in metric`() = runTest {
        client().point(46.871234567, -113.991234567)

        val url = requests.single().url
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("46.8712", url.parameters["latitude"])
        assertEquals("-113.9912", url.parameters["longitude"])
        assertEquals("ms", url.parameters["wind_speed_unit"])
        assertNull(url.parameters["hourly"], "the map sheet does not need hours")
    }

    @Test
    fun `a failed request is no forecast rather than an error`() = runTest {
        assertNull(client(HttpStatusCode.ServiceUnavailable, "{}").point(1.0, 2.0))
        assertNull(client(body = "not json").point(1.0, 2.0))
    }

    @Test
    fun `a day's hours are only that day's, in the point's own timezone`() = runTest {
        val day = client().hourly(46.87, -113.99, "2026-10-07", today = CivilDate(2026, 10, 6))

        assertEquals(listOf("2026-10-07T00:00", "2026-10-07T01:00"), day.hours.map { it.time })
        assertEquals("America/Denver", day.timezone)
        // Tomorrow plus a day of slack, as the server asked.
        assertEquals("3", requests.single().url.parameters["forecast_days"])
    }

    @Test
    fun `a day outside the forecast window is not asked for at all`() = runTest {
        val day = client().hourly(46.87, -113.99, "2026-12-25", today = CivilDate(2026, 10, 6))

        assertTrue(day.hours.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the watch forecast speaks the watch's codes and units`() = runTest {
        val w = assertNotNull(client().watch(46.87, -113.99, nowEpochSeconds = NOW))

        assertEquals(22, w.currentTempC)
        assertEquals("Partly cloudy", w.currentCondition)
        assertEquals(802, w.currentConditionCode)
        assertEquals(11.5f, w.windSpeedKmh) // 3.2 m/s
        assertEquals("46.87°N 113.99°W", w.location)
        assertEquals(46.87, w.lat)
        // Today is the "current" block; the rows are the days after.
        assertEquals(1, w.forecasts.size)
        assertEquals(500, w.forecasts[0].conditionCode)
    }

    @Test
    fun `the watch's hours start at the current hour and are real instants`() = runTest {
        val w = assertNotNull(client().watch(46.87, -113.99, nowEpochSeconds = NOW))

        // 2026-10-06T12:00 local at UTC-6 is 18:00 UTC. The 10:00 hour is more
        // than an hour gone and must not open the strip.
        val noonLocal = 1791309600L // 2026-10-06T18:00:00Z
        assertEquals(noonLocal, w.hourly.first().timestamp)
        assertTrue(w.hourly.all { it.timestamp >= NOW - 3600 })
    }

    @Test
    fun `a forecast with no current temperature is refused, not sent as zero`() = runTest {
        val body = RESPONSE.replace("\"temperature_2m\": 21.5", "\"temperature_2m\": null")
        assertNull(client(body = body).watch(46.87, -113.99, NOW))
    }

    private companion object {
        /** 2026-10-06T18:30:00Z — half past noon at the point. */
        const val NOW = 1791311400L

        val RESPONSE = """
            {
              "timezone": "America/Denver",
              "utc_offset_seconds": -21600,
              "current": {
                "temperature_2m": 21.5, "apparent_temperature": 19.0,
                "relative_humidity_2m": 35, "wind_speed_10m": 3.2,
                "wind_direction_10m": 270, "weather_code": 2, "is_day": 1
              },
              "daily": {
                "time": ["2026-10-06", "2026-10-07"],
                "weather_code": [2, 61],
                "temperature_2m_max": [24.0, 18.0],
                "temperature_2m_min": [8.0, 6.0],
                "precipitation_sum": [0.0, 4.2],
                "precipitation_probability_max": [5, 40],
                "wind_speed_10m_max": [5.0, 7.5]
              },
              "hourly": {
                "time": ["2026-10-06T10:00", "2026-10-06T12:00", "2026-10-06T13:00",
                         "2026-10-07T00:00", "2026-10-07T01:00"],
                "temperature_2m": [15.0, 21.0, 22.0, 9.0, 8.5],
                "weather_code": [0, 2, 3, 61, 61],
                "precipitation_probability": [0, 5, 10, 40, 45],
                "relative_humidity_2m": [50, 35, 33, 80, 82]
              }
            }
        """.trimIndent()
    }
}
