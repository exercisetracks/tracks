// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.HourlyForecast
import com.tracks.device.WeatherReport
import com.tracks.device.feeds.WeatherBroadcast
import java.time.Instant
import java.time.ZoneId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitLocalMessageBuilder
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.fieldDefinitions.FieldDefinitionWeatherStatus
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.WeatherReport as FitWeatherReport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.AbstractFitWeatherConditions
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitWeatherConditions

/**
 * Turns a [WeatherReport] into the FIT records a Garmin watch expects.
 *
 * ## Why this lives on the Tracks side
 *
 * It translates Tracks' own weather model, and the vendored `GarminSupport` may
 * not know that model exists — the boundary only runs one way. `GarminSupport`
 * takes the finished [FitLocalMessageBuilder] and owns the protocol exchange
 * that gets it to the watch.
 *
 * ## The two things that are easy to get wrong
 *
 * **Temperatures go in as Celsius**, and the wire carries Celsius: the FIT
 * profile declares `weather_conditions.temperature` as signed 8-bit Celsius.
 * Until Gadgetbridge 0.94 the field definition took Kelvin and subtracted 273
 * on encode, so this encoder fed it Kelvin; since 0.94 the builder takes
 * Celsius as it is, and Kelvin here would be 273 degrees too warm, or out of
 * range and dropped. The bytes on the wire are what they were, except at
 * exactly 0 C: the Kelvin helper treated 0 as "no reading", and sent that.
 *
 * **Null is not the same as absent.** Setting a field to null still adds it to
 * the record *definition* while leaving the value empty. Some watches will not
 * accept a record whose definition is missing fields they expect, so a few
 * fields are explicitly set to null rather than skipped. That is upstream's
 * behaviour and it is deliberate on both sides.
 */
object GarminWeatherEncoder {

    /** The watch's own daily-forecast screen shows five days including today. */
    private const val FORECAST_DAYS = 4

    private const val SECONDS_PER_DAY = 24 * 60 * 60

    /**
     * Twelve hours of the strip. Every hour is another FIT record on a link
     * that is also carrying activity files, and the glance scrolls a short way.
     */
    private const val FORECAST_HOURS = 12

    fun encode(weather: WeatherReport): FitLocalMessageBuilder {
        val messages = FitLocalMessageBuilder()

        messages.addRecordData(
            current(weather).build(messages.nextAvailableLocalMessageType)
        )

        // Today and the following days share one local message type: they are
        // the same record shape, and a local message type is a scarce resource
        // on this protocol (there are only 16).
        val dailyType = messages.nextAvailableLocalMessageType
        messages.addRecordData(today(weather).build(dailyType))

        weather.forecasts.take(FORECAST_DAYS).forEachIndexed { index, forecast ->
            val at = weather.timestamp + (index + 1L) * SECONDS_PER_DAY
            val builder = FitWeatherConditions.Builder()
                .setWeatherReport(FitWeatherReport.daily_forecast)
                // The day this describes, not the moment the forecast was
                // fetched. Every daily record used to carry the *same*
                // timestamp — today's — so the watch was handed five rows
                // claiming to be about the same day and kept one.
                .setTimestamp(at)
                .setLowTemperature(forecast.minTempC)
                .setHighTemperature(forecast.maxTempC)
                .setCondition(condition(forecast.conditionCode))
                .setAirQuality(null)
            builder.setDayOfWeek(dayOfWeek(at))
            messages.addRecordData(builder.build(dailyType))
        }

        // The hourly strip, which had nothing to draw at all: Tracks sent
        // current conditions and daily highs, and the type between them —
        // `hourly_forecast`, which the format has always carried — was never
        // used. Its own local message type because the record shape differs
        // from the daily one, and sharing a type means sharing a definition.
        if (weather.hourly.isNotEmpty()) {
            val hourlyType = messages.nextAvailableLocalMessageType
            weather.hourly.take(FORECAST_HOURS).forEach { hour ->
                messages.addRecordData(hourly(hour).build(hourlyType))
            }
        }

        return messages
    }

