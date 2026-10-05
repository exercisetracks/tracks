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
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.fieldDefinitions.FieldDefinitionWeatherReport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitWeather

/**
 * The encoder is worth testing precisely because its two hardest properties are
 * invisible: temperatures leave in Kelvin even though the wire carries Celsius,
 * and several fields are deliberately null so the record *definition* still
 * carries them. Both are silent when wrong — a watch face reading 273 degrees
 * too cold, or a watch quietly rejecting a record.
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
    fun `temperatures are encoded as kelvin`() {
        val messages = GarminWeatherEncoder.encode(report(currentC = 12))
        val current = messages.recordDataList
            .filterIsInstance<FitWeather>()
            .first { it.weatherReport == FieldDefinitionWeatherReport.Type.current }

        // 12 C is 285 K. The field definition subtracts 273 on the way to the
        // wire, so anything that looks like Celsius here is a 273-degree bug.
        assertEquals(285, current.temperature)
        assertEquals(280, current.lowTemperature)
        assertEquals(291, current.highTemperature)
    }

    @Test
    fun `a forecast survives the broadcast round trip unchanged`() {
        // What the wire actually does: a broadcast arrives in Kelvin, is parsed
        // to Celsius, and goes back out to the watch in Kelvin. Anything that
        // breaks that symmetry shifts every temperature by 273.
        val kelvin = 285
        val parsed = WeatherBroadcast.kelvinToCelsius(kelvin)
        val encoded = GarminWeatherEncoder.encode(report(currentC = parsed))
            .recordDataList
            .filterIsInstance<FitWeather>()
            .first { it.weatherReport == FieldDefinitionWeatherReport.Type.current }

        assertEquals(kelvin, encoded.temperature)
    }

    @Test
    fun `current conditions carry the definition fields the broadcast cannot fill`() {
        val messages = GarminWeatherEncoder.encode(report())
        val current = messages.recordDataList
            .filterIsInstance<FitWeather>()
            .first { it.weatherReport == FieldDefinitionWeatherReport.Type.current }

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
            .filterIsInstance<FitWeather>()
            .filter { it.weatherReport == FieldDefinitionWeatherReport.Type.daily_forecast }

        // Today plus four. A watch shows five days; sending seven wastes radio
        // time on records it will discard.
        assertEquals(5, daily.size)
    }

    @Test
    fun `an empty forecast still produces today`() {
        val messages = GarminWeatherEncoder.encode(report(forecasts = emptyList()))
        val types = messages.recordDataList.filterIsInstance<FitWeather>().map { it.weatherReport }

        assertTrue(FieldDefinitionWeatherReport.Type.current in types)
        assertTrue(FieldDefinitionWeatherReport.Type.daily_forecast in types)
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
