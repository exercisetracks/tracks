// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.db.SqlDriver
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.TracksJson
import com.tracks.core.db.Local_activity
import com.tracks.core.db.Local_file
import com.tracks.core.db.TracksDb
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.MetricActivity
import com.tracks.core.plan.Matching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Everything this phone derived from FIT files, and the integer ids screens use.
 *
 * Written only by [LocalImporter]; read by every screen that shows a
 * measurement. See `Local.sq` for why none of it syncs.
 */
class LocalLibrary(
    driver: SqlDriver,
    /**
     * UTC offset in a named zone at an instant — [com.tracks.core.time.ZoneOffsets]
     * on a phone, as [LocalMatching] takes it. The default is UTC everywhere,
     * for tests that do not care.
     */
    private val zoneOffsets: (zone: String?) -> (epochSeconds: Long) -> Int = { { 0 } },
) {
    private val db = TracksDb(driver)
    internal val q = db.localQueries
    private val lock = Mutex()

    private val _version = MutableStateFlow(0L)

    /**
     * Bumped whenever the derived data changes, so a screen held open while a
     * watch sync imports files reloads instead of showing this morning.
     */
    val version: StateFlow<Long> get() = _version

    internal fun changed() {
        _version.value = _version.value + 1
    }

    // ── Aliases ─────────────────────────────────────────────────────────────

    /** The integer a screen uses for [uid], minted on first use and kept forever. */
    fun alias(uid: String): Int =
        // Read first: nearly every call is for a uid that already has one,
        // and a write — even an INSERT OR IGNORE that inserts nothing — waits
        // for the write lock. During a first sync the history import holds
        // that lock for long stretches, so every page decoding rows waited on
        // it, one row at a time. Only a new uid takes the write path.
        q.aliasOf(uid).executeAsOneOrNull()?.toInt() ?: db.transactionWithResult {
            q.insertAlias(uid)
            q.aliasOf(uid).executeAsOne().toInt()
        }

    fun uidOf(alias: Int): String? = q.uidOf(alias.toLong()).executeAsOneOrNull()

    /** uid → alias for every row that has one, in one read — for loops that would call [alias] per row. */
    fun aliasesByUid(): Map<String, Int> = q.allAliases().executeAsList().associate { it.uid to it.alias.toInt() }

    /** alias → uid for every row that has one; see `allAliases` in Local.sq. */
    fun uidsByAlias(): Map<Int, String> = q.allAliases().executeAsList().associate { it.alias.toInt() to it.uid }

    // ── Files ───────────────────────────────────────────────────────────────

    fun file(sha256: String): Local_file? = q.selectFile(sha256).executeAsOneOrNull()

    fun notUploaded(): List<String> = q.selectNotUploaded().executeAsList()

    fun markUploaded(sha256: String) = q.markUploaded(sha256)

    /** Many at once, in one transaction: a commit per file was its own cost on a backlog of thousands. */
    fun markUploaded(sha256s: Collection<String>) = db.transaction { sha256s.forEach(q::markUploaded) }

    /**
     * Called before every upload run with the server the replica is bound to:
     * if the `uploaded` flags were earned on a different one, they are all
     * cleared, so every file goes up again.
     *
     * The replica's own "give the new server everything" paths — a recreated
     * server, and the user answering yes to restoring this phone into an
     * account on another server — re-queue synced rows only. FIT files are
     * not rows; they kept their flags from the old server, so the new one got
     * every activity's name and every plan, and never the files themselves:
     * no activity history, no past health days. Keying the flags to a server
     * covers both paths, and any later one, without each having to remember.
     *
     * With no server recorded yet the flags are taken as they stand: a fresh
     * phone's flags are all its first server's (history it downloaded), and a
     * phone upgraded from schema 7 had them cleared by that migration, since
     * nothing then said which server they belonged to.
     */
    suspend fun uploadsGoTo(serverId: String) = lock.withLock {
        db.transaction {
            val recorded = q.selectUploadServer().executeAsOneOrNull()
            if (recorded == serverId) return@transaction
            if (recorded != null) q.markAllNotUploaded()
            q.setUploadServer(serverId)
        }
    }

    // ── Activities ──────────────────────────────────────────────────────────

    fun activities(): List<ActivitySummary> {
        // Every alias in one read, not one query per row: this list backs
        // most screens and the dashboard asks for it several times a load,
        // and a history of a thousand activities was a thousand lookups each
        // time. [alias] still mints one for a row that has none yet.
        val aliases = aliasesByUid()
        return q.selectActivities().executeAsList().map { activitySummary(it, aliases[it.uid] ?: alias(it.uid)) }
    }

    private fun activitySummary(it: com.tracks.core.db.SelectActivities, id: Int) =
        ActivitySummary(
            id = id,
            sport = it.sport,
            subSport = it.sub_sport,
            name = it.name,
            startedAt = it.started_at,
            durationSeconds = it.duration_seconds?.toInt(),
            distanceMeters = it.distance_meters,
            avgHeartRate = it.avg_heart_rate?.toInt(),
            maxHeartRate = it.max_heart_rate?.toInt(),
            totalCalories = it.total_calories?.toInt(),
            totalAscent = it.total_ascent,
            avgSpeed = it.avg_speed,
        )

    fun activity(uid: String): Local_activity? = q.selectActivity(uid).executeAsOneOrNull()

    /**
     * Every parsed activity's columns, newest first — the auto-threshold
     * search's input. Without the JSON: only the few candidates it scans need
     * their points, and those are read one at a time.
     */
    fun activityRows(): List<com.tracks.core.db.SelectActivities> = q.selectActivities().executeAsList()

    /** The parser's `activity` dict for one activity, as JSON. */
    fun summaryJson(uid: String): JsonObject? =
        activity(uid)?.let { TracksJson.parseToJsonElement(it.summary).jsonObject }

    /** The parser's children — `data_points`, `laps`, `strength_sets`, curves — as JSON. */
    fun detailJson(uid: String): JsonObject? =
        activity(uid)?.let { TracksJson.parseToJsonElement(it.detail).jsonObject }

    /**
     * The day [startedAt] fell on in the account's [zone], as `YYYY-MM-DD` —
     * the server's `activity_local_date`, by [Matching.localDate].
     *
     * Not `started_at.take(10)`, the UTC day: an 18:04 run in California is
     * stored as 01:04 the next day, and every figure built from these rows
     * (the fitness model, readiness, coaching, the plan's recent volume)
     * counted it tomorrow. The server buckets by the same local day, so the
     * two still agree; see calculators/local_day.py.
     */
    fun localDay(startedAt: String, zone: String?): String? = Matching.localDate(startedAt, zoneOffsets(zone))

    /**
     * The fitness model's input, one row per activity, each on its day in the
     * account's [zone] ([localDay]).
     */
    fun metricActivities(zone: String?): List<MetricActivity> {
        val offsets = zoneOffsets(zone)
        return q.selectActivities().executeAsList().mapNotNull { metricActivity(it, offsets) }
    }

    private fun metricActivity(
        row: com.tracks.core.db.SelectActivities,
        offsets: (Long) -> Int,
    ): MetricActivity? {
        val date = row.started_at?.let { Matching.localDate(it, offsets) }?.let(::civil) ?: return null
        return MetricActivity(
            id = alias(row.uid).toLong(),
            date = date,
            sport = row.sport,
            distanceMeters = row.distance_meters,
            durationSeconds = row.duration_seconds,
            avgHeartRate = row.avg_heart_rate?.toInt(),
            maxHeartRate = row.max_heart_rate?.toInt(),
            trainingStressScore = row.training_stress_score,
            effectiveTss = row.effective_tss,
            vo2maxEstimate = row.vo2max_estimate,
            avgPower = row.avg_power?.toInt(),
            normalizedPower = row.normalized_power?.toInt(),
            avgSpeed = row.avg_speed,
            totalAscent = row.total_ascent,
        )
    }

    /**
     * The activities that may have a route thumbnail, newest first, as
     * (list id, uid) — the ones with a distance, so an indoor session is never
     * decoded just to find it has no track.
     */
    fun shapeCandidates(): List<Pair<Int, String>> {
        val aliases = aliasesByUid()
        return q.selectActivities().executeAsList()
            .filter { (it.distance_meters ?: 0.0) > 0.0 }
            .map { (aliases[it.uid] ?: alias(it.uid)) to it.uid }
    }

    /**
     * One activity's route outline, from its own parsed file.
     *
     * One at a time rather than [routesGeoJson] for everything: decoding a
     * detail file is the expensive part, and the list can show each outline
     * as soon as its own file is read instead of after all of them are.
     */
    fun trackShape(uid: String): com.tracks.core.api.TrackShape? {
        val points = detailJson(uid)?.get("data_points") as? JsonArray ?: return null
        val coords = points.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull
            val lng = (o["lng"] as? JsonPrimitive)?.doubleOrNull
            if (lat == null || lng == null) null else lng to lat
        }
        return com.tracks.core.api.TrackShapes.normalise(coords)
    }

    /**
     * The tracks the dashboard heatmap draws, from the phone's own files.
     *
     * [uids] limits it to some activities — the visible ones, in the chosen
     * sport; [after], an ISO date, drops anything started before it — the
     * dashboard's window, compared against the stored UTC start the way the
     * server's `started_at >= after` does. In a value [mode] only points that
     * carry the value are kept, as the server's heatmap query filters them, so
     * a stretch with no heart rate is a gap rather than a guess.
     *
     * Read from `local_heat_track`, each activity's track packed small. A
     * track missing there, or cut from an older file, is cut now from the
     * detail JSON and stored, so each activity's detail is parsed once rather
     * than on every filter change. Cut outside the write lock and stored in one
     * short transaction, so a first run over a long history never holds up an
     * import for the length of the parse.
     */
    suspend fun heatmapTracks(
        uids: Set<String>?,
        after: String?,
        mode: com.tracks.core.metrics.HeatmapMode,
    ): List<List<com.tracks.core.metrics.HeatPoint>> {
        // ISO text compares in date order, so a bare date sorts before every
        // time on that day — the same inclusive edge as the server.
        val rows = q.selectHeatTracks(after).executeAsList().filter { uids == null || it.uid in uids }
        val cut = HashMap<String, Pair<String, ByteArray>>()
        val out = ArrayList<List<com.tracks.core.metrics.HeatPoint>>(rows.size)
        for (row in rows) {
            val packed = row.points ?: cutHeatTrack(row.uid).also { cut[row.uid] = row.sha256 to it }
            val track = com.tracks.core.metrics.HeatTrackCodec.decode(packed).filter { it.valueFor(mode) != null }
            if (track.size >= 2) out += track
        }
        if (cut.isNotEmpty()) lock.withLock {
            db.transaction { cut.forEach { (uid, v) -> q.upsertHeatTrack(uid, v.first, v.second) } }
        }
        return out
    }

    /**
     * One activity's track, thinned to at most [HEAT_TRACK_POINTS] points and
     * packed; empty when it has no GPS, which is stored too so the activity
     * is not re-parsed to learn that again.
     */
    private fun cutHeatTrack(uid: String): ByteArray {
        val points = detailJson(uid)?.get("data_points") as? JsonArray
            ?: return com.tracks.core.metrics.HeatTrackCodec.encode(emptyList())
        val track = points.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
            val lat = num("lat") ?: return@mapNotNull null
            val lng = num("lng") ?: return@mapNotNull null
            com.tracks.core.metrics.HeatPoint(lat, lng, num("speed"), num("heart_rate"), num("altitude"))
        }
        val step = maxOf(1, track.size / HEAT_TRACK_POINTS)
        return com.tracks.core.metrics.HeatTrackCodec.encode(
            track.filterIndexed { i, _ -> i % step == 0 || i == track.lastIndex },
        )
    }

    /**
     * `/activities/heatmap`: [lat, lng] of every activity's track, thinned to
     * at most [perActivity] points each, from the phone's own files.
     */
    fun heatmap(perActivity: Int = 200): List<List<Double>> {
        val out = ArrayList<List<Double>>()
        for (row in q.selectActivities().executeAsList()) {
            val points = detailJson(row.uid)?.get("data_points") as? JsonArray ?: continue
            val step = maxOf(1, points.size / perActivity)
            points.forEachIndexed { i, p ->
                if (i % step != 0) return@forEachIndexed
                val o = p as? JsonObject ?: return@forEachIndexed
                val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
                val lng = (o["lng"] as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
                out += listOf(lat, lng)
            }
        }
        return out
    }

    /**
     * `/strength/history`: working sets the watch recorded in strength
     * activities since [sinceDate], newest activity first, as the server lists
     * them — from each activity's parsed `strength_sets`. Each is dated by its
     * day in the account's [zone] ([localDay]), and [sinceDate] is a local day.
     */
    fun strengthHistory(
        sinceDate: String,
        zone: String?,
        exerciseName: String? = null,
        limit: Int = 500,
    ): List<com.tracks.core.api.StrengthHistoryEntry> {
        val out = ArrayList<com.tracks.core.api.StrengthHistoryEntry>()
        val offsets = zoneOffsets(zone)
        // The query compares stored UTC text; a day earlier holds every local
        // day from [sinceDate] in any zone (none is more than 14 h from UTC),
        // and the local day decides.
        val wider = civilOrNull(sinceDate)?.let { CivilDate.fromEpochDay(it.epochDay - 1).isoformat() } ?: sinceDate
        for (row in q.strengthSetsByActivity(wider).executeAsList()) {
            val started = row.started_at ?: continue
            val day = Matching.localDate(started, offsets) ?: continue
            if (day < sinceDate) continue
            val sets = row.sets?.let { TracksJson.parseToJsonElement(it) } as? JsonArray ?: continue
            for (s in sets.mapNotNull { it as? JsonObject }.sortedBy { (it["set_number"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }) {
                fun str(k: String) = (s[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                if (str("set_type") != "active") continue
                if (exerciseName != null && str("exercise_name") != exerciseName) continue
                out += com.tracks.core.api.StrengthHistoryEntry(
                    activityId = alias(row.uid),
                    activityDate = day,
                    sport = row.sport ?: "strength_training",
                    setNumber = str("set_number")?.toDoubleOrNull()?.toInt() ?: 0,
                    exerciseName = str("exercise_name"),
                    exerciseCategory = str("exercise_category"),
                    weightKg = str("weight_kg")?.toDoubleOrNull(),
                    repetitions = str("repetitions")?.toDoubleOrNull()?.toInt(),
                    durationSeconds = str("duration_seconds")?.toDoubleOrNull(),
                )
                if (out.size >= limit) return out
            }
        }
        return out
    }

    // ── Days ────────────────────────────────────────────────────────────────

    /** Every day's merged metrics plus `extra` (sleep stages, stress series). */
    fun days(): List<LocalDay> = q.selectDays().executeAsList().map {
        LocalDay(
            it.date,
            TracksJson.parseToJsonElement(it.metrics).jsonObject,
            TracksJson.parseToJsonElement(it.extra).jsonObject,
        )
    }

    fun day(date: String): LocalDay? = q.selectDay(date).executeAsOneOrNull()?.let {
        LocalDay(
            it.date,
            TracksJson.parseToJsonElement(it.metrics).jsonObject,
            TracksJson.parseToJsonElement(it.extra).jsonObject,
        )
    }

    /** Drop everything derived — the blobs are gone, or about to be re-imported. */
    suspend fun clear() = lock.withLock {
        db.transaction {
            q.deleteAllActivities()
            q.deleteAllHeatTracks()
            q.deleteAllDays()
            q.deleteAllFiles()
            q.deleteUploadServer()
        }
        changed()
    }

    internal suspend fun <T> locked(block: () -> T): T = lock.withLock { db.transactionWithResult { block() } }

    private fun civil(iso: String): CivilDate? = runCatching {
        CivilDate(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt())
    }.getOrNull()
}

/** One local date as every file that describes it adds up to. */
data class LocalDay(val date: String, val metrics: JsonObject, val extra: JsonObject)

/**
 * Points kept per activity for the dashboard heatmap. A whole history at full
 * resolution is millions of vertices for a card; 300 keeps a run's shape.
 */
private const val HEAT_TRACK_POINTS = 300
