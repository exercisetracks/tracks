// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readAvailable
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Lenient on purpose: the server may be newer, and a new field must never
 *  break an installed client. */
val TracksJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * The API contract version this client speaks. Bump alongside the server's
 * `API_VERSION` (backend/app/version.py) — ApiContractTest fails if they differ,
 * because a client left behind is refused at connect time with "too old for
 * the server", which no amount of retrying fixes.
 */
const val CLIENT_API_VERSION: Int = 2

/** See [TracksClient]'s `clientVersion`. */
const val CLIENT_VERSION_HEADER = "X-Tracks-Client"

/**
 * The Tracks HTTP client.
 *
 * Its real job is not making requests — it is keeping the session alive without
 * involving the user. Tracks has two independent expiries (see [TokenStore]),
 * and a naive client that treats every 401 as "log out" would throw the user
 * back to a password screen weekly. This one distinguishes them:
 *
 * - `401 session_expired` — authenticated, vault shut. Unlock with the device
 *   key and retry. Only if there is no device key does the user see anything.
 * - any other 401 — the access token is dead. Refresh it and retry. Only if
 *   the refresh fails is the user logged out.
 *
 * Recovery happens once per request. A second failure is real, and retrying
 * further would just turn a broken session into a request storm.
 */
class TracksClient(
    private val baseUrl: String,
    private val tokens: TokenStore,
    engine: HttpClientEngine? = null,
    private val onSessionState: (SessionState) -> Unit = {},
    /**
     * What this app is, e.g. `android/1.2.0 (10200)`, sent on every request
     * as `X-Tracks-Client`. The server records it against this device's
     * session so the web can list each phone with the version it runs. Purely
     * informational: the server decides nothing from it.
     */
    private val clientVersion: String? = null,
    configure: HttpClientConfig<*>.() -> Unit = {},
) {
    private val http: HttpClient = run {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(TracksJson) }
            // Without this a request that stalls never returns, and Ktor
            // installs no timeouts of its own. That is not a hypothetical: a
            // watch sync of 350 files stopped dead partway through, mid-upload,
            // with the app idle at 0% CPU and no error anywhere — the loop was
            // suspended forever inside one POST. Every file after it stayed on
            // the phone and on the watch, and the user was told nothing.
            //
            // On a tool meant to be carried out of signal range, a request that
            // hangs is the *expected* failure, not an exotic one. The caller
            // already treats a failed upload as "leave it on the watch and try
            // again next time", which is the right answer — it just needs the
            // failure to actually arrive.
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                // Between bytes, not for the whole request: a large activity on
                // a weak link is slow but healthy, and killing it at a fixed
                // deadline would make big files permanently unsyncable while
                // small ones went through. Silence is what indicates death.
                socketTimeoutMillis = 60_000
                // Deliberately generous rather than absent. Some failures stall
                // without the socket ever going quiet, so there has to be an
                // outer bound; ten minutes is far longer than any single file
                // needs and far shorter than forever.
                requestTimeoutMillis = 600_000
            }
            // Non-2xx must not throw: this client inspects status codes to
            // decide how to recover, and an exception would erase the detail
            // string that says which kind of 401 it is.
            expectSuccess = false
            clientVersion?.let { v -> defaultRequest { header(CLIENT_VERSION_HEADER, v) } }
            configure()
        }
        if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    // Serialises recovery. Without it, five requests failing together would
    // each mint a refresh token, and rotation would treat four of them as
    // replay and revoke the family — turning a lapsed session into a forced
    // logout.
    private val recoveryLock = Mutex()

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    // ── Capability negotiation ───────────────────────────────────────────────

    /** Public; safe to call before login. */
    suspend fun capabilities(): Capabilities =
        http.get(url(Endpoints.CAPABILITIES)).body()

    /**
     * This server's version and the newest published release. Also how the
     * server learns this app's version (the header on every request), so the
     * sync loop calls it as well as the settings screen.
     */
    suspend fun versionStatus(): VersionStatus = request { token ->
        http.get(url(Endpoints.VERSION)) { bearer(token) }
    }

    /**
     * Fetch capabilities and throw if this client and the server cannot work
     * together. Call once at startup, before anything else.
     */
    suspend fun requireCompatible(): Capabilities {
        val caps = capabilities()
        val verdict = caps.compatibleWith(CLIENT_API_VERSION)
        if (verdict != Compatibility.OK) throw IncompatibleServerException(verdict, caps)
        return caps
    }

    // ── Authentication ───────────────────────────────────────────────────────

    /**
     * Log in with a password. Asks for a refresh token by default because a
     * native client can store one safely, unlike a browser.
     */
    suspend fun login(username: String, password: String, deviceLabel: String? = null): TokenResponse {
        val resp: HttpResponse = http.post(url(Endpoints.LOGIN)) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password, deviceLabel = deviceLabel))
        }
        if (resp.status != HttpStatusCode.OK) throw NotAuthenticatedException("Login failed")

        val body: TokenResponse = resp.body()
        val existing = tokens.load()
        tokens.save(
            existing.copy(
                accessToken = body.accessToken,
                refreshToken = body.refreshToken ?: existing.refreshToken,
            ),
        )
        onSessionState(SessionState.Active)
        return body
    }

    /**
     * Enrol this device so the vault can be reopened without a password.
     *
     * Requires a live vault, so call it right after a password login. The
     * secret comes back exactly once; persist it before returning.
     */
    suspend fun enrolDeviceKey(label: String? = null): DeviceKeyCreated {
        val resp: HttpResponse = authorized {
            http.post(url(Endpoints.DEVICE_KEYS)) {
                contentType(ContentType.Application.Json)
                setBody(DeviceKeyCreate(label))
                bearer(it)
            }
        }
        if (resp.status != HttpStatusCode.Created) {
            throw VaultLockedException("Device enrolment needs an unlocked vault")
        }
        val created: DeviceKeyCreated = resp.body()
        tokens.save(
            tokens.load().copy(deviceKey = DeviceKeyCredential(created.id, created.deviceSecret)),
        )
        return created
    }

    suspend fun revokeDeviceKey(id: Int) {
        authorized { http.delete(url(Endpoints.deviceKey(id))) { bearer(it) } }
        val stored = tokens.load()
        if (stored.deviceKey?.id == id) tokens.save(stored.copy(deviceKey = null))
    }

    /**
     * Sign out, on the server as well as here.
     *
     * Clearing the stored credentials alone left all of them valid: the access
     * token for up to 30 days, the refresh token for 90, and the device key
     * forever — so a copy taken off the phone before sign-out kept working. The
     * server is told first, the device key revoked and then the session ended
     * (which also revokes the refresh chain on it); the key goes first because
     * revoking it needs the token that logout is about to end.
     *
     * Best-effort and bounded: signing out must work offline and must not hang
     * on a dead server, so any failure here still clears the phone.
     */
    suspend fun logout() {
        val stored = tokens.load()
        val access = stored.accessToken
        if (access != null) {
            // Separately: a key the server already forgot must not stop the
            // session being ended.
            stored.deviceKey?.let { key ->
                runCatching {
                    http.delete(url(Endpoints.deviceKey(key.id))) { bearer(access); signOutTimeout() }
                }
            }
            runCatching { http.post(url(Endpoints.LOGOUT)) { bearer(access); signOutTimeout() } }
        }
        tokens.clear()
        onSessionState(SessionState.LoggedOut)
    }

    /**
     * Change the account's password from this phone.
     *
     * The server answers "I think I am compromised" by ending everything else:
     * every access token issued before now, every refresh token, every device
     * key — this phone's included. What it hands back is a replacement access
     * token and refresh token on this same session, so the vault stays open,
     * and this phone then enrols a fresh device key if it had one, so it keeps
     * unlocking on its own. If that enrolment fails the change has still
     * happened; the phone just asks for the password next time the vault shuts.
     *
     * Throws [WrongPasswordException] when the current password is wrong.
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
        deviceLabel: String? = null,
    ): PasswordChangeResult {
        val resp = authorized {
            http.post(url(Endpoints.CHANGE_PASSWORD)) {
                contentType(ContentType.Application.Json)
                setBody(PasswordChangeRequest(currentPassword, newPassword, deviceLabel = deviceLabel))
                bearer(it)
            }
        }
        when {
            resp.status == HttpStatusCode.Forbidden -> throw WrongPasswordException()
            resp.status == HttpStatusCode.Unauthorized -> {
                if (resp.isSessionExpired()) throw VaultLockedException()
                throw NotAuthenticatedException()
            }
            !resp.status.isSuccess() -> throw ServerErrorException(resp.status.value, errorDetail(resp))
        }
        val body: PasswordChangeResponse = resp.body()
        val hadDeviceKey = tokens.load().deviceKey != null
        tokens.save(
            tokens.load().copy(
                accessToken = body.accessToken,
                // The old one is revoked; keeping it would only fail later.
                refreshToken = body.refreshToken,
                deviceKey = null,
            ),
        )
        val reEnrolled = hadDeviceKey && runCatching { enrolDeviceKey(deviceLabel) }.isSuccess
        return PasswordChangeResult(
            // This phone's own key and refresh chain are among those counted.
            otherDevicesSignedOut = maxOf(body.revokedDeviceKeys - (if (hadDeviceKey) 1 else 0), 0),
            deviceKeyReEnrolled = reEnrolled,
        )
    }

    // ── Data ─────────────────────────────────────────────────────────────────

    /** Who is signed in. Used to bind this phone's data to one account. */
    suspend fun currentUser(): CurrentUser = request { token ->
        http.get(url(Endpoints.USER_ME)) { bearer(token) }
    }

    // ── Replica sync (spec/sync.yaml, "Wire") ───────────────────────────────
    //
    // Raw JSON in and out: the encoding belongs to com.tracks.core.replica.Wire,
    // which owns the [value, stamp] pairs no data class models well. These only
    // add what every call here needs — the session recovery — and refuse to
    // hand an error body to a decoder that would misread it as an empty page.

    suspend fun syncPush(body: JsonObject): JsonObject = syncCall { token ->
        http.post(url(Endpoints.SYNC_PUSH)) {
            contentType(ContentType.Application.Json)
            setBody(body)
            bearer(token)
        }
    }

    suspend fun syncPull(since: Long, limit: Int): JsonObject = syncCall { token ->
        http.get(url(Endpoints.SYNC_PULL)) {
            bearer(token)
            parameter("since", since)
            parameter("limit", limit)
        }
    }

    suspend fun syncBlob(sha256: String): ByteArray = syncCall { token ->
        http.get(url(Endpoints.syncBlob(sha256))) { bearer(token) }
    }

    private suspend inline fun <reified T> syncCall(noinline block: suspend (String) -> HttpResponse): T {
        val resp = authorized(block)
        when {
            resp.status == HttpStatusCode.Unauthorized -> {
                if (resp.isSessionExpired()) throw VaultLockedException()
                throw NotAuthenticatedException()
            }
            resp.status.value !in 200..299 ->
                throw SyncHttpException(resp.status.value, resp.bodyAsText().take(200))
        }
        return resp.body()
    }

    /** `maxPoints` is not optional in practice — the server-side default can be
     *  uncapped, and a long ride will happily send 40k samples to a phone. */
    suspend fun activityTrack(activityId: Int, maxPoints: Int = 2000): List<TrackPoint> =
        request { token ->
            http.get(url(Endpoints.activityTrack(activityId))) {
                bearer(token)
                parameter("max_points", maxPoints)
            }
        }

    /**
     * The full record behind one activity.
     *
     * Needs only the token, not an open vault — the detail row carries no
     * encrypted columns. Its GPS does, which is why [activityTrack] is a
     * separate call the detail screen can fail independently: a locked vault
     * should cost the user their map, not the whole screen.
     */
    suspend fun activityDetail(activityId: Int): ActivityDetail = request { token ->
        http.get(url(Endpoints.activityDetail(activityId))) { bearer(token) }
    }

    /**
     * Rename an activity, or correct its sport.
     *
     * Every field is optional and only the ones set are written — the server
     * treats null as "leave it alone" rather than "clear it", which is what
     * lets a rename be a rename and not an accidental wipe of the notes.
     *
     * Changing the sport is not cosmetic on the server: it recomputes the
     * metrics that depend on it. The phone only ever sends a name.
     */
    suspend fun updateActivity(activityId: Int, update: ActivityUpdate): ActivityDetail =
        request { token ->
            http.patch(url(Endpoints.activityDetail(activityId))) {
                contentType(ContentType.Application.Json)
                setBody(update)
                bearer(token)
            }
        }

    suspend fun activityLaps(activityId: Int): List<Lap> = request { token ->
        http.get(url(Endpoints.activityLaps(activityId))) { bearer(token) }
    }

    /** Always pass a viewport: unbounded, this is the user's entire GPS history. */
    suspend fun heatmap(
        bbox: BoundingBox? = null,
        zoom: Double? = null,
        mode: String = "frequency",
        sport: String? = null,
    ): List<List<List<Double>>> = request { token ->
        http.get(url(Endpoints.HEATMAP)) {
            bearer(token)
            parameter("mode", mode)
            sport?.let { parameter("sport", it) }
            bbox?.let { parameter("bbox", it.asParameter()) }
            zoom?.let { parameter("zoom", it) }
        }
    }

    /**
     * Every track as a GeoJSON FeatureCollection, returned as raw text.
     *
     * Not decoded. MapLibre's `GeoJsonSource` parses GeoJSON itself, so
     * modelling it here would mean decoding a document only to re-encode it —
     * and this one is large. Unlike `/activities/heatmap`, the server emits
     * proper `[lng, lat]` order here, so it needs no correction on the way in.
     */
    suspend fun tracksGeoJson(sport: String? = null, limit: Int = 400): String =
        authorized { token ->
            http.get(url(Endpoints.TRACKS_GEOJSON)) {
                bearer(token)
                sport?.let { parameter("sport", it) }
                parameter("limit", limit)
            }
        }.bodyAsText()

    /**
     * The map style both clients render from. `baseUrl` is required for native
     * clients — MapLibre Native has no page origin to resolve `/api` against.
     */
    /**
     * The absolute style URL, for callers that need a URL rather than a body.
     *
     * MapLibre's offline downloader takes a style *URL* and re-fetches it and
     * everything it references itself — it cannot be handed a document the way
     * the live map is. Built here so the `base_url` rule (scheme + host, no
     * path) lives in one place; see [origin].
     */
    fun mapStyleUrl(): String = url(Endpoints.MAP_STYLE) + "?base_url=" + origin()

    /**
     * The USGS symbol sheet the map draws its point icons from.
     *
     * Fetched rather than bundled so the legend cannot drift from the map: both
     * read the same sheet, so a symbol that changes on one changes on the
     * other. [spriteManifest] gives the name → rectangle table; [spriteSheet]
     * is the PNG those rectangles index into.
     */
    suspend fun spriteManifest(): String =
        http.get(url("/sprite/usgs.json")).bodyAsText()

    suspend fun spriteSheet(): ByteArray =
        http.get(url("/sprite/usgs.png")).body()

    suspend fun mapStyleJson(): String =
        http.get(url(Endpoints.MAP_STYLE)) { parameter("base_url", origin()) }.bodyAsText()

    // ── Dashboard ────────────────────────────────────────────────────────────
    // `after` is an ISO date or null for all-time. Null is not merely the
    // absence of a filter here: the server serves the unfiltered request from
    // a pre-warmed per-user cache and recomputes for any other window, so
    // passing an `after` that happens to cover everything is markedly slower
    // than passing none. Callers should send null when they mean lifetime.

    suspend fun metricsSummary(after: String? = null): MetricsSummary = request { token ->
        http.get(url(Endpoints.METRICS_SUMMARY)) { bearer(token); after?.let { parameter("after", it) } }
    }

    suspend fun metricsBySport(after: String? = null): List<SportBreakdown> = request { token ->
        http.get(url(Endpoints.METRICS_BY_SPORT)) { bearer(token); after?.let { parameter("after", it) } }
    }

    suspend fun activityCalendar(after: String? = null, sport: String? = null): List<ActivityCalendarPoint> =
        request { token ->
            http.get(url(Endpoints.METRICS_ACTIVITY_CALENDAR)) {
                bearer(token)
                after?.let { parameter("after", it) }
                sport?.let { parameter("sport", it) }
            }
        }

    suspend fun vo2maxHistory(after: String? = null): List<Vo2MaxPoint> = request { token ->
        http.get(url(Endpoints.METRICS_VO2MAX_HISTORY)) { bearer(token); after?.let { parameter("after", it) } }
    }

    suspend fun weeklyVolume(after: String? = null, sport: String? = null): List<WeeklyVolumePoint> =
        request { token ->
            http.get(url(Endpoints.METRICS_WEEKLY_VOLUME)) {
                bearer(token)
                after?.let { parameter("after", it) }
                sport?.let { parameter("sport", it) }
            }
        }

    /**
     * The whole CTL/ATL/TSB series, unfiltered.
     *
     * Deliberately never date-filtered, and the display window is applied on the
     * client. CTL is an exponentially weighted average with a 42-day time
     * constant, so a series that begins at the window's start begins at zero and
     * ramps — showing the user a fitness collapse that did not happen. The web
     * app fetches all-time once for the same reason and filters in the browser.
     */
    suspend fun trainingLoad(): List<TrainingLoadPoint> = request { token ->
        http.get(url(Endpoints.METRICS_TRAINING_LOAD)) { bearer(token) }
    }

    /** Windowed by day count, not by date; the server caps at 365. */
    suspend fun readinessHistory(days: Int = 30): List<ReadinessHistoryPoint> = request { token ->
        http.get(url(Endpoints.METRICS_READINESS_HISTORY)) {
            bearer(token)
            parameter("days", days.coerceIn(1, 365))
        }
    }

    suspend fun coachingToday(): DailyCoaching = request { token ->
        http.get(url(Endpoints.COACHING_TODAY)) { bearer(token) }
    }

    /**
     * Planned workouts inside a window.
     *
     * [limit] defaults to the endpoint's own default of 10, which suits a
     * widget and not a mirror: a client caching the plan to build watch files
     * from it must ask for enough to cover [days], or it will quietly build a
     * calendar out of a truncated plan.
     */
    suspend fun upcomingWorkouts(days: Int = 14, limit: Int = 10): List<PlannedWorkout> =
        request { token ->
            http.get(url(Endpoints.UPCOMING_WORKOUTS)) {
                bearer(token)
                parameter("days", days)
                parameter("limit", limit)
            }
        }

    suspend fun activityClimbs(activityId: Int): List<ClimbSplit> = request { token ->
        http.get(url(Endpoints.activityClimbs(activityId))) { bearer(token) }
    }

    suspend fun activitySets(activityId: Int): List<StrengthSet> = request { token ->
        http.get(url(Endpoints.activitySets(activityId))) { bearer(token) }
    }

    /**
     * One night's sleep stages.
     *
     * Answers with an empty list rather than a 404 for a night the server has
     * totals for but no timeline — every night imported before the server began
     * keeping one — so a client draws the summary instead of an error.
     */
    suspend fun sleepNight(date: String): SleepNight = request { token ->
        http.get(url(Endpoints.sleepNight(date))) { bearer(token) }
    }

    /**
     * Stress reading by reading, for every day in the window that has any.
     *
     * Days with no series are simply absent from the answer rather than
     * present and empty: a day the watch never sampled and a day it sampled as
     * flat calm are different days, and every day recorded before the parser
     * kept the series is the former.
     *
     * The server caps how far back it will go — this is the curve, and over a
     * year the curve is a solid block of ink. Long windows read the daily
     * averages already on [DailyMetricFull] instead.
     */
    suspend fun stressDetail(after: String? = null, before: String? = null): List<StressDay> =
        request { token ->
            http.get(url(Endpoints.STRESS_DETAIL)) {
                bearer(token)
                after?.let { parameter("after", it) }
                before?.let { parameter("before", it) }
            }
        }

    /**
     * The account's preferences.
     *
     * Read on every start rather than mirrored as a document: it is one small
     * object, and the alternative — a phone showing last week's units because
     * the cache had not refreshed — is a worse failure than a screen that keeps
     * its previous answer for a moment.
     */
    suspend fun userSettings(): UserSettings = request { token ->
        http.get(url(Endpoints.USER_SETTINGS)) { bearer(token) }
    }

    suspend fun updateUserSettings(update: UserSettingsUpdate): UserSettings = request { token ->
        http.patch(url(Endpoints.USER_SETTINGS)) {
            contentType(ContentType.Application.Json)
            setBody(update)
            bearer(token)
        }
    }

    /**
     * A partial update of text fields, for those where `null` means "clear
     * it" — turning AI coaching off sends `ai_provider: null`, which
     * [UserSettingsUpdate] would drop as unset. Plain strings in, so `:app`
     * needs no JSON types.
     */
    suspend fun patchUserSettings(fields: Map<String, String?>): UserSettings = request { token ->
        http.patch(url(Endpoints.USER_SETTINGS)) {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(fields.mapValues { (_, v) -> JsonPrimitive(v) }))
            bearer(token)
        }
    }

    /** Body data by date range. Both bounds are ISO dates; omit for all-time. */
    suspend fun dailyMetrics(after: String? = null, before: String? = null): List<DailyMetricFull> =
        request { token ->
            http.get(url(Endpoints.DAILY_METRICS)) {
                bearer(token)
                after?.let { parameter("after", it) }
                before?.let { parameter("before", it) }
            }
        }

    /**
     * The account's training goals.
     *
     * Cached, unlike [userSettings], because the watch push needs the live
     * goal's name and end date with no network — see
     * `com.tracks.core.sync.CoachingContext`.
     */
    suspend fun goals(): List<TrainingGoal> = request { token ->
        http.get(url(Endpoints.GOALS)) { bearer(token) }
    }

    /**
     * A goal's generated plan.
     *
     * 404s when the goal has no plan yet, which is an ordinary state rather
     * than an error — a goal exists before anything is generated from it.
     */
    suspend fun goalPlan(goalId: Int): TrainingPlan = request { token ->
        http.get(url(Endpoints.goalPlan(goalId))) { bearer(token) }
    }

    /**
     * Build (or rebuild) a goal's plan.
     *
     * Stays a server call. The periodisation engine is thousands of lines of
     * sport-specific programming and it is generated once per goal, not once
     * per sync — so unlike the FIT files a plan is made from, there is nothing
     * to gain from doing it on a phone with no signal.
     */
    suspend fun generatePlan(goalId: Int): TrainingPlan = request { token ->
        http.post(url(Endpoints.goalPlanGenerate(goalId))) { bearer(token) }
    }

    /** The 7-day outlook: projected CTL/ATL/TSB with a recommendation per day. */
    suspend fun weeklyPlan(): List<PlanDay> = request { token ->
        http.get(url(Endpoints.WEEKLY_PLAN)) { bearer(token) }
    }

    /** The account's permanent calendar feed, created on first ask — what the web's "Copy subscription link" copies. */
    suspend fun userIcsToken(): UserIcsToken = request { token ->
        http.get(url(Endpoints.USER_ICS_TOKEN)) { bearer(token) }
    }

    /** The user's saved workouts. */
    suspend fun savedWorkouts(): List<UserWorkout> = request { token ->
        http.get(url(Endpoints.WORKOUTS)) { bearer(token) }
    }

    /**
     * Save a new one.
     *
     * Names are unique per user server-side — a duplicate is refused with a
     * 409 rather than silently making a second workout with the same name.
     */
    suspend fun createSavedWorkout(workout: UserWorkoutIn): UserWorkout = request { token ->
        http.post(url(Endpoints.WORKOUTS)) {
            contentType(ContentType.Application.Json)
            setBody(workout)
            bearer(token)
        }
    }

    /**
     * Replace one.
     *
     * A PUT rather than a patch, and the exercise list is replaced wholesale —
     * that is the server's own contract, and it is the right one for a list
     * whose order is part of its meaning.
     */
    suspend fun updateSavedWorkout(workoutId: Int, workout: UserWorkoutIn): UserWorkout =
        request { token ->
            http.put(url(Endpoints.workout(workoutId))) {
                contentType(ContentType.Application.Json)
                setBody(workout)
                bearer(token)
            }
        }

    suspend fun deleteSavedWorkout(workoutId: Int): Unit = request { token ->
        http.delete(url(Endpoints.workout(workoutId))) { bearer(token) }
    }

    /**
     * Add a workout to the calendar that no plan prescribed.
     *
     * The counterpart to plan generation rather than a replacement for it: a
     * generated plan is still the spine, and this is the session somebody
     * decides on themselves.
     */
    suspend fun createPlannedWorkout(workout: PlannedWorkoutCreate): PlannedWorkout =
        request { token ->
            http.post(url(Endpoints.PLAN_WORKOUTS)) {
                contentType(ContentType.Application.Json)
                setBody(workout)
                bearer(token)
            }
        }

    /** Remove a planned workout, generated or added by hand. */
    suspend fun deletePlannedWorkout(workoutId: Int): Unit = request { token ->
        http.delete(url(Endpoints.planWorkout(workoutId))) { bearer(token) }
    }

    /**
     * Patch a planned workout — in practice, tick it off.
     *
     * Marking a workout complete is the one plan edit that belongs on a phone:
     * it happens right after the session, which is exactly when the laptop is
     * elsewhere. Everything else about a plan — generating it, moving days,
     * changing a goal — stays on the web, where there is room to see the
     * consequences.
     */
    suspend fun updatePlannedWorkout(
        workoutId: Int,
        update: PlannedWorkoutUpdate,
    ): PlannedWorkout = request { token ->
        http.patch(url(Endpoints.planWorkout(workoutId))) {
            contentType(ContentType.Application.Json)
            setBody(update)
            bearer(token)
        }
    }

    // ── Training library ─────────────────────────────────────────────────────
    //
    // Strength and flexibility, which the phone previously had no access to at
    // all. These are the data a guided session is assembled from, and they are
    // read-mostly: the library is shared with the browser and the server merges
    // the user's own additions into it, so there is one list rather than a
    // built-in one and a custom one for every screen to join.
    //
    // The stretch library filters server-side; the exercise library does not
    // take filters at all and returns the lot. That asymmetry is the server's,
    // not an oversight here — and the whole exercise library is a few hundred
    // rows, so the phone narrows it itself. Which is the better shape anyway:
    // filtering a list already in hand costs no round trip and works in a gym
    // with no signal.

    /**
     * Every exercise: the shared library plus the user's own, each annotated
     * with their preference for it.
     */
    suspend fun exercises(): List<Exercise> = request { token ->
        http.get(url(Endpoints.STRENGTH_EXERCISES)) { bearer(token) }
    }

    suspend fun customExercises(): List<Exercise> = request { token ->
        http.get(url(Endpoints.STRENGTH_CUSTOM_EXERCISES)) { bearer(token) }
    }

    suspend fun stretches(
        muscle: String? = null,
        search: String? = null,
        difficulty: Int? = null,
    ): List<Stretch> = request { token ->
        http.get(url(Endpoints.STRETCHES)) {
            bearer(token)
            muscle?.let { parameter("muscle", it) }
            search?.let { parameter("search", it) }
            difficulty?.let { parameter("difficulty", it) }
        }
    }

    suspend fun customStretches(): List<Stretch> = request { token ->
        http.get(url(Endpoints.CUSTOM_STRETCHES)) { bearer(token) }
    }

    /**
     * The user's saved mobility routines.
     *
     * Each flow names its stretches rather than embedding them, so a flow is
     * only runnable alongside the library it draws from — see [FlowStretch].
     */
    /**
     * Save a flow.
     *
     * The stretches go in running order and the server numbers them from the
     * list, so there is no index to keep in step.
     */
    suspend fun createFlow(flow: FlexibilityFlowIn): FlexibilityFlow = request { token ->
        http.post(url(Endpoints.FLOWS)) {
            contentType(ContentType.Application.Json)
            setBody(flow)
            bearer(token)
        }
    }

    /** Replace one, stretches and all. */
    suspend fun updateFlow(flowId: Int, flow: FlexibilityFlowIn): FlexibilityFlow = request { token ->
        http.put(url(Endpoints.flow(flowId))) {
            contentType(ContentType.Application.Json)
            setBody(flow)
            bearer(token)
        }
    }

    suspend fun deleteFlow(flowId: Int): Unit = request { token ->
        http.delete(url(Endpoints.flow(flowId))) { bearer(token) }
    }

    suspend fun flows(): List<FlexibilityFlow> = request { token ->
        http.get(url(Endpoints.FLOWS)) { bearer(token) }
    }

    /** What the user has to train with — the library's own filter, really. */
    suspend fun equipment(): List<String> = request<EquipmentList> { token ->
        http.get(url(Endpoints.STRENGTH_EQUIPMENT)) { bearer(token) }
    }.equipment

    suspend fun setEquipment(equipment: List<String>): Unit = request { token ->
        http.put(url(Endpoints.STRENGTH_EQUIPMENT)) {
            contentType(ContentType.Application.Json)
            setBody(EquipmentList(equipment))
            bearer(token)
        }
    }

    /**
     * Sets already done, newest first.
     *
     * A guided session opens on what happened last time rather than an empty
     * weight field, which is the difference between logging a workout and being
     * told what to lift.
     */
    suspend fun strengthHistory(
        exercise: String? = null,
        limit: Int = 200,
    ): List<StrengthHistoryEntry> = request { token ->
        http.get(url(Endpoints.STRENGTH_HISTORY)) {
            bearer(token)
            exercise?.let { parameter("exercise", it) }
            parameter("limit", limit.coerceIn(1, 1000))
        }
    }

    /**
     * Where each lift stands, one row per exercise the user has ever done.
     *
     * Sparse by nature: the library has hundreds of exercises and this returns
     * only the ones with history, so a screen joining the two has to treat a
     * missing row as "never done" rather than as an error.
     */
    suspend fun strengthProgress(): List<StrengthProgress> = request { token ->
        http.get(url(Endpoints.STRENGTH_PROGRESS)) { bearer(token) }
    }

    /**
     * Log a finished session.
     *
     * The one write in the training API, and it does more than record: the
     * server folds each exercise's best set into that lift's estimated max,
     * counts the session towards its progression stage, and — when the session
     * completes a planned workout — ticks that workout off the calendar.
     *
     * It refuses a second log of the same planned workout with a 409, which is
     * the right answer and worth surfacing as "already logged" rather than as a
     * failure the user should retry.
     */
    suspend fun logWorkoutSession(session: WorkoutSessionIn): WorkoutSessionOut = request { token ->
        http.post(url(Endpoints.WORKOUT_SESSIONS)) {
            contentType(ContentType.Application.Json)
            setBody(session)
            bearer(token)
        }
    }

    /**
     * Tell the server a one-rep max rather than let it infer one.
     *
     * The server estimates from history; this is for the case where the user
     * knows better — a tested max, or a lift they have not done here yet.
     */
    suspend fun setOneRepMax(
        exerciseName: String,
        override: OneRepMaxOverride,
    ): Unit = request { token ->
        http.put(url(Endpoints.strengthOneRm(exerciseName))) {
            contentType(ContentType.Application.Json)
            setBody(override)
            bearer(token)
        }
    }

    // ── Health: the entered half ─────────────────────────────────────────────
    //
    // Injuries, medications and meals. Unlike everything above, these are
    // read-*write*: the phone is often the only device present when a dose is
    // taken or a meal is eaten, so a client that could only display them would
    // be the wrong way round.

    suspend fun injuries(): List<Injury> = request { token ->
        http.get(url(Endpoints.INJURIES)) { bearer(token) }
    }

    // ── Courses and waypoints ────────────────────────────────────────────────
    //
    // Both carry a `load_to_device` flag that the watch sync reads: setting it
    // does not push anything by itself, it puts the item on the next sync's
    // list. That indirection is deliberate — the phone may not be near the
    // watch when somebody decides they want a route on it.

    suspend fun courses(): List<CourseSummary> = request { token ->
        http.get(url(Endpoints.COURSES)) { bearer(token) }
    }

    suspend fun updateCourse(trackId: Int, update: CourseUpdate): CourseSummary =
        request { token ->
            http.patch(url(Endpoints.course(trackId))) {
                contentType(ContentType.Application.Json)
                setBody(update)
                bearer(token)
            }
        }

    /**
     * Every visible track as one GeoJSON document, kept as text.
     *
     * Not decoded into models on the way past: this exists to be handed
     * straight to a map source, and parsing it into Kotlin only to re-serialise
     * it would cost two passes over the geometry to arrive back where it
     * started. [courses] is the typed view, for lists.
     */
    suspend fun coursesGeoJson(): String = request { token ->
        http.get(url(Endpoints.COURSES_GEOJSON)) { bearer(token) }
    }

    /** One track, with the geometry-derived extras a list omits. */
    suspend fun course(trackId: Int): CourseDetail = request { token ->
        http.get(url(Endpoints.course(trackId))) { bearer(token) }
    }

    /**
     * Import a GPX or FIT someone sent you.
     *
     * Multipart because that is what the endpoint the web app already uses
     * speaks, and adding a second base64 route for the same job would mean two
     * parsers to keep agreeing. The server decides the format from the
     * filename, so it is sent rather than invented.
     */
    suspend fun importCourse(filename: String, bytes: ByteArray): CourseDetail =
        request { token ->
            http.post(url(Endpoints.COURSES_IMPORT)) {
                bearer(token)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file", bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, "application/octet-stream")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"$filename\"",
                                    )
                                },
                            )
                        }
                    )
                )
            }
        }

    suspend fun createCourse(course: CourseCreate): CourseSummary = request { token ->
        http.post(url(Endpoints.COURSES)) {
            contentType(ContentType.Application.Json)
            setBody(course)
            bearer(token)
        }
    }

    /**
     * Remove a track.
     *
     * The server decides what that means: a track the watch is holding keeps
     * its row so the next sync can take the file off the device, and only then
     * is it really gone. So a 204 here means "accepted", not "erased".
     */
    suspend fun deleteCourse(trackId: Int) {
        request<Unit> { token -> http.delete(url(Endpoints.course(trackId))) { bearer(token) } }
    }

    suspend fun waypoints(): List<Waypoint> = request { token ->
        http.get(url(Endpoints.WAYPOINTS)) { bearer(token) }
    }

    suspend fun createWaypoint(waypoint: WaypointIn): Waypoint = request { token ->
        http.post(url(Endpoints.WAYPOINTS)) {
            contentType(ContentType.Application.Json)
            setBody(waypoint)
            bearer(token)
        }
    }

    suspend fun updateWaypoint(waypointId: Int, update: WaypointUpdate): Waypoint =
        request { token ->
            http.patch(url(Endpoints.waypoint(waypointId))) {
                contentType(ContentType.Application.Json)
                setBody(update)
                bearer(token)
            }
        }

    suspend fun deleteWaypoint(waypointId: Int) {
        request<Unit> { token -> http.delete(url(Endpoints.waypoint(waypointId))) { bearer(token) } }
    }

    /** What was happening around an injury — a week either side, server-picked. */
    suspend fun injuryActivities(injuryId: Int): List<InjuryActivity> = request { token ->
        http.get(url(Endpoints.injuryActivities(injuryId))) { bearer(token) }
    }

    suspend fun createInjury(injury: InjuryCreate): Injury = request { token ->
        http.post(url(Endpoints.INJURIES)) {
            contentType(ContentType.Application.Json)
            setBody(injury)
            bearer(token)
        }
    }

    /**
     * Patch an injury. Unset fields are left alone rather than cleared —
     * `explicitNulls = false` keeps them out of the body entirely, which is what
     * makes [healInjury] a one-field call instead of a read-modify-write.
     */
    suspend fun updateInjury(injuryId: Int, update: InjuryUpdate): Injury = request { token ->
        http.patch(url(Endpoints.injury(injuryId))) {
            contentType(ContentType.Application.Json)
            setBody(update)
            bearer(token)
        }
    }

    /** Mark an injury healed as of [date]. The end date *is* the healed flag. */
    suspend fun healInjury(injuryId: Int, date: String): Injury =
        updateInjury(injuryId, InjuryUpdate(endDate = date))

    suspend fun deleteInjury(injuryId: Int): Unit = request { token ->
        http.delete(url(Endpoints.injury(injuryId))) { bearer(token) }
    }

    /**
     * Ask the server to re-read its retained files for the daily metrics.
     *
     * Returns as soon as the work is queued — it is hundreds of sealed files to
     * unseal and parse, and holding a phone's request open for that would time
     * out long before it finished. The new figures appear on the next load.
     */
    suspend fun reparseDailyMetrics(): Unit = request { token ->
        http.post(url(Endpoints.HEALTH_DAILY_REPARSE)) { bearer(token) }
    }

    suspend fun medications(): List<Medication> = request { token ->
        http.get(url(Endpoints.MEDICATIONS)) { bearer(token) }
    }

    /**
     * Add a medication and its schedule.
     *
     * Unlike a dose, this is *setup* rather than a record of something that
     * happened — but it still goes through the outbox, because the phone is
     * where someone stands when a prescription changes and "you cannot add this
     * until you have signal" is not an answer a health app gets to give.
     */
    suspend fun createMedication(medication: MedicationIn): Medication = request { token ->
        http.post(url(Endpoints.MEDICATIONS)) {
            contentType(ContentType.Application.Json)
            setBody(medication)
            bearer(token)
        }
    }

    /**
     * Replace a medication, schedules included.
     *
     * The server's PATCH deletes and rebuilds the schedule rows rather than
     * merging them, so this takes the whole medication. Sending a partial one
     * would silently drop every schedule left out of it.
     */
    suspend fun updateMedication(medicationId: Int, medication: MedicationIn): Medication =
        request { token ->
            http.patch(url(Endpoints.medication(medicationId))) {
                contentType(ContentType.Application.Json)
                setBody(medication)
                bearer(token)
            }
        }

    suspend fun deleteMedication(medicationId: Int): Unit = request { token ->
        http.delete(url(Endpoints.medication(medicationId))) { bearer(token) }
    }

    /**
     * Doses recorded, newest first as the server orders them.
     *
     * A window in *days*, which is the parameter the endpoint actually reads.
     * This used to send `after=<iso date>`, which the server ignores — so the
     * phone asked for a fortnight and silently got whatever the default was.
     */
    suspend fun medicationLog(days: Int = 30): List<MedicationLog> = request { token ->
        http.get(url(Endpoints.MEDICATION_LOG)) {
            bearer(token)
            parameter("days", days)
        }
    }

    suspend fun logMedication(entry: MedicationLogCreate): MedicationLog = request { token ->
        http.post(url(Endpoints.MEDICATION_LOG)) {
            contentType(ContentType.Application.Json)
            setBody(entry)
            bearer(token)
        }
    }

    /** The user's saved meals — templates, not things eaten. */
    suspend fun meals(): List<Meal> = request { token ->
        http.get(url(Endpoints.MEALS)) { bearer(token) }
    }

    /** Same story as [medicationLog]: the endpoint takes days, not a date. */
    suspend fun mealLog(days: Int = 14): List<MealLog> = request { token ->
        http.get(url(Endpoints.MEAL_LOG)) {
            bearer(token)
            parameter("days", days)
        }
    }

    suspend fun logMeal(entry: MealLogCreate): MealLog = request { token ->
        http.post(url(Endpoints.MEAL_LOG)) {
            contentType(ContentType.Application.Json)
            setBody(entry)
            bearer(token)
        }
    }

    suspend fun deleteMealLog(entryId: Int): Unit = request { token ->
        http.delete(url(Endpoints.mealLogEntry(entryId))) { bearer(token) }
    }

    /**
     * Correct a day's figures.
     *
     * Only the three the watch cannot know — weight, hydration, calories in.
     * Everything else on a day is a measurement, and letting a phone overwrite
     * one would make the record disagree with the device that took it.
     */
    suspend fun patchDailyMetric(date: String, patch: DailyMetricPatch): Unit = request { token ->
        http.patch(url(Endpoints.healthDaily(date))) {
            contentType(ContentType.Application.Json)
            setBody(patch)
            bearer(token)
        }
    }

    // ── Watch sync ───────────────────────────────────────────────────────────

    /**
     * Everything the server owes the watch.
     *
     * [bluetooth] tells the server this link can write files but not delete
     * them, which changes one answer: unloading every saved place comes back as
     * an empty locations file to write rather than as a deletion this transport
     * could not carry out.
     */
    suspend fun pushList(bluetooth: Boolean = false): PushList = request { token ->
        http.get(url(Endpoints.PUSH_LIST)) {
            bearer(token)
            if (bluetooth) parameter("bluetooth", true)
        }
    }

    suspend fun markUploaded(items: List<MarkItem>): Unit = request { token ->
        http.post(url(Endpoints.MARK_UPLOADED)) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(MarkUploadedRequest(items))
        }
    }

    /** See [Endpoints.SCHEDULE_FIT] for why this is fetched after marking. */
    suspend fun scheduleFit(): ScheduleFit = request { token ->
        http.get(url(Endpoints.SCHEDULE_FIT)) { bearer(token) }
    }

    /**
     * The schedule and its workouts together, needing no prior marking.
     *
     * Preferred over [scheduleFit] on BLE: the watch will not build a calendar
     * from a schedule whose workouts arrived in an earlier session.
     */
    suspend fun scheduleBundle(): ScheduleBundle = request { token ->
        http.get(url(Endpoints.SCHEDULE_BUNDLE)) { bearer(token) }
    }

    /**
     * The server's own forecast for the watch, located at the newest activity
     * with GPS *it* holds.
     *
     * Only the fallback: the phone normally asks Open-Meteo itself, and calls
     * this when none of its own files has a position — an account whose
     * outdoor activities reached the server from somewhere else. The server
     * 404s when it has no such activity either, and 403s when Weather is off.
     */
    suspend fun watchWeather(): WatchWeather = request { token ->
        http.get(url(Endpoints.WATCH_WEATHER)) { bearer(token) }
    }

    // ── Offline map regions ──────────────────────────────────────────────
    //
    // The phone never extracts tiles itself; it asks the server for an area and
    // watches the pipeline. Both clients read one registry, so a region
    // downloaded in the browser is already available here.

    suspend fun mapRegions(): List<MapRegion> = request { token ->
        http.get(url(Endpoints.REGIONS)) { bearer(token) }
    }

    suspend fun mapRegion(id: Int): MapRegion = request { token ->
        http.get(url(Endpoints.region(id))) { bearer(token) }
    }

    /**
     * How big a download would be, before asking for it.
     *
     * Worth its own round trip: the answer ranges from tens of megabytes to
     * several gigabytes depending on the terrain, and on a phone that is the
     * difference between "go ahead" and "wait for wifi".
     */
    suspend fun regionEstimate(bbox: String): RegionEstimate = request { token ->
        http.get(url(Endpoints.REGION_ESTIMATE)) {
            bearer(token)
            parameter("bbox", bbox)
        }
    }

    /** The server's geocoded name for an area, so nobody has to type one. */
    suspend fun suggestRegionName(bbox: String): RegionNameSuggestion = request { token ->
        http.get(url(Endpoints.REGION_SUGGEST_NAME)) {
            bearer(token)
            parameter("bbox", bbox)
        }
    }

    /**
     * Start a download. Returns as soon as the pipeline is launched, not when
     * it finishes — the work is minutes long, so progress is polled.
     */
    suspend fun downloadRegion(bbox: String, name: String): RegionDownloadStarted =
        request { token ->
            http.post(url(Endpoints.REGION_DOWNLOAD)) {
                bearer(token)
                contentType(ContentType.Application.Json)
                setBody(RegionDownloadRequest(bbox = bbox, name = name))
            }
        }

    /**
     * Cancel or remove a region. The server treats both as the same request:
     * an in-flight build is aborted and cleaned up, a finished one is purged.
     */
    suspend fun deleteRegion(id: Int): Unit = request { token ->
        http.delete(url(Endpoints.region(id))) { bearer(token) }
    }

    // ── Inspecting the map ───────────────────────────────────────────────

    /**
     * Elevation and the nearest named place at a point.
     *
     * [withWeather] off is the phone's normal case: the sheet opens on what the
     * server can answer locally and asks Open-Meteo directly for the forecast
     * (see [com.tracks.core.weather.OpenMeteo]), so the panel is on screen
     * while the forecast is still in flight.
     */
    suspend fun pointInfo(
        lat: Double,
        lon: Double,
        withWeather: Boolean = true,
    ): PointInfo = request { token ->
        http.get(url(Endpoints.MAP_POINT)) {
            bearer(token)
            parameter("lat", lat)
            parameter("lon", lon)
            if (!withWeather) parameter("weather", false)
        }
    }

    /**
     * Ground heights under [coordinates], `[lng, lat]` as GeoJSON orders them.
     *
     * For lines the router did not draw. A snapped route already carries
     * elevation in its third ordinate, so asking again would be a round trip
     * for something in hand — and could disagree with the route that was
     * planned.
     */
    suspend fun routeElevation(coordinates: List<List<Double>>): List<Double?> {
        val body: SampledElevations = request { token ->
            http.post(url(Endpoints.ROUTE_ELEVATION)) {
                bearer(token)
                contentType(ContentType.Application.Json)
                setBody(ElevationRequest(coordinates))
            }
        }
        return body.elevations
    }

    /**
     * Search the server's gazetteer for a place.
     *
     * [lat] and [lon] bias results toward where the user is looking rather than
     * filtering to it — searching "lake" from a trailhead should put
     * the nearby ones first without hiding the rest.
     *
     * The endpoint reads this bias from a `bbox` parameter, not a point — it
     * was called with `lat`/`lon` for a long time, which the server simply
     * never has a parameter for and silently ignores, so every search ran
     * completely unbiased regardless of where the map was looking. A box
     * around the point is a working stand-in for the real viewport, which
     * this call has no way to know: the caller has one point, a camera
     * centre, not the four corners currently on screen.
     *
     * The response is Mapbox-geocoder-shaped GeoJSON, which carries far more
     * than a result list needs, so it is flattened here into [PoiHit] rather
     * than modelled in full. Parsed leniently: a hit missing its geometry is
     * dropped rather than failing the search.
     */
    suspend fun searchPoi(query: String, lat: Double? = null, lon: Double? = null): List<PoiHit> {
        val body: JsonObject = request { token ->
            http.get(url(Endpoints.POI_SEARCH)) {
                bearer(token)
                parameter("q", query)
                if (lat != null && lon != null) {
                    parameter(
                        "bbox",
                        "${lon - SEARCH_BIAS_DEGREES},${lat - SEARCH_BIAS_DEGREES}," +
                            "${lon + SEARCH_BIAS_DEGREES},${lat + SEARCH_BIAS_DEGREES}",
                    )
                }
            }
        }
        return body["features"]?.jsonArray.orEmpty().mapNotNull { feature ->
            val obj = feature as? JsonObject ?: return@mapNotNull null
            val properties = obj["properties"] as? JsonObject
            val centre = (obj["geometry"] as? JsonObject)
                ?.get("coordinates")?.jsonArray
                ?: return@mapNotNull null
            val lng = centre.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val latitude = centre.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                ?: return@mapNotNull null
            val name = (properties?.get("name") ?: obj["text"])?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            PoiHit(
                name = name,
                description = (properties?.get("place_name") ?: obj["place_name"])
                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
                lat = latitude,
                lng = lng,
            )
        }
    }

    /**
     * Everything named in [bbox], for a phone to index and search for itself.
     *
     * The server refuses a box wider than a few degrees per side — see the
     * endpoint's own note — so a caller downloading a large region should
     * expect this to fail for it exactly as [routingDataManifest] already
     * does, and treat it the same non-fatal way.
     */
    suspend fun offlinePoi(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): OfflinePoiExport = request { token ->
        http.get(url(Endpoints.POI_OFFLINE)) {
            bearer(token)
            parameter("bbox", "$west,$south,$east,$north")
        }
    }

    /**
     * Snap a line through [coordinates] onto real trails and paths.
     *
     * BRouter's answer, passed through as the GeoJSON text it is. Its
     * properties are a loose bag of strings — `track-length`, `filtered ascend`,
     * `total-time` — that differ by profile and version, so modelling them here
     * would be inventing a contract the router does not offer. The caller reads
     * the geometry it needs and treats the rest as advisory.
     *
     * `coordinates` is `[lng, lat]`, GeoJSON order, unlike the `[lat, lng]` the
     * heatmap endpoint uses. See [heatmapPoint] for why that distinction gets
     * its own home in this module.
     */
    suspend fun snapRoute(
        coordinates: List<List<Double>>,
        profile: String = "trekking",
    ): String = request { token ->
        http.post(url(Endpoints.ROUTE_SNAP)) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(SnapRouteRequest(coordinates = coordinates, profile = profile))
        }
    }

    /** What the phone must download to route inside [bbox] with no network. */
    suspend fun routingDataManifest(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): RoutingDataManifest = request { token ->
        http.get(url(Endpoints.ROUTE_OFFLINE_MANIFEST)) {
            bearer(token)
            parameter("bbox", "$west,$south,$east,$north")
        }
    }

    /**
     * Stream one routing file to wherever the caller is putting it.
     *
     * A sink rather than a `ByteArray` return, because these are rd5 segments:
     * one covering a mid-sized US state is 36 MB and the one covering the Alps is 138.
     * Materialising that as a single array to write it straight back out would
     * be a heap spike proportional to how mountainous the user's trip is —
     * exactly backwards. The sink is suspending so the caller can write to disk
     * without buffering, and [onProgress] reports bytes so far against the size
     * the manifest promised.
     */
    suspend fun downloadRoutingFile(
        kind: String,
        name: String,
        onProgress: (Long) -> Unit = {},
        sink: suspend (ByteArray, Int) -> Unit,
    ) {
        val path = when (kind) {
            "segment" -> Endpoints.routingSegment(name)
            "profile" -> Endpoints.routingProfile(name)
            // Not an assertion about the server: the kind arrives inside the
            // manifest, so a server that learns a third kind would otherwise
            // have this quietly fetch it from the wrong endpoint.
            else -> throw IllegalArgumentException("Unknown routing file kind: $kind")
        }
        val resp = authorized { token -> http.get(url(path)) { bearer(token) } }
        if (resp.status != HttpStatusCode.OK) {
            throw IllegalStateException("Could not fetch $name: ${resp.status}")
        }

        val channel = resp.bodyAsChannel()
        val buffer = ByteArray(DOWNLOAD_CHUNK)
        var total = 0L
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) continue
            sink(buffer, read)
            total += read
            onProgress(total)
        }
    }

    /**
     * One 512×512 Terrarium-encoded DEM tile — see [Endpoints.DEM_TILE] for
     * why this is not a JSON-decoded [request]. Null rather than throwing for
     * a tile the DEM archive does not have — Caddy turns go-pmtiles' own 204
     * for that into a 404 before it reaches here, so both are treated the
     * same way — since a missing tile is an ordinary, expected answer for
     * [OfflineDem] to fall back to a coarser zoom over, not a failure.
     */
    suspend fun demTile(z: Int, x: Int, y: Int): ByteArray? {
        val resp = authorized { token -> http.get(url(Endpoints.demTile(z, x, y))) { bearer(token) } }
        if (resp.status != HttpStatusCode.OK) return null
        return resp.body()
    }

    suspend fun deleteList(): PushList = request { token ->
        http.get(url(Endpoints.DELETE_LIST)) { bearer(token) }
    }

    suspend fun agps(): AgpsResponse = request { token ->
        http.get(url(Endpoints.AGPS)) { bearer(token) }
    }

    // ── Music ────────────────────────────────────────────────────────────────

    suspend fun musicTracks(): MusicTrackList = request { token ->
        http.get(url(Endpoints.MUSIC_TRACKS)) { bearer(token) }
    }

    suspend fun setMusicLoad(ids: List<Int>, load: Boolean): Unit = request { token ->
        http.post(url(Endpoints.MUSIC_TRACKS_LOAD)) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(MusicLoadRequest(ids, load))
        }
    }

    suspend fun musicDevicePlan(): MusicDevicePlan = request { token ->
        http.get(url(Endpoints.MUSIC_DEVICE_PLAN)) { bearer(token) }
    }

    suspend fun musicServer(): MusicServer = request { token ->
        http.get(url(Endpoints.MUSIC_SERVER)) { bearer(token) }
    }

    /**
     * Save (and verify) a music server.
     *
     * The server proves the credentials before storing them, so a 4xx here
     * means "those details do not work", not "saved but broken later".
     */
    suspend fun setMusicServer(body: MusicServerRequest): MusicServerResult = request { token ->
        http.put(url(Endpoints.MUSIC_SERVER)) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun clearMusicServer(): Unit = request { token ->
        http.delete(url(Endpoints.MUSIC_SERVER)) { bearer(token) }
    }

    suspend fun smartPlaylists(): SmartPlaylistList = request { token ->
        http.get(url(Endpoints.MUSIC_SMART)) { bearer(token) }
    }

    suspend fun importSmartPlaylist(kind: String): Unit = request { token ->
        http.post(url(Endpoints.musicSmartImport(kind))) { bearer(token) }
    }

    suspend fun dropSmartPlaylist(kind: String): Unit = request { token ->
        http.delete(url(Endpoints.musicSmartByKind(kind))) { bearer(token) }
    }

    suspend fun remotePlaylists(): RemotePlaylistList = request { token ->
        http.get(url(Endpoints.MUSIC_REMOTE_PLAYLISTS)) { bearer(token) }
    }

    suspend fun searchRemoteSongs(query: String): RemoteSongList = request { token ->
        http.get(url(Endpoints.MUSIC_REMOTE_SEARCH)) {
            bearer(token)
            parameter("q", query)
        }
    }

    suspend fun importRemotePlaylist(id: String): Unit = request { token ->
        http.post(url(Endpoints.musicPlaylistImport(id))) { bearer(token) }
    }

    /**
     * The music server credentials for the on-watch music app.
     *
     * Fetched at the moment they are handed to the watch and not kept: the
     * watch is where they live.
     */
    suspend fun musicWatchConfig(): MusicWatchConfig = request { token ->
        http.get(url(Endpoints.MUSIC_WATCH_CONFIG)) { bearer(token) }
    }

    /** Record that a watch sync completed, for the sidebar indicator. */
    suspend fun markWatchSynced(): Unit = request { token ->
        http.post(url(Endpoints.WATCH_SYNCED)) { bearer(token) }
    }

    /**
     * Register this phone as a sync agent and return its one-time token.
     *
     * Needs a live session — this is the one moment the agent path depends on
     * being logged in. After that it never does again, which is exactly why it
     * is worth doing at sign-in rather than waiting for the first watch sync,
     * which may well happen with no signal and an expired session.
     */
    suspend fun registerSyncAgent(label: String): CreateSyncAgentResponse = request { token ->
        http.post(url(Endpoints.SYNC_AGENTS)) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(CreateSyncAgentRequest(label = label))
        }
    }

    // ── Sealed ingest ────────────────────────────────────────────────────────
    // Authenticated by a sync-agent token, NOT the JWT, and needs no vault —
    // which is exactly why watch sync keeps working on an expedition where the
    // user has not entered a password in weeks.

    suspend fun syncPubkey(agentToken: String, deviceSerial: String? = null): SyncPubkey =
        http.get(url(Endpoints.SYNC_PUBKEY)) {
            header("Authorization", "Bearer $agentToken")
            deviceSerial?.let { header("X-Garmin-Device-Serial", it) }
        }.body()

    suspend fun ingest(agentToken: String, body: IngestRequest): IngestResponse =
        http.post(url(Endpoints.SYNC_INGEST)) {
            header("Authorization", "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

    /**
     * Which of [body]'s hashes the server lacks. Throws on any non-2xx rather
     * than decoding an error body into an empty answer: "nothing is missing"
     * is the one reading of a failure that would mark files sent that never
     * went.
     */
    suspend fun ingestMissing(agentToken: String, body: IngestMissingRequest): IngestMissingResponse {
        val resp = http.post(url(Endpoints.SYNC_INGEST_MISSING)) {
            header("Authorization", "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) throw SyncHttpException(resp.status.value, resp.bodyAsText().take(200))
        return resp.body()
    }

    /** Many sealed files at once; same failure rule as [ingestMissing]. */
    suspend fun ingestBatch(agentToken: String, body: IngestBatchRequest): IngestBatchResponse {
        val resp = http.post(url(Endpoints.SYNC_INGEST_BATCH)) {
            header("Authorization", "Bearer $agentToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) throw SyncHttpException(resp.status.value, resp.bodyAsText().take(200))
        return resp.body()
    }

    /**
     * Hand over a course the watch was carrying.
     *
     * Unsealed, unlike [ingest]. A course is a route someone loaded onto their
     * watch — public cartography they chose to carry, not a record of where they
     * actually went — and the server parses it into a saved track immediately
     * rather than queueing it behind a login. The serial goes in the header the
     * same way, because it is still how the server works out whose watch this is.
     */
    suspend fun ingestCourse(
        agentToken: String,
        body: CourseIngestRequest,
        deviceSerial: String? = null,
    ): Unit = http.post(url(Endpoints.COURSE_INGEST)) {
        header("Authorization", "Bearer $agentToken")
        deviceSerial?.let { header("X-Garmin-Device-Serial", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }.body()

    /**
     * Hand over the saved places a watch is carrying.
     *
     * The whole file, and the server reconciles the set against it: places on
     * the watch that Tracks has never seen become waypoints, and ones it
     * believed were there and are not come off. That is why this takes a file
     * rather than a list — "what the device holds" is only answerable by the
     * file itself, and a diff computed on the phone would be a guess about a
     * device somebody may have edited on their wrist.
     */
    suspend fun ingestLocations(
        agentToken: String,
        fitB64: String,
        deviceSerial: String? = null,
    ): Unit = http.post(url(Endpoints.WAYPOINT_INGEST)) {
        header("Authorization", "Bearer $agentToken")
        deviceSerial?.let { header("X-Garmin-Device-Serial", it) }
        contentType(ContentType.Application.Json)
        setBody(WaypointIngestRequest(fitB64 = fitB64))
    }.body()

    // ── Recovery ─────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.bearer(token: String) {
        header("Authorization", "Bearer $token")
    }

    private fun HttpRequestBuilder.signOutTimeout() {
        timeout {
            connectTimeoutMillis = LOGOUT_TIMEOUT_MS
            requestTimeoutMillis = LOGOUT_TIMEOUT_MS
        }
    }

    private fun origin(): String {
        // base_url must be scheme + host with no path; the server rejects
        // anything else rather than mint a style pointing somewhere unexpected.
        val withoutScheme = baseUrl.substringAfter("://", baseUrl)
        val host = withoutScheme.substringBefore('/')
        val scheme = baseUrl.substringBefore("://", "https")
        return "$scheme://$host"
    }

    private suspend fun token(): String =
        tokens.load().accessToken ?: throw NotAuthenticatedException()

    /** Run a request, recovering from an expired session at most once. */
    private suspend fun authorized(block: suspend (String) -> HttpResponse): HttpResponse {
        val first = block(token())
        if (first.status != HttpStatusCode.Unauthorized) return first

        val recovered = recover(first)
        if (!recovered) return first
        return block(token())
    }

    private suspend inline fun <reified T> request(
        noinline block: suspend (String) -> HttpResponse,
    ): T {
        val resp = authorized(block)
        when (resp.status) {
            HttpStatusCode.Unauthorized -> {
                // Recovery already ran and failed. Which exception depends on
                // whether the vault or the identity is the problem, because the
                // app shows a different screen for each.
                if (resp.isSessionExpired()) throw VaultLockedException()
                throw NotAuthenticatedException()
            }
            else -> {
                // Only where a value is expected: a Unit call ignores the
                // body as before, and an HttpResponse caller reads the status.
                if (resp.status.value >= 400 && T::class != Unit::class && T::class != HttpResponse::class) {
                    throw ServerErrorException(resp.status.value, errorDetail(resp))
                }
                return resp.body()
            }
        }
    }

    /** FastAPI's `{"detail": "..."}`, when the error body is one. */
    @PublishedApi
    internal suspend fun errorDetail(resp: HttpResponse): String? = runCatching {
        val detail = (Json.parseToJsonElement(resp.bodyAsText()) as? JsonObject)?.get("detail")
        (detail as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /**
     * Try to make the session usable again. Returns false if the user has to do
     * something.
     */
    private suspend fun recover(failed: HttpResponse): Boolean = recoveryLock.withLock {
        val stored = tokens.load()

        if (failed.isSessionExpired()) {
            // The vault is shut but the identity is fine — a refresh would not
            // help, because the decryption key is derived from a secret the
            // refresh token does not carry.
            val key = stored.deviceKey ?: run {
                onSessionState(SessionState.VaultLocked)
                return@withLock false
            }
            return@withLock deviceUnlock(key)
        }

        val refresh = stored.refreshToken ?: run {
            onSessionState(SessionState.LoggedOut)
            return@withLock false
        }
        return@withLock refresh(refresh)
    }

    private suspend fun refresh(refreshToken: String): Boolean {
        val resp: HttpResponse = http.post(url(Endpoints.REFRESH)) {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshToken))
        }
        if (resp.status != HttpStatusCode.OK) {
            // A rejected refresh token is terminal: either it expired, or its
            // family was revoked because a consumed one was replayed.
            tokens.clear()
            onSessionState(SessionState.LoggedOut)
            return false
        }
        val body: TokenResponse = resp.body()
        tokens.save(
            tokens.load().copy(
                accessToken = body.accessToken,
                // Rotation: the old token is now dead. Storing the new one is
                // not optional — reusing the old would revoke the whole family.
                refreshToken = body.refreshToken ?: refreshToken,
            ),
        )
        onSessionState(SessionState.Active)
        return true
    }

    private suspend fun deviceUnlock(key: DeviceKeyCredential): Boolean {
        val resp: HttpResponse = http.post(url(Endpoints.DEVICE_UNLOCK)) {
            contentType(ContentType.Application.Json)
            setBody(DeviceUnlockRequest(key.id, key.secret))
        }
        if (resp.status != HttpStatusCode.OK) {
            // Revoked, or the password was changed. The key is dead; keep the
            // rest of the session so the user only re-enters a password.
            tokens.save(tokens.load().copy(deviceKey = null))
            onSessionState(SessionState.VaultLocked)
            return false
        }
        val body: TokenResponse = resp.body()
        tokens.save(
            tokens.load().copy(
                accessToken = body.accessToken,
                // Unlock mints a new sid, so any refresh token held before this
                // points at a dead session. The replacement carries the new one.
                refreshToken = body.refreshToken ?: tokens.load().refreshToken,
            ),
        )
        onSessionState(SessionState.Active)
        return true
    }

    fun close() = http.close()

    private companion object {
        /** How long sign-out waits for the server before signing out anyway. */
        const val LOGOUT_TIMEOUT_MS = 5_000L

        /** 64 KB: big enough that the syscall count is irrelevant next to the
         *  network, small enough to be invisible on a phone's heap. */
        const val DOWNLOAD_CHUNK = 64 * 1024

        /**
         * Half-width of the box built around a search's bias point — see
         * [searchPoi]. About 55 km at the equator: close enough to favour a
         * trailhead's own valley, wide enough that a search for something a
         * short drive away is not pushed to the bottom of the list.
         */
        const val SEARCH_BIAS_DEGREES = 0.5
    }
}

/** What [TracksClient.changePassword] did beyond changing the password. */
data class PasswordChangeResult(
    /** Other phones' device keys the change revoked; they now need the new password. */
    val otherDevicesSignedOut: Int,
    /** Whether this phone enrolled a fresh device key to replace its revoked one. */
    val deviceKeyReEnrolled: Boolean,
)

/** A map viewport, in the order the server expects. */
data class BoundingBox(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
) {
    fun asParameter(): String = "$minLng,$minLat,$maxLng,$maxLat"
}

/**
 * Whether a 401 means "vault shut" rather than "who are you".
 *
 * The distinction is the entire reason this client can go weeks without asking
 * for a password, and it is carried in the response body rather than the status
 * code, so it has to be read out.
 */
internal suspend fun HttpResponse.isSessionExpired(): Boolean =
    status == HttpStatusCode.Unauthorized && bodyAsText().contains("session_expired")
