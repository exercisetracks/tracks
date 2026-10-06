// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.weather

import com.tracks.core.api.HourlyForecast
import com.tracks.core.api.PointWeather
import com.tracks.core.api.WatchWeather
import com.tracks.core.api.WatchWeatherDay
import com.tracks.core.api.WatchWeatherHour
import com.tracks.core.api.WeatherDay
import com.tracks.core.api.WeatherHour
import com.tracks.core.api.WeatherNow
import com.tracks.core.fit.decode.CivilDate
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * Forecasts straight from Open-Meteo, without going through the Tracks server.
 *
 * The server used to fetch these and hand them on, which made the forecast the
 * one part of the map sheet and the watch glance that needed both the server
 * *and* the internet: a phone with signal but no route home — on a trip, or
 * with the server behind a home network — got nothing, though Open-Meteo was
 * one request away. Asking directly also means a coordinate only ever travels
 * phone → Open-Meteo, rather than phone → server → Open-Meteo.
 *
 * Privacy: each request sends the latitude and longitude being asked about —
 * a tapped point, or the phone's own rounded position for the watch —
 * rounded to four places (~11 m), and the phone's IP address rather than the
 * server's — the same disclosure the server made, from a different address.
 * Open-Meteo is keyless, so nothing identifies the account. Callers must check
 * the synced `weather_enabled` setting first; this class does not, so that it
 * stays testable without a replica. Every caller in `:app` goes through
 * `AppContainer.weatherAllowed()`.
 *
 * The response is reshaped into the same models the server's endpoints
 * returned (see `backend/app/services/weather.py` and the watch mapping in
 * `backend/app/api/device_sync.py`), so the screens did not change.
 */
class OpenMeteo(engine: HttpClientEngine? = null) {