    private fun hourly(hour: HourlyForecast): AbstractFitWeatherConditions.Builder =
        FitWeatherConditions.Builder()
            .setWeatherReport(FitWeatherReport.hourly_forecast)
            .setTimestamp(hour.timestamp)
            .setTemperature(hour.tempC)
            .setCondition(condition(hour.conditionCode))
            .setPrecipitationProbability(hour.precipProbability)
            .setRelativeHumidity(hour.humidityPercent)
            // Kept in the definition even when unfilled, as elsewhere here —
            // watches reject a record whose definition omits fields they expect.
            .setTemperatureFeelsLike(null)
            .setDewPoint(null)
            .setAirQuality(null)

    private fun current(weather: WeatherReport): AbstractFitWeatherConditions.Builder {
        val builder = FitWeatherConditions.Builder()
            .setWeatherReport(FitWeatherReport.current)
            .setTimestamp(weather.timestamp)
            .setObservedAtTime(weather.timestamp)
            .setTemperature(weather.currentTempC)
            .setLowTemperature(weather.todayMinTempC)
            .setHighTemperature(weather.todayMaxTempC)
            .setCondition(condition(weather.currentConditionCode))
            .setWindDirection(weather.windDirectionDegrees)
            .setWindSpeed(weather.windSpeedKmh?.let(::millimetresPerSecond))
            .setRelativeHumidity(weather.humidityPercent)
            .setLocation(weather.location)
            // Explicitly null: the definition must carry these fields even
            // though the broadcast never supplies them. See the class note.
            .setPrecipitationProbability(null)
            .setTemperatureFeelsLike(null)
            .setDewPoint(null)
            .setAirQuality(null)

        // Position, in degrees — the generated field applies FIT's semicircle
        // scaling itself, so converting here would put the forecast in the wrong
        // hemisphere rather than fix anything.
        //
        // Sent because the watch does not treat position as decoration: the
        // glance binds conditions to one, which is why a forecast can be
        // accepted in full — definitions applied, data acknowledged — and still
        // leave the screen on "waiting for weather".
        //
        // Conditional, unlike the explicit nulls above, and this is the one
        // place that distinction is not a style choice: these two setters unbox
        // to a primitive, so handing them a null throws rather than declaring an
        // empty field. A broadcasting weather app supplies a place name and
        // usually no coordinates, so absent has to stay absent for that path.
        if (weather.lat != null && weather.lon != null) {
            builder.setObservedLocationLat(weather.lat)
            builder.setObservedLocationLong(weather.lon)
        }
        return builder
    }

    private fun today(weather: WeatherReport): AbstractFitWeatherConditions.Builder {
        val builder = FitWeatherConditions.Builder()
            .setWeatherReport(FitWeatherReport.daily_forecast)
            .setTimestamp(weather.timestamp)
            .setLowTemperature(weather.todayMinTempC)
            .setHighTemperature(weather.todayMaxTempC)
            .setCondition(condition(weather.currentConditionCode))
            .setAirQuality(null)
        builder.setDayOfWeek(dayOfWeek(weather.timestamp))
        return builder
    }

    /**
     * The FIT field is millimetres per second since Gadgetbridge 0.94's
     * profile; before it, the same bytes were km/h scaled by 298, about 7%
     * high. Upstream's own conversion (FitWeatherConditions.weatherWindSpeed),
     * which Kotlin cannot reach through the chained setters' return type.
     */
    private fun millimetresPerSecond(kmh: Float): Int = Math.round(kmh / 3.6f * 1000.0f)

    private fun condition(openWeatherCode: Int) =
        FieldDefinitionWeatherStatus.openWeatherCodeToFitWeatherStatus(openWeatherCode)

    /**
     * The watch labels forecast days by name, so this has to be the *user's*
     * day boundary rather than UTC's — a forecast that says "Tuesday" when the
     * phone says Monday evening is worse than no label at all.
     */
    private fun dayOfWeek(epochSeconds: Long) =
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).dayOfWeek

    /**
     * Delegated to the broadcast parser rather than reimplemented, so the two
     * directions cannot drift apart — including the shared decision that zero
     * means "not supplied" and stays zero.
     */
}

/**
 * Kotlin sees the generated FIT builders' `getNextAvailableLocalMessageType()`
 * as a method rather than a property, and the call reads badly inline.
 */
private val FitLocalMessageBuilder.nextAvailableLocalMessageType: Int
    get() = getNextAvailableLocalMessageType()
