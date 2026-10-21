// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.DailyForecast
import com.tracks.device.WeatherReport
import com.tracks.device.feeds.WeatherBroadcast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.WeatherReport as FitWeatherReport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitWeatherConditions

/**
 * The encoder is worth testing precisely because its two hardest properties are
 * invisible: temperatures are Celsius on the wire, which a broadcast's Kelvin
 * has to be turned back into, and several fields are deliberately null so the
 * record *definition* still carries them. Both are silent when wrong — a
 * watch face 273 degrees off, or a watch quietly rejecting a record.
 */
class GarminWeatherEncoderTest {

    private fun report(
        currentC: Int = 12,
        minC: Int = 7,
        maxC: Int = 18,
        forecasts: List<DailyForecast> = emptyList(),
    ) = WeatherReport(
        location = "Seaside",
        timestamp = 1_760_000_000L,
        currentTempC = currentC,
        todayMinTempC = minC,
        todayMaxTempC = maxC,
        currentCondition = "Clear",
        currentConditionCode = 800,
        windSpeedKmh = 11.5f,
        windDirectionDegrees = 270,
        humidityPercent = 42,
        forecasts = forecasts,
    )

    @Test
    fun `temperatures go to the wire in celsius`() {
        val messages = GarminWeatherEncoder.encode(report(currentC = 12))
        val current = messages.recordDataList
            .filterIsInstance<FitWeatherConditions>()
            .first { it.weatherReport == FitWeatherReport.current }

        // Celsius as it is (the field is signed 8-bit Celsius since
        // Gadgetbridge 0.94); a Kelvin value here is a 273-degree bug.
        assertEquals(12, current.temperature)
        assertEquals(7, current.lowTemperature)
        assertEquals(18, current.highTemperature)
    }

    @Test
    fun `a forecast survives the broadcast round trip unchanged`() {
        // What the wire actually does: a broadcast arrives in Kelvin, is parsed
        // to Celsius, and goes to the watch as Kelvin minus 273, the same
        // integer conversion Gadgetbridge makes (its #4313). Anything that
        // breaks that shifts every temperature by 273.
        val kelvin = 285
        val parsed = WeatherBroadcast.kelvinToCelsius(kelvin)
        val encoded = GarminWeatherEncoder.encode(report(currentC = parsed))
            .recordDataList
            .filterIsInstance<FitWeatherConditions>()
            .first { it.weatherReport == FitWeatherReport.current }

        assertEquals(kelvin - 273, encoded.temperature)
    }

    @Test
    fun `current conditions carry the definition fields the broadcast cannot fill`() {
        val messages = GarminWeatherEncoder.encode(report())
        val current = messages.recordDataList
            .filterIsInstance<FitWeatherConditions>()
            .first { it.weatherReport == FitWeatherReport.current }

        // Present in the definition, empty in the data. getFieldByNumber
        // returning null is the correct outcome; the point is that encoding
        // did not throw and the record was still built.
        assertEquals(null, current.dewPoint)
        assertEquals(null, current.precipitationProbability)
        assertEquals("Seaside", current.location)
        assertEquals(270, current.windDirection)
        assertEquals(42, current.relativeHumidity)
    }

    @Test
    fun `daily forecasts are capped at four days beyond today`() {
        val week = (1..7).map { DailyForecast(minTempC = it, maxTempC = it + 10, conditionCode = 800) }
        val messages = GarminWeatherEncoder.encode(report(forecasts = week))

        val daily = messages.recordDataList
            .filterIsInstance<FitWeatherConditions>()
            .filter { it.weatherReport == FitWeatherReport.daily_forecast }

        // Today plus four. A watch shows five days; sending seven wastes radio
        // time on records it will discard.
        assertEquals(5, daily.size)
    }

    @Test
    fun `an empty forecast still produces today`() {
        val messages = GarminWeatherEncoder.encode(report(forecasts = emptyList()))
        val types = messages.recordDataList.filterIsInstance<FitWeatherConditions>().map { it.weatherReport }

        assertTrue(FitWeatherReport.current in types)
        assertTrue(FitWeatherReport.daily_forecast in types)
        assertNotNull(messages.definitions.firstOrNull())
    }

    @Test
    fun `current and daily records use different local message types`() {
        // They have different field sets, so sharing a local type would mean
        // the watch decoding one against the other's definition.
        val messages = GarminWeatherEncoder.encode(
            report(forecasts = listOf(DailyForecast(1, 11, 800))),
        )
        assertEquals(2, messages.definitions.size)
    }
}
