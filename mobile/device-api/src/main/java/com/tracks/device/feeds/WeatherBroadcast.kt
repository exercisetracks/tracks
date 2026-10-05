// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.content.Intent
import com.tracks.device.DailyForecast
import com.tracks.device.WeatherReport
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the weather broadcast that Android weather apps already send.
 *
 * ## Why this format
 *
 * Gadgetbridge defined a broadcast — action
 * `nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER` with a JSON
 * extra — and weather apps implemented it. Breezy Weather, the one this setup
 * uses, sends exactly this. Inventing a Tracks-shaped broadcast instead would
 * mean asking every weather app to add support for us, which is not a thing
 * that happens. So we speak the format that already exists.
 *
 * That is interoperability, not impersonation: we register for a public
 * broadcast action, the same way any app may. We do not claim Gadgetbridge's
 * package name, signature, or identity, and this parser is written from the
 * wire format rather than lifted from their receiver.
 *
 * ## The catch
 *
 * Whether the broadcast reaches us depends on how the sender addresses it. An
 * implicit broadcast reaches any app with a matching receiver; one sent with
 * `setPackage(...)` reaches only that package. If a sender targets Gadgetbridge
 * explicitly, uninstalling Gadgetbridge does not redirect it here — it goes
 * nowhere. [ACTION_GENERIC_WEATHER] is therefore accepted alongside
 * [ACTION_TRACKS_WEATHER], so a sender that can be pointed at us has somewhere
 * to aim.
 */
object WeatherBroadcast {

    /** The de-facto standard action, as implemented by Breezy Weather et al. */
    const val ACTION_GENERIC_WEATHER =
        "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"

    /** Our own, for senders that can be configured to target us directly. */
    const val ACTION_TRACKS_WEATHER = "com.exercisetracks.app.ACTION_WEATHER"

    const val EXTRA_WEATHER_JSON = "WeatherJson"
    const val EXTRA_WEATHER_SECONDARY_JSON = "WeatherSecondaryJson"

    fun handles(action: String?): Boolean =
        action == ACTION_GENERIC_WEATHER || action == ACTION_TRACKS_WEATHER

    /**
     * Every location in the broadcast, primary first.
     *
     * Returns empty rather than throwing on malformed input: a weather app
     * sending something unexpected should cost the user a stale forecast, not
     * a crash in a background receiver.
     */
    fun parse(intent: Intent): List<WeatherReport> {
        if (!handles(intent.action)) return emptyList()
        val extras = intent.extras ?: return emptyList()

        return try {
            val primaryJson = extras.getString(EXTRA_WEATHER_JSON) ?: return emptyList()
            buildList {
                add(fromJson(JSONObject(primaryJson)))
                extras.getString(EXTRA_WEATHER_SECONDARY_JSON)?.let { secondary ->
                    val array = JSONArray(secondary)
                    for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Every field is optional with a sensible default.
     *
     * Senders differ in what they populate, and a missing wind speed is not a
     * reason to discard an otherwise good forecast.
     */
    internal fun fromJson(json: JSONObject): WeatherReport = WeatherReport(
        location = json.optString("location", ""),
        timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000),
        currentTempC = kelvinToCelsius(json.optInt("currentTemp", 0)),
        todayMinTempC = kelvinToCelsius(json.optInt("todayMinTemp", 0)),
        todayMaxTempC = kelvinToCelsius(json.optInt("todayMaxTemp", 0)),
        currentCondition = json.optString("currentCondition", ""),
        currentConditionCode = json.optInt("currentConditionCode", 0),
        windSpeedKmh = if (json.has("windSpeed")) json.optDouble("windSpeed").toFloat() else null,
        windDirectionDegrees = if (json.has("windDirection")) json.optInt("windDirection") else null,
        humidityPercent = if (json.has("currentHumidity")) json.optInt("currentHumidity") else null,
        forecasts = parseForecasts(json.optJSONArray("forecasts")),
    )

    private fun parseForecasts(array: JSONArray?): List<DailyForecast> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val day = array.optJSONObject(i) ?: return@mapNotNull null
            DailyForecast(
                minTempC = kelvinToCelsius(day.optInt("minTemp", 0)),
                maxTempC = kelvinToCelsius(day.optInt("maxTemp", 0)),
                conditionCode = day.optInt("conditionCode", 0),
            )
        }
    }

    /**
     * The wire format carries Kelvin, which is not obvious and is easy to get
     * wrong by 273 degrees.
     *
     * Zero is treated as absent rather than converted: it is the default this
     * parser and the senders both use for "not supplied", and -273 C on a watch
     * face is worse than 0.
     */
    fun kelvinToCelsius(kelvin: Int): Int =
        if (kelvin == 0) 0 else kelvin - 273

    /**
     * The inverse, and it lives here rather than with the watch encoder that
     * needs it.
     *
     * Garmin's FIT weather fields are also fed Kelvin — the field definition
     * subtracts 273 on its way to the wire — so a forecast that came in from a
     * broadcast has to go back out the way it arrived. Keeping both directions
     * in one object is what makes "these are inverses" a property someone can
     * check rather than a coincidence spread across two modules.
     */
    fun celsiusToKelvin(celsius: Int): Int =
        if (celsius == 0) 0 else celsius + 273
}