    private val http: HttpClient = run {
        val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            // Same reasoning as TracksClient: Ktor has no timeouts of its own,
            // and on a phone that walks out of signal a stalled request is the
            // normal failure. A forecast is never worth waiting long for.
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
                requestTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
        if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    /** Current conditions and [days] days ahead, or null on any failure. */
    suspend fun point(lat: Double, lon: Double, days: Int = 7): PointWeather? =
        fetch(lat, lon, days, hourly = false)?.let(::pointWeather)

    /**
     * One local day's hours at a point. [today] is the phone's date, used
     * only to stretch `forecast_days` far enough: Open-Meteo counts days
     * forward from today rather than taking a range. Empty when the day is
     * outside the forecast window or the request fails.
     */
    suspend fun hourly(lat: Double, lon: Double, date: String, today: CivilDate): HourlyForecast {
        val wanted = runCatching { parseDate(date) }.getOrNull() ?: return HourlyForecast(date = date)
        // A day of slack for a point whose date has already rolled over
        // relative to the phone's — the server did the same.
        val days = (wanted.epochDay - today.epochDay + 2).toInt()
        if (days < 1 || days > MAX_DAYS) return HourlyForecast(date = date)
        val raw = fetch(lat, lon, days, hourly = true) ?: return HourlyForecast(date = date)
        // Timestamps are the point's local wall-clock (timezone=auto), so a
        // prefix match on the date is the whole filter.
        val hours = hourRows(raw).filter { it.time.startsWith(date) }
        return HourlyForecast(date = date, timezone = raw.str("timezone"), hours = hours)
    }

    /**
     * The forecast in the shape the watch protocol carries, for the glance.
     * Null on failure, or when the provider sent no current temperature — the
     * one field worth refusing on, since a defaulted 0 C reads as real.
     */
    suspend fun watch(lat: Double, lon: Double, nowEpochSeconds: Long): WatchWeather? {
        val raw = fetch(lat, lon, WATCH_DAYS, hourly = true) ?: return null
        val point = pointWeather(raw)
        val now = point.current ?: return null
        val tempC = now.temperatureC ?: return null
        val today = point.daily.firstOrNull()
        val code = now.weatherCode ?: 0
        return WatchWeather(
            location = placeName(lat, lon),
            // The watch attaches conditions to a position; without one its
            // glance stays on "waiting for weather". See GarminWeatherEncoder.
            lat = round5(lat),
            lon = round5(lon),
            timestamp = nowEpochSeconds,
            currentTempC = tempC.roundToInt(),
            todayMinTempC = (today?.tempMinC ?: 0.0).roundToInt(),
            todayMaxTempC = (today?.tempMaxC ?: 0.0).roundToInt(),
            currentCondition = WMO_LABEL[code] ?: "Cloudy",
            currentConditionCode = owm(code),
            // Asked for in m/s; the watch protocol carries km/h.
            windSpeedKmh = ((now.windMps ?: 0.0) * 3.6 * 10).roundToInt() / 10f,
            windDirectionDegrees = (now.windDirection ?: 0.0).roundToInt(),
            humidityPercent = (now.humidityPct ?: 0.0).roundToInt(),
            hourly = watchHours(raw, nowEpochSeconds),
            // Today is the "current" block; the forecast rows are the days after.
            forecasts = point.daily.drop(1).map {
                WatchWeatherDay(
                    minTempC = (it.tempMinC ?: 0.0).roundToInt(),
                    maxTempC = (it.tempMaxC ?: 0.0).roundToInt(),
                    conditionCode = owm(it.weatherCode ?: 0),
                )
            },
        )
    }

    private suspend fun fetch(lat: Double, lon: Double, days: Int, hourly: Boolean): JsonObject? =
        try {
            val response = http.get(URL) {
                parameter("latitude", fixed4(lat))
                parameter("longitude", fixed4(lon))
                parameter("current", CURRENT)
                parameter("daily", DAILY)
                parameter("forecast_days", days.coerceIn(1, MAX_DAYS))
                if (hourly) parameter("hourly", HOURLY)
                parameter("timezone", "auto")
                parameter("wind_speed_unit", "ms")
            }
            if (!response.status.isSuccess()) null
            else Json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }

    // ── Reshaping ────────────────────────────────────────────────────────────

    private fun pointWeather(raw: JsonObject): PointWeather {
        val cur = raw.obj("current")
        val d = raw.obj("daily")
        val dates = d.arr("time")
        return PointWeather(
            current = WeatherNow(
                temperatureC = cur.num("temperature_2m"),
                apparentC = cur.num("apparent_temperature"),
                humidityPct = cur.num("relative_humidity_2m"),
                windMps = cur.num("wind_speed_10m"),
                windDirection = cur.num("wind_direction_10m"),
                weatherCode = cur.int("weather_code"),
                isDay = cur.int("is_day"),
            ),
            daily = dates.indices.map { i ->
                WeatherDay(
                    date = dates.strAt(i) ?: "",
                    weatherCode = d.arr("weather_code").intAt(i),
                    tempMaxC = d.arr("temperature_2m_max").numAt(i),
                    tempMinC = d.arr("temperature_2m_min").numAt(i),
                    precipMm = d.arr("precipitation_sum").numAt(i),
                    precipProbPct = d.arr("precipitation_probability_max").numAt(i),
                    windMaxMps = d.arr("wind_speed_10m_max").numAt(i),
                )
            },
        )
    }

    private fun hourRows(raw: JsonObject): List<WeatherHour> {
        val h = raw.obj("hourly")
        val times = h.arr("time")
        return times.indices.map { i ->
            WeatherHour(
                time = times.strAt(i) ?: "",
                tempC = h.arr("temperature_2m").numAt(i),
                weatherCode = h.arr("weather_code").intAt(i),
                precipProbPct = h.arr("precipitation_probability").numAt(i),
                humidityPct = h.arr("relative_humidity_2m").numAt(i),
            )
        }
    }

    /**
     * The next [WATCH_HOURS] hours as epoch seconds. Open-Meteo's local
     * wall-clock times are unusable to a device until the offset it reports
     * is taken back off. Hours before the current one are dropped: the watch
     * renders the strip in order and would otherwise open on this morning.
     */
    private fun watchHours(raw: JsonObject, nowEpochSeconds: Long): List<WatchWeatherHour> {
        val offset = raw.int("utc_offset_seconds") ?: return emptyList()
        val rows = ArrayList<WatchWeatherHour>()
        for (hour in hourRows(raw)) {
            val local = localEpochSeconds(hour.time) ?: continue
            val epoch = local - offset
            val temp = hour.tempC ?: continue
            if (epoch < nowEpochSeconds - 3600) continue
            rows += WatchWeatherHour(
                timestamp = epoch,
                tempC = temp.roundToInt(),
                conditionCode = owm(hour.weatherCode ?: 0),
                precipProbability = hour.precipProbPct?.roundToInt(),
                humidityPercent = hour.humidityPct?.roundToInt(),
            )
            if (rows.size >= WATCH_HOURS) break
        }
        return rows
    }

    companion object {
        private const val URL = "https://api.open-meteo.com/v1/forecast"
        private const val MAX_DAYS = 16
        private const val WATCH_DAYS = 5

        /**
         * Half a day. The glance scrolls a short strip, and every extra hour
         * is another FIT record on a link also carrying activity files.
         */
        private const val WATCH_HOURS = 12

        private const val CURRENT = "temperature_2m,apparent_temperature,relative_humidity_2m," +
            "wind_speed_10m,wind_direction_10m,weather_code,precipitation,is_day"
        private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset"
        private const val HOURLY = "temperature_2m,weather_code,precipitation_probability,relative_humidity_2m"

        /**
         * WMO codes to the OpenWeatherMap ids the watch protocol speaks. Not
         * lossless and need not be: Garmin draws one of a dozen glyphs, so the
         * category has to survive and roughly how hard it is coming down.
         * Unknown codes become "cloudy", the least wrong thing to draw.
         */
        private val WMO_TO_OWM = mapOf(
            0 to 800, 1 to 801, 2 to 802, 3 to 804,
            45 to 741, 48 to 741,
            51 to 300, 53 to 301, 55 to 302, 56 to 511, 57 to 511,
            61 to 500, 63 to 501, 65 to 502, 66 to 511, 67 to 511,
            71 to 600, 73 to 601, 75 to 602, 77 to 601,
            80 to 520, 81 to 521, 82 to 522,
            85 to 620, 86 to 622,
            95 to 200, 96 to 201, 99 to 202,
        )

        private val WMO_LABEL = mapOf(
            0 to "Clear", 1 to "Mainly clear", 2 to "Partly cloudy", 3 to "Overcast",
            45 to "Fog", 48 to "Fog", 51 to "Light drizzle", 53 to "Drizzle",
            55 to "Heavy drizzle", 56 to "Freezing drizzle", 57 to "Freezing drizzle",
            61 to "Light rain", 63 to "Rain", 65 to "Heavy rain",
            66 to "Freezing rain", 67 to "Freezing rain",
            71 to "Light snow", 73 to "Snow", 75 to "Heavy snow", 77 to "Snow grains",
            80 to "Showers", 81 to "Showers", 82 to "Heavy showers",
            85 to "Snow showers", 86 to "Snow showers",
            95 to "Thunderstorm", 96 to "Thunderstorm", 99 to "Thunderstorm",
        )

        private fun owm(wmo: Int): Int = WMO_TO_OWM[wmo] ?: 804

        /**
         * A caption for the glance header. Coordinates are the honest label
         * that needs no lookup of its own — reverse geocoding would be a
         * second request sending the same location somewhere else.
         */
        internal fun placeName(lat: Double, lon: Double): String =
            "${fixed2(kotlin.math.abs(lat))}°${if (lat >= 0) "N" else "S"} " +
                "${fixed2(kotlin.math.abs(lon))}°${if (lon >= 0) "E" else "W"}"

        /** `YYYY-MM-DDTHH:MM` read as if it were UTC; the caller removes the offset. */
        internal fun localEpochSeconds(time: String): Long? = runCatching {
            val date = parseDate(time.substring(0, 10))
            val hh = time.substring(11, 13).toInt()
            val mm = time.substring(14, 16).toInt()
            date.epochDay * 86_400 + hh * 3600 + mm * 60
        }.getOrNull()

        private fun parseDate(iso: String): CivilDate {
            val (y, m, d) = iso.split("-").map { it.toInt() }
            return CivilDate(y, m, d)
        }

        private fun round5(v: Double): Double = kotlin.math.round(v * 100_000) / 100_000
        private fun fixed4(v: Double): String = fixed(v, 4)
        private fun fixed2(v: Double): String = fixed(v, 2)

        /** `%.nf` without String.format, which commonMain does not have. */
        private fun fixed(v: Double, places: Int): String {
            var scale = 1L
            repeat(places) { scale *= 10 }
            val scaled = kotlin.math.round(kotlin.math.abs(v) * scale).toLong()
            val whole = scaled / scale
            val frac = (scaled % scale).toString().padStart(places, '0')
            val sign = if (v < 0 && scaled != 0L) "-" else ""
            return "$sign$whole.$frac"
        }
    }
}

// Lenient JSON reads: Open-Meteo omits blocks it was not asked for and sends
// null for values a model does not produce, and neither should throw.
private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.num(key: String): Double? = prim(this[key])?.doubleOrNull
private fun JsonObject.int(key: String): Int? = prim(this[key])?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
private fun JsonArray.numAt(i: Int): Double? = prim(getOrNull(i))?.doubleOrNull
private fun JsonArray.intAt(i: Int): Int? = prim(getOrNull(i))?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() }
private fun JsonArray.strAt(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun prim(e: JsonElement?): JsonPrimitive? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }
