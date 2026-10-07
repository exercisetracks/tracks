// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.Serializable

/**
 * Wire models for the endpoints a mobile client actually uses.
 *
 * Hand-written rather than generated from the OpenAPI schema. The server
 * exposes roughly two hundred endpoints; generating all of them would bury the
 * dozen that matter here in noise, and the generated shapes would still need
 * wrapping to be pleasant to use. What generation buys — noticing when the
 * server's contract moves — is bought instead by ApiContractTest, which checks
 * every path this client calls against the live `/openapi.json`.
 *
 * Every model is lenient about unknown fields (see `TracksJson`): the server
 * can be newer than the app, and adding a field must never break an installed
 * client.
 */

// ── Capability negotiation ───────────────────────────────────────────────────

@Serializable
data class Capabilities(
    val app: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("min_client_api_version") val minClientApiVersion: Int,
    val features: List<String> = emptyList(),
    val limits: Limits = Limits(),
) {
    fun supports(feature: String): Boolean = feature in features

    /**
     * Whether this client can talk to this server at all.
     *
     * Both directions matter once the app ships through a store and update
     * timing stops being ours: the app can be older than the server's floor, or
     * newer than a server that has not been updated.
     */
    fun compatibleWith(clientApiVersion: Int): Compatibility = when {
        clientApiVersion < minClientApiVersion -> Compatibility.CLIENT_TOO_OLD
        clientApiVersion > apiVersion -> Compatibility.SERVER_TOO_OLD
        else -> Compatibility.OK
    }
}

enum class Compatibility { OK, CLIENT_TOO_OLD, SERVER_TOO_OLD }

/**
 * `GET /version`: this server, and the newest Tracks release GitHub knows of.
 *
 * The server asks GitHub, not the phone, so a phone that only ever reaches its
 * server over a LAN or VPN still hears of updates. [latest] is null when the
 * check is off ([updateCheck] false) or GitHub could not be reached
 * ([checkError] set) — "not known", never "up to date".
 */
@Serializable
data class VersionStatus(
    @SerialName("server_version") val serverVersion: String,
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("update_check") val updateCheck: Boolean = false,
    val latest: Release? = null,
    @SerialName("checked_at") val checkedAt: String? = null,
    @SerialName("check_error") val checkError: String? = null,
    @SerialName("server_update_available") val serverUpdateAvailable: Boolean = false,
    @SerialName("releases_url") val releasesUrl: String = "https://github.com/exercisetracks/tracks/releases",
)

@Serializable
data class Release(
    val version: String,
    val url: String,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class Limits(
    @SerialName("track_max_points") val trackMaxPoints: Int = 50_000,
    @SerialName("activities_page_size") val activitiesPageSize: Int = 100,
    @SerialName("fit_upload_bytes") val fitUploadBytes: Long = 50L * 1024 * 1024,
    @SerialName("fit_precheck_entries") val fitPrecheckEntries: Int = 10_000,
    @SerialName("ingest_batch_files") val ingestBatchFiles: Int = 200,
    @SerialName("ingest_batch_bytes") val ingestBatchBytes: Long = 16L * 1024 * 1024,
    @SerialName("ingest_missing_hashes") val ingestMissingHashes: Int = 10_000,
)

// ── Auth ─────────────────────────────────────────────────────────────────────

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("issue_refresh_token") val issueRefreshToken: Boolean = true,
    @SerialName("device_label") val deviceLabel: String? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("refresh_token") val refreshToken: String? = null,
)

/**
 * `POST /users/me/password`. Asks for a refresh token because the change
 * revokes every one the account holds, this phone's included.
 */
@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("issue_refresh_token") val issueRefreshToken: Boolean = true,
    @SerialName("device_label") val deviceLabel: String? = null,
)

/**
 * The server refuses every access token issued before the change, so the
 * caller's replacement comes back here, on the same session.
 */
@Serializable
data class PasswordChangeResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("revoked_device_keys") val revokedDeviceKeys: Int = 0,
    @SerialName("revoked_sessions") val revokedSessions: Int = 0,
)

/** `GET /users/me`, as much of it as the phone needs. */
@Serializable
data class CurrentUser(
    val id: Int,
    val username: String? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class DeviceKeyCreate(val label: String? = null)

@Serializable
data class DeviceKeyCreated(
    val id: Int,
    val label: String? = null,
    @SerialName("device_secret") val deviceSecret: String,
)

@Serializable
data class DeviceUnlockRequest(
    @SerialName("device_key_id") val deviceKeyId: Int,
    @SerialName("device_secret") val deviceSecret: String,
    @SerialName("issue_refresh_token") val issueRefreshToken: Boolean = true,
)

// ── Activities ───────────────────────────────────────────────────────────────

@Serializable
data class ActivitySummary(
    val id: Int,
    val sport: String? = null,
    @SerialName("sub_sport") val subSport: String? = null,
    val name: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("avg_heart_rate") val avgHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    @SerialName("total_calories") val totalCalories: Int? = null,
    @SerialName("total_ascent") val totalAscent: Double? = null,
    @SerialName("avg_speed") val avgSpeed: Double? = null,
    @SerialName("device_id") val deviceId: Int? = null,
    @SerialName("is_merged") val isMerged: Boolean = false,
)

/**
 * A correction to an activity.
 *
 * Null means "unchanged", not "clear it" — see [TracksClient.updateActivity].
 */
@Serializable
data class ActivityUpdate(
    val name: String? = null,
    val notes: String? = null,
    val sport: String? = null,
)

@Serializable
data class DailyMetric(
    val id: Int,
    val date: String,
    @SerialName("resting_hr") val restingHr: Double? = null,
    val hrv: Double? = null,
    @SerialName("sleep_hours") val sleepHours: Double? = null,
    @SerialName("sleep_score") val sleepScore: Double? = null,
    @SerialName("training_load") val trainingLoad: Double? = null,
    val steps: Int? = null,
    @SerialName("active_calories") val activeCalories: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
)

@Serializable
data class TrackPoint(
    @SerialName("recorded_at") val recordedAt: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val altitude: Double? = null,
    @SerialName("heart_rate") val heartRate: Int? = null,
    val power: Int? = null,
    val cadence: Int? = null,
    val speed: Double? = null,
    val grit: Double? = null,
    val flow: Double? = null,
)

/**
 * The full activity record, for the detail screen.
 *
 * Extends nothing — Kotlin data classes cannot inherit, and the server's
 * ActivityDetail is ActivitySummary plus ~20 fields. Duplicating the summary
 * fields here is the price of that; [ApiContractTest] checks the endpoint
 * still exists, and every field is nullable so a server that stops sending one
 * degrades to a hidden stat rather than a parse failure.
 */
@Serializable
data class ActivityDetail(
    val id: Int,
    val sport: String? = null,
    @SerialName("sub_sport") val subSport: String? = null,
    val name: String? = null,
    val notes: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("avg_heart_rate") val avgHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    @SerialName("total_calories") val totalCalories: Int? = null,
    @SerialName("total_ascent") val totalAscent: Double? = null,
    @SerialName("total_descent") val totalDescent: Double? = null,
    @SerialName("avg_speed") val avgSpeed: Double? = null,
    @SerialName("max_speed") val maxSpeed: Double? = null,
    @SerialName("avg_cadence") val avgCadence: Int? = null,
    @SerialName("avg_power") val avgPower: Int? = null,
    @SerialName("normalized_power") val normalizedPower: Int? = null,
    @SerialName("training_stress_score") val trainingStressScore: Double? = null,
    @SerialName("effective_tss") val effectiveTss: Double? = null,
    @SerialName("intensity_factor") val intensityFactor: Double? = null,
    @SerialName("aerobic_training_effect") val aerobicTrainingEffect: Double? = null,
    @SerialName("anaerobic_training_effect") val anaerobicTrainingEffect: Double? = null,
    @SerialName("vo2max_estimate") val vo2maxEstimate: Double? = null,
    @SerialName("efficiency_factor") val efficiencyFactor: Double? = null,
    @SerialName("aerobic_decoupling") val aerobicDecoupling: Double? = null,
    @SerialName("total_grit") val totalGrit: Double? = null,
    @SerialName("avg_flow") val avgFlow: Double? = null,
    /** "How did you feel": 0–100 in steps of 25, very weak … very strong. */
    @SerialName("workout_feel") val workoutFeel: Int? = null,
    /** Perceived effort ×10, as the watch records it: 70 is 7/10. */
    @SerialName("workout_rpe") val workoutRpe: Int? = null,
    @SerialName("lap_count") val lapCount: Int? = null,
    @SerialName("device_id") val deviceId: Int? = null,
    @SerialName("is_merged") val isMerged: Boolean = false,
)

/** One lap. Golf fields are null for everything that is not golf. */
@Serializable
data class Lap(
    val id: Int,
    @SerialName("lap_number") val lapNumber: Int,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("distance_meters") val distanceMeters: Double? = null,
    @SerialName("avg_heart_rate") val avgHeartRate: Int? = null,
    @SerialName("max_heart_rate") val maxHeartRate: Int? = null,
    @SerialName("avg_speed") val avgSpeed: Double? = null,
    @SerialName("max_speed") val maxSpeed: Double? = null,
    @SerialName("avg_cadence") val avgCadence: Int? = null,
    @SerialName("total_ascent") val totalAscent: Double? = null,
    @SerialName("total_descent") val totalDescent: Double? = null,
    @SerialName("avg_power") val avgPower: Int? = null,
    @SerialName("total_calories") val totalCalories: Int? = null,
    // Golf records each hole as a lap, which is why strokes and putts live on
    // a lap rather than anywhere golf-shaped. Null for every other sport.
    @SerialName("total_strokes") val totalStrokes: Int? = null,
    @SerialName("total_putts") val totalPutts: Int? = null,
    @SerialName("avg_stroke_distance") val avgStrokeDistance: Double? = null,
    // Mountain biking only — Garmin's own "how rough" and "how smooth" scores.
    @SerialName("total_grit") val totalGrit: Double? = null,
    @SerialName("avg_flow") val avgFlow: Double? = null,
)

/** A GPS point with nothing but a position — what the heatmap draws. */
@Serializable
data class HeatmapPoint(val lat: Double, val lng: Double)

// ── Watch sync ───────────────────────────────────────────────────────────────

/**
 * One file to write to the watch. `dataB64` is a complete FIT file the server
 * already built — the client is a transport and never authors FIT content.
 *
 * Wire name is `fit_b64`, not `data_b64` — found on hardware, the expensive
 * way. `/device-sync/upload-list` and its course counterpart both build this
 * key from the same server-side helpers (`_upload_items_for_user`,
 * `_course_upload_items`), so `fit_b64` is the one name every existing
 * caller — the browser's WebUSB path included — has always used. The mobile
 * model was written with an assumed name that was never checked against that
 * contract, and because `dataB64` has a default of null, decoding never
 * failed: every push silently decoded to bytes-less items and
 * [com.tracks.app.device.WatchSyncRunner] moved on with nothing to send, no
 * exception, no log line — first caught by watching a real sync push zero of
 * eighteen scheduled workouts to a real watch.
 */
@Serializable
data class PushItem(
    val type: String,
    val id: Int? = null,
    val filename: String,
    /** On-device destination, e.g. `GARMIN/NewFiles`. */
    val folder: String,
    @SerialName("fit_b64") val dataB64: String? = null,
    /** Waypoints only — see [MarkItem.ids]. */
    val ids: List<Int>? = null,
)

@Serializable
data class PushList(val items: List<PushItem> = emptyList())

/**
 * A schedule and every workout it names, to be pushed as one set.
 *
 * The workouts come back whether or not the server believes the watch already
 * holds them — see the server's `_schedule_bundle_for_user` for the capture
 * that made that necessary. [workouts] is authoritative for this window: a
 * caller that pushes the bundle has delivered every scheduled workout and
 * should not push them again from the ordinary upload list.
 */
@Serializable
data class ScheduleBundle(
    @SerialName("fit_b64") val dataB64: String? = null,
    val count: Int = 0,
    val filename: String = "SCHEDULE.fit",
    val folder: String = "GARMIN/NewFiles",
    val workouts: List<PushItem> = emptyList(),
)

/**
 * The training calendar as one FIT file.
 *
 * Unlike [PushItem] this is not queued and never marked: the schedule is a
 * complete statement of what the watch's calendar should contain, so it is
 * rebuilt and resent rather than tracked file by file. [count] is how many
 * workouts it places, and is zero when there is nothing scheduled — a
 * legitimate answer that still carries a valid file, since an empty schedule
 * is how a cancelled week clears the watch.
 */
@Serializable
data class ScheduleFit(
    @SerialName("fit_b64") val dataB64: String? = null,
    val count: Int = 0,
    val filename: String = "SCHEDULE.fit",
    /** On-device destination, e.g. `GARMIN/NewFiles`. */
    val folder: String = "GARMIN/NewFiles",
)

/**
 * A forecast for the watch, for a phone with no weather app feeding it.
 *
 * Shaped to match what the watch protocol carries rather than what Open-Meteo
 * returns: [com.tracks.core.weather.OpenMeteo] turns the provider's WMO codes
 * into the OpenWeatherMap ids these broadcasts speak, beside the request. The
 * server's `/device-sync/weather` returns the same shape for older phones.
 */
@Serializable
data class WatchWeather(
    val location: String = "",
    /** Where the forecast is for. The watch needs this, not just the label. */
    val lat: Double? = null,
    val lon: Double? = null,
    val timestamp: Long = 0,
    @SerialName("current_temp_c") val currentTempC: Int = 0,
    @SerialName("today_min_temp_c") val todayMinTempC: Int = 0,
    @SerialName("today_max_temp_c") val todayMaxTempC: Int = 0,
    @SerialName("current_condition") val currentCondition: String = "",
    @SerialName("current_condition_code") val currentConditionCode: Int = 804,
    @SerialName("wind_speed_kmh") val windSpeedKmh: Float? = null,
    @SerialName("wind_direction_degrees") val windDirectionDegrees: Int? = null,
    @SerialName("humidity_percent") val humidityPercent: Int? = null,
    val hourly: List<WatchWeatherHour> = emptyList(),
    val forecasts: List<WatchWeatherDay> = emptyList(),
)

@Serializable
data class WatchWeatherHour(
    val timestamp: Long = 0,
    @SerialName("temp_c") val tempC: Int = 0,
    @SerialName("condition_code") val conditionCode: Int = 804,
    @SerialName("precip_probability") val precipProbability: Int? = null,
    @SerialName("humidity_percent") val humidityPercent: Int? = null,
)

@Serializable
data class WatchWeatherDay(
    @SerialName("min_temp_c") val minTempC: Int = 0,
    @SerialName("max_temp_c") val maxTempC: Int = 0,
    @SerialName("condition_code") val conditionCode: Int = 804,
)

// ── Map inspection and routing ───────────────────────────────────────────────

/**
 * What is at a point on the map.
 *
 * The elevation is sampled from the DEM the server holds, the POI comes from
 * its indexed gazetteer, and both are things a phone in a valley cannot work
 * out for itself. Every field is optional because the server answers with
 * whatever it has: outside a downloaded region there is no DEM to sample, and
 * away from anywhere named there is no POI.
 */
@Serializable
data class PointInfo(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerialName("elevation_m") val elevationMetres: Double? = null,
    @SerialName("elevation_ft") val elevationFeet: Double? = null,
    @SerialName("nearest_poi") val nearestPoi: NearbyPoi? = null,
    /**
     * Conditions and the week ahead. The phone asks for point info without it
     * and fills this in from Open-Meteo directly, once that answers. Null is a
     * real answer: no network, or a point the provider has no grid for.
     */
    val weather: PointWeather? = null,
    /**
     * The user turned Weather off in privacy settings, so nothing was asked.
     * Kept apart from a null [weather] so the sheet can say "off" rather than
     * "unavailable" — one is a choice, the other looks like a fault. Set by
     * the phone after reading the setting; the server sends it too.
     */
    @SerialName("weather_disabled") val weatherDisabled: Boolean = false,
)

/** Current conditions plus the daily outlook, in metric. */
@Serializable
data class PointWeather(
    val current: WeatherNow? = null,
    val daily: List<WeatherDay> = emptyList(),
)

@Serializable
data class WeatherNow(
    @SerialName("temperature_c") val temperatureC: Double? = null,
    @SerialName("apparent_c") val apparentC: Double? = null,
    @SerialName("humidity_pct") val humidityPct: Double? = null,
    @SerialName("wind_mps") val windMps: Double? = null,
    @SerialName("wind_direction") val windDirection: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
)

@Serializable
data class WeatherDay(
    val date: String = "",
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("temp_max_c") val tempMaxC: Double? = null,
    @SerialName("temp_min_c") val tempMinC: Double? = null,
    @SerialName("precip_mm") val precipMm: Double? = null,
    @SerialName("precip_prob_pct") val precipProbPct: Double? = null,
    @SerialName("wind_max_mps") val windMaxMps: Double? = null,
)

/**
 * One hour of a day's forecast.
 *
 * Fetched only when somebody opens a day, which is why it is not part of
 * [PointWeather]: the daily strip answers "what is this week like" for every
 * tap, and the hours answer "when should I go" for the few that care.
 */
@Serializable
data class WeatherHour(
    /** Local wall-clock, `YYYY-MM-DDTHH:MM`, in the point's own timezone. */
    val time: String = "",
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("weather_code") val weatherCode: Int? = null,
    @SerialName("precip_prob_pct") val precipProbPct: Double? = null,
    @SerialName("humidity_pct") val humidityPct: Double? = null,
)

/** A day's hours, grouped as the server's `/maps/point/hourly` did. */
@Serializable
data class HourlyForecast(
    val date: String = "",
    val timezone: String? = null,
    val hours: List<WeatherHour> = emptyList(),
)

/** DEM heights under a line, one per coordinate, null where the DEM has no data. */
@Serializable
data class SampledElevations(
    val elevations: List<Double?> = emptyList(),
)

@Serializable
data class NearbyPoi(
    val name: String = "",
    val kind: String? = null,
    @SerialName("kind_detail") val kindDetail: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerialName("distance_m") val distanceMetres: Double? = null,
)

/** One hit from the gazetteer, flattened out of the GeoJSON the server sends. */
@Serializable
data class PoiHit(
    val name: String,
    val description: String,
    val lat: Double,
    val lng: Double,
)

/**
 * The compact export behind offline place search — see [Endpoints.POI_OFFLINE].
 *
 * Carries none of `/search`'s ranking machinery, because there is nothing
 * offline to rank against a live database: this is the raw material a phone
 * indexes and scores for itself once downloaded, not a set of results.
 */
@Serializable
data class OfflinePoiExport(
    val points: List<OfflinePoi> = emptyList(),
)

/**
 * One named place, as compact as the export gets.
 *
 * [id] is `poi_search`'s own primary key — carried so the phone can
 * de-duplicate a point that two overlapping region downloads both cover,
 * something a plain name-and-position pair cannot do reliably on its own.
 */
@Serializable
data class OfflinePoi(
    val id: Long,
    val name: String,
    val kind: String? = null,
    val lat: Double,
    val lng: Double,
)

/**
 * `:app` stores this list itself — see [com.tracks.app.map.OfflinePoiData] —
 * but does not depend on `kotlinx-serialization-json` directly, the same
 * boundary [com.tracks.core.sync.OfflineRepository] draws for the encrypted
 * mirror's documents. These two keep the concrete [Json] type inside `:core`
 * so the app-layer class only ever handles plain [String].
 */
private val OFFLINE_POI_LIST = ListSerializer(OfflinePoi.serializer())

fun encodeOfflinePoi(points: List<OfflinePoi>): String =
    TracksJson.encodeToString(OFFLINE_POI_LIST, points)

fun decodeOfflinePoi(body: String): List<OfflinePoi> =
    TracksJson.decodeFromString(OFFLINE_POI_LIST, body)

/** The body `/maps/route/elevation` wants: just the line. */
@Serializable
data class ElevationRequest(
    val coordinates: List<List<Double>>,
)

@Serializable
data class SnapRouteRequest(
    val coordinates: List<List<Double>>,
    /** BRouter's profile name. `trekking` is its walking/hiking default. */
    val profile: String = "trekking",
)

/**
 * What the phone must hold to route inside an area with no network.
 *
 * Routing offline is not the same problem as drawing the map offline. The tiles
 * say where the trail is; they do not say whether it is walkable, how steep it
 * gets, or which junction connects to which. That lives in BRouter's rd5
 * segments — a separate download, on a 5° grid that has nothing to do with the
 * region the user drew.
 *
 * The server answers with what it actually holds, so a cell over the ocean (no
 * rd5 exists, ever) is simply absent rather than listed and unfetchable.
 */
@Serializable
data class RoutingDataManifest(
    val files: List<RoutingFile> = emptyList(),
    @SerialName("total_bytes") val totalBytes: Long = 0,
)

@Serializable
data class RoutingFile(
    /** `segment` or `profile` — which endpoint fetches it, and where it lands. */
    val kind: String,
    val name: String,
    val bytes: Long = 0,
)

// ── Offline map regions ──────────────────────────────────────────────────────

/**
 * An area of the map the server has downloaded, or is downloading.
 *
 * The server owns this work — extracting a bbox out of the planet archives is
 * minutes of tippecanoe and gigabytes of intermediate files, which is not
 * something a phone does. The phone asks for a region and watches; both clients
 * see the same registry, so an area downloaded from the browser is already
 * there for the watch trip.
 *
 * [status] is the server's own vocabulary, deliberately not narrowed to an enum
 * here: the pipeline runs through `downloading`, `downloading_dem`, `merging`,
 * `trails`, `contours` and `combining` before finishing, and a stage added
 * later should show up as an unfamiliar label rather than as a parse failure
 * that takes the screen down.
 *
 * Only the two ends are interpreted, and getting the success one wrong is
 * expensive rather than cosmetic. It is **`installed`**, not `ready` — a value
 * this client originally guessed. Everything downstream keys off [isReady]: the
 * phone waits for it before pulling tiles into its own store, so a region built
 * perfectly on the server sat at "preparing" forever and nothing was ever saved
 * for offline use. See `region_registry._IN_PROGRESS_STATUSES` for the set this
 * is the complement of.
 */
@Serializable
data class MapRegion(
    val id: Int,
    val name: String = "",
    /** `[west, south, east, north]`, the order every bbox in this API uses. */
    val bbox: List<Double> = emptyList(),
    /**
     * The real shape of a merged area, when it has one.
     *
     * Overlapping downloads are folded into a single region whose [bbox] is the
     * *envelope* of everything it swallowed. For an L-shaped pair that envelope
     * includes a large corner no tile covers, so drawing the box would promise
     * ground the server does not have — which is the one thing a coverage
     * outline must never do. Null for a plain single-box area, where the box is
     * the shape.
     *
     * Kept as raw GeoJSON rather than modelled: it is a Polygon or a
     * MultiPolygon depending on how the merge went, both clients hand it
     * straight to a map library, and neither has any reason to look inside it.
     */
    val geometry: JsonElement? = null,
    val status: String = "",
    /** 0.0 to 1.0 while building; meaningless once ready. */
    val progress: Double = 0.0,
    /** Free text naming the current pipeline stage, for the progress line. */
    val detail: String? = null,
    val error: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /**
     * Set when the server has heard nothing from the worker in a long while.
     * A stalled build looks identical to a slow one without it.
     */
    val stale: Boolean = false,
) {
    val isReady: Boolean get() = status == "installed"
    val isFailed: Boolean get() = status == "error"
    val isWorking: Boolean get() = !isReady && !isFailed

    /**
     * [geometry] as the document a map library wants, or null.
     *
     * The accessor exists so a client can draw the real shape without taking a
     * dependency on the serialization library's tree types just to call
     * `toString` on one.
     */
    val shapeGeoJson: String? get() = geometry?.toString()
}

/** What a bbox would cost to download, in bytes, before committing to it. */
@Serializable
data class RegionEstimate(val bytes: Long = 0)

@Serializable
data class RegionNameSuggestion(val name: String = "")

@Serializable
data class RegionDownloadRequest(val bbox: String, val name: String)

@Serializable
data class RegionDownloadStarted(
    val status: String = "",
    val region: MapRegion? = null,
)

@Serializable
data class MarkUploadedRequest(val items: List<MarkItem>)

/**
 * What was actually written to the watch.
 *
 * [ids] exists for waypoints alone. A watch keeps every saved place in one
 * `Locations.fit`, so the unit that got delivered is the whole list the server
 * put in the file — not [id], which is a placeholder for that type. Echoing it
 * back is what lets the server record the set it built rather than re-reading
 * flags that may have changed since.
 */
@Serializable
data class MarkItem(
    val type: String,
    val id: Int? = null,
    val filename: String? = null,
    val ids: List<Int>? = null,
)

@Serializable
data class AgpsResponse(
    val due: Boolean = false,
    val folder: String? = null,
    val filename: String? = null,
    @SerialName("data_b64") val dataB64: String? = null,
)

// ── Sealed ingest (works while the vault is locked) ──────────────────────────

@Serializable
data class SyncPubkey(
    @SerialName("user_id") val userId: Int,
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class IngestRequest(
    @SerialName("device_serial") val deviceSerial: String? = null,
    val filename: String,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("sealed_b64") val sealedB64: String,
)

/**
 * A course FIT going the other way from an activity: read now, not queued.
 *
 * [size] is what the watch reported for the file, and the server keeps it to
 * tell whether the device's copy has changed since it last ingested one under
 * the same name.
 */
@Serializable
data class CourseIngestRequest(
    val filename: String,
    @SerialName("fit_b64") val fitB64: String,
    val size: Long? = null,
)

/**
 * A watch's whole Locations.fit.
 *
 * No filename: there is only ever one, and its name is the server's business
 * rather than something worth agreeing about over the wire.
 */
@Serializable
data class WaypointIngestRequest(
    @SerialName("fit_b64") val fitB64: String,
)

@Serializable
data class IngestResponse(val status: String)

/** `POST /sync/ingest/missing`: which of these the server does not hold yet. */
@Serializable
data class IngestMissingRequest(
    @SerialName("device_serial") val deviceSerial: String? = null,
    val hashes: List<String>,
)

@Serializable
data class IngestMissingResponse(val missing: List<String> = emptyList())

/** `POST /sync/ingest/batch`: many [IngestRequest]s under one request and one commit. */
@Serializable
data class IngestBatchRequest(
    @SerialName("device_serial") val deviceSerial: String? = null,
    val files: List<IngestRequest>,
)

@Serializable
data class IngestBatchResult(
    @SerialName("content_hash") val contentHash: String,
    val status: String,
)

/** One result per file sent, in the order sent. */
@Serializable
data class IngestBatchResponse(val results: List<IngestBatchResult> = emptyList())

/**
 * Registering this phone as a sync agent.
 *
 * `kind` is one of the server's two accepted values and `"mobile-app"` is ours —
 * it already existed in the server's model before this app did.
 *
 * `household = false` deliberately: a household agent has no owner and needs
 * admin rights to create, and its uploads land unclaimed for a human to sort
 * out. A phone belongs to exactly one person, so a personal agent is both
 * correct and the one that auto-claims.
 */
@Serializable
data class CreateSyncAgentRequest(
    val kind: String = "mobile-app",
    val label: String,
    val household: Boolean = false,
)

/**
 * The token field is present exactly once, on creation — the server stores only
 * a hash of it and cannot ever show it again. Losing it means registering a new
 * agent, not recovering this one.
 */
@Serializable
data class CreateSyncAgentResponse(
    val id: Int,
    val kind: String,
    val label: String,
    val token: String,
)


// ── Account preferences ──────────────────────────────────────────────────────

/**
 * The preferences that belong to the account rather than to a device.
 *
 * Every field is optional here even though the server declares several of them
 * required. That is the same leniency the rest of this client applies: an older
 * or newer server that stops sending `chart_resolution` should cost a
 * preference, not the whole settings screen.
 *
 * ## What is deliberately not in here
 *
 * Light versus dark. That one follows the device it is being read on — the
 * phone in your hand at night and the laptop under an office light want
 * different answers, and syncing it would mean one of the two is always wrong.
 * The accent colour is the opposite: it is an identity choice, and a phone that
 * kept its own would make the two clients look like different products.
 */
@Serializable
data class UserSettings(
    /** `metric` | `imperial`. */
    val units: String = "metric",
    val timezone: String? = null,
    /** A preset name — `emerald`, `sky`, … — or a `#rrggbb` literal. */
    @SerialName("accent_color") val accentColor: String? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("height_cm") val heightCm: Double? = null,

    /**
     * Whether workouts pushed to the watch carry pace, heart-rate or power
     * targets. Off means every step is emitted open, which is what the watch
     * shows for someone who has not asked to be paced.
     */
    @SerialName("pace_coaching") val paceCoaching: Boolean = false,

    /**
     * Threshold heart rate and FTP, each as a mode plus the two candidate
     * values.
     *
     * The server stores them this way rather than resolved because "auto" has
     * to keep moving as fitness changes while a manual entry must not — see
     * [thresholdHeartRate] and [functionalThresholdPower] for the resolution,
     * which mirrors the server's `_effective_threshold_hr`/`_effective_ftp`.
     * These have always been in the response and were simply not modelled;
     * without them the phone cannot put a target on a bike workout it built.
     */
    @SerialName("threshold_hr_mode") val thresholdHrMode: String = "auto",
    @SerialName("threshold_hr_manual") val thresholdHrManual: Int? = null,
    @SerialName("threshold_hr_auto") val thresholdHrAuto: Int? = null,
    @SerialName("ftp_mode") val ftpMode: String = "auto",
    @SerialName("ftp_manual") val ftpManual: Int? = null,
    @SerialName("ftp_auto") val ftpAuto: Int? = null,

    /**
     * AI coaching, which runs on the server and so never syncs (spec/sync.yaml
     * leaves `ai_*` out). The key itself is write-only: the server answers
     * only whether one is stored, never the key.
     */
    @SerialName("ai_provider") val aiProvider: String? = null,
    @SerialName("ai_model") val aiModel: String? = null,
    @SerialName("ai_endpoint") val aiEndpoint: String? = null,
    @SerialName("ai_configured") val aiConfigured: Boolean = false,
) {
    val imperial: Boolean get() = units.equals("imperial", ignoreCase = true)

    /** The threshold heart rate actually in force, or null if none is known. */
    val thresholdHeartRate: Int?
        get() = if (thresholdHrMode == "manual") thresholdHrManual else thresholdHrAuto

    /** The FTP actually in force, or null if none is known. */
    val functionalThresholdPower: Int?
        get() = if (ftpMode == "manual") ftpManual else ftpAuto
}

/** A partial update. Unset fields are left alone, as everywhere else. */
@Serializable
data class UserSettingsUpdate(
    val units: String? = null,
    @SerialName("accent_color") val accentColor: String? = null,
)


/**
 * A saved place.
 *
 * [loadToDevice] is what the user asked for and [onWatch] is what is actually
 * on the wrist — the same desired/actual split a course carries. Reporting only
 * one of them would make "queued for the next sync" and "already there"
 * indistinguishable, which is exactly the distinction somebody checks before
 * leaving the house.
 */
@Serializable
data class Waypoint(
    val id: Int = 0,
    val name: String = "Waypoint",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerialName("ele_m") val elevationMetres: Double? = null,
    val color: String = "#2563eb",
    val icon: String = "marker",
    val notes: String? = null,
    @SerialName("load_to_device") val loadToDevice: Boolean = false,
    @SerialName("on_watch") val onWatch: Boolean = false,
)

/** What the server accepts to save a place. */
@Serializable
data class WaypointIn(
    val name: String = "Waypoint",
    val lat: Double,
    val lng: Double,
    @SerialName("ele_m") val elevationMetres: Double? = null,
    val color: String = "#2563eb",
    val icon: String = "marker",
    val notes: String? = null,
    @SerialName("load_to_device") val loadToDevice: Boolean = false,
)

/** A partial update; omitted fields are left alone rather than cleared. */
@Serializable
data class WaypointUpdate(
    val name: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val color: String? = null,
    val icon: String? = null,
    val notes: String? = null,
    @SerialName("load_to_device") val loadToDevice: Boolean? = null,
)

/** A drawn or imported course, as the list shows it. */
@Serializable
data class CourseSummary(
    val id: Int = 0,
    val name: String = "",
    val color: String = "#2563eb",
    val sport: String = "hiking",
    @SerialName("distance_m") val distanceMetres: Double = 0.0,
    @SerialName("ascent_m") val ascentMetres: Double = 0.0,
    @SerialName("load_to_device") val loadToDevice: Boolean = false,
    /** The server's own word for where it stands: queued, on-device, external. */
    @SerialName("device_status") val deviceStatus: String? = null,
    @SerialName("is_external") val isExternal: Boolean = false,
    val hidden: Boolean = false,
    /** `[west, south, east, north]`, for framing the track on a map. */
    val bounds: List<Double>? = null,
)

/** One sample along a saved track's height profile, as the server stores it. */
@Serializable
data class CourseProfilePoint(
    @SerialName("d_km") val distanceKm: Double = 0.0,
    @SerialName("ele_m") val elevationMetres: Double? = null,
)

/**
 * A saved track's height against distance.
 *
 * Resampled and DEM-corrected server-side rather than read off the geometry:
 * an imported GPX often carries barometric noise or no elevation at all, and
 * the profile is the one place that difference is glaring.
 */
@Serializable
data class CourseProfile(
    val points: List<CourseProfilePoint> = emptyList(),
    @SerialName("gain_m") val gainMetres: Double = 0.0,
    @SerialName("loss_m") val lossMetres: Double = 0.0,
    @SerialName("min_m") val minMetres: Double? = null,
    @SerialName("max_m") val maxMetres: Double? = null,
)

/** A track with everything the list view leaves out. */
@Serializable
data class CourseDetail(
    val id: Int = 0,
    val name: String = "",
    val color: String = "#2563eb",
    val sport: String = "hiking",
    @SerialName("distance_m") val distanceMetres: Double = 0.0,
    @SerialName("ascent_m") val ascentMetres: Double = 0.0,
    @SerialName("descent_m") val descentMetres: Double = 0.0,
    @SerialName("load_to_device") val loadToDevice: Boolean = false,
    @SerialName("device_status") val deviceStatus: String? = null,
    @SerialName("is_external") val isExternal: Boolean = false,
    val bounds: List<Double>? = null,
    val notes: String? = null,
    val profile: CourseProfile? = null,
) {
    /** The list-shaped view of the same track, so one row renders either. */
    fun summary(): CourseSummary = CourseSummary(
        id = id, name = name, color = color, sport = sport,
        distanceMetres = distanceMetres, ascentMetres = ascentMetres,
        loadToDevice = loadToDevice, deviceStatus = deviceStatus,
        isExternal = isExternal, bounds = bounds,
    )
}

/**
 * A track to create.
 *
 * `coords` is `[lng, lat, ele?]` triples, matching GeoJSON's axis order rather
 * than the lat-first order the rest of this app speaks — the server takes them
 * straight into a LineString, and flipping them here would put every saved
 * track in the wrong hemisphere.
 */
@Serializable
data class CourseCreate(
    val name: String? = null,
    val color: String? = null,
    val sport: String? = null,
    val source: String? = "builder",
    @SerialName("turn_by_turn") val turnByTurn: Boolean = false,
    @SerialName("load_to_device") val loadToDevice: Boolean = false,
    val coords: List<List<Double>> = emptyList(),
)

@Serializable
data class CourseUpdate(
    val name: String? = null,
    val color: String? = null,
    val sport: String? = null,
    val hidden: Boolean? = null,
    @SerialName("load_to_device") val loadToDevice: Boolean? = null,
)
