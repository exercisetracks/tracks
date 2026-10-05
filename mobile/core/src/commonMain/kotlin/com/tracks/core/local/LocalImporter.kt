// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.TracksJson
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.metrics.HrReview
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.FitParsing
import com.tracks.core.parse.Py
import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanAssembly
import com.tracks.core.replica.Uids
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The server's importer (`services/fit_import.py`), run on the phone.
 *
 * Same parsers (com.tracks.core.parse, held byte-for-byte to the server's),
 * same identity for an activity (device serial + start second), same rule when
 * two files describe one activity (the lowest hash is the one parsed), and
 * the same per-field rules for folding several files into one day. Given the
 * same files, this and the server therefore derive the same rows — which is
 * what lets derived data never sync (docs/offline-first.md).
 *
 * ## What it deliberately does not do
 *
 * Write anything to the replica. An activity's measurements are derived; only
 * what a person says about it (a rename, a note) is a source, and that is
 * written by the screen that says it. A file's `fit_file` row is created by
 * the server when the file is uploaded, and reaches this phone by pull — a
 * phone announcing a file the server cannot serve yet would send every other
 * phone after a download that 404s.
 */
class LocalImporter(
    private val library: LocalLibrary,
    /** Thresholds for TSS, read at import time exactly as the server does. */
    private val thresholds: suspend () -> ImportThresholds = { ImportThresholds() },
    /** True for an activity the user deleted: a delete wins, and another copy of its file must not bring it back. */
    private val isDeleted: suspend (activityUid: String) -> Boolean = { false },
    /**
     * Told about each newly imported activity (uid, start, what matching needs)
     * — where the planned workout it satisfies gets ticked off. See [LocalMatching].
     */
    private val onActivity: suspend (uid: String, startedAt: String?, done: PlanAssembly.DoneActivity) -> Unit =
        { _, _, _ -> },
) {

    enum class Outcome { Imported, Duplicate, Superseded, Deleted, Unrecognised, Failed }

    /** Import one file. Idempotent: a file already imported is [Outcome.Duplicate]. */
    suspend fun import(sha256: String, bytes: ByteArray): Outcome {
        if (library.file(sha256) != null) return Outcome.Duplicate
        val kind = FitParsing.select(bytes)
        if (kind == null) {
            record(sha256, null, null)
            return Outcome.Unrecognised
        }
        val parsed = try {
            FitParsing.parse(kind, bytes)
        } catch (e: Exception) {
            // Recorded, so a file the parsers cannot read is not retried on
            // every sync — the server records the same failure against it.
            record(sha256, "failed", null)
            return Outcome.Failed
        }
        return when (parsed["type"]) {
            "daily" -> importDaily(sha256, parsed)
            "activity" -> importActivity(sha256, parsed)
            else -> {
                record(sha256, null, null)
                Outcome.Unrecognised
            }
        }
    }

    // ── Summaries from an older parser ──────────────────────────────────────

    /**
     * Fill in summary fields the parser has learned since an activity was
     * imported, from the file it was imported from. Returns how many were
     * updated.
     *
     * An import is once per file — the same bytes are a [Outcome.Duplicate]
     * forever after — so a field added to the parser otherwise appears only on
     * activities recorded from then on. The watch's post-workout feel and
     * effort were the first such fields (2026-09-30): the run that prompted
     * them had been imported an hour earlier and would never have shown its
     * own answers.
     *
     * Only keys missing from the stored summary are added. Everything already
     * there stays as it is, including what the import computed rather than
     * parsed (effective TSS from the thresholds of the day), so this can never
     * change a number the person has already seen. A file that is missing or
     * no longer parses gets the keys as null, which marks it done; otherwise
     * every launch would try it again.
     */
    suspend fun backfillSummaries(file: suspend (sha256: String) -> ByteArray?): Int {
        var updated = 0
        for (key in BACKFILLED_SUMMARY_KEYS) {
            val stale = library.locked { library.q.activitiesMissingSummaryKey(key).executeAsList() }
            for (row in stale) {
                val stored = runCatching { com.tracks.core.api.TracksJson.parseToJsonElement(row.summary) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
                val parsed = runCatching {
                    val bytes = file(row.sha256) ?: return@runCatching null
                    val kind = FitParsing.select(bytes) ?: return@runCatching null
                    @Suppress("UNCHECKED_CAST")
                    FitParsing.parse(kind, bytes)["activity"] as? Map<String, Any?>
                }.getOrNull()
                val fresh = LocalJson.obj(BACKFILLED_SUMMARY_KEYS.associateWith { parsed?.get(it) })
                val merged = kotlinx.serialization.json.JsonObject(fresh + stored)
                library.locked { library.q.updateActivitySummary(merged.toString(), row.uid) }
                updated++
            }
        }
        return updated
    }

    // ── Activities ──────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private suspend fun importActivity(sha256: String, parsed: Map<String, Any?>): Outcome {
        val device = parsed["device"] as? Map<String, Any?> ?: emptyMap()
        val activity = LinkedHashMap(parsed["activity"] as Map<String, Any?>)
        val t = thresholds()

        val powerTss = powerTss(activity["normalized_power"], t.ftp, activity["duration_seconds"])
        if (powerTss != null) {
            activity["effective_tss"] = powerTss
        } else if (activity["training_stress_score"] == null) {
            activity["effective_tss"] = hrTss(activity, t.thresholdHr, parsed["data_points"] as? List<Map<String, Any?>>)
        }
        // Stored unscaled, as the server stores it: the MTB and indoor-cycling
        // multipliers apply when load is read (TrainingLoad.scaleTss), so the
        // stored value never depends on which goal was active at import.

        val uid = activityUid(device["serial_number"], activity["started_at"], sha256)
        if (isDeleted(uid)) {
            record(sha256, "activity", uid)
            return Outcome.Deleted
        }

        val summary = LocalJson.obj(activity)
        val detail = LocalJson.obj(parsed.filterKeys { it != "activity" && it != "device" && it != "type" })
        val serial = device["serial_number"]?.let(Py::str)

        return library.locked {
            val q = library.q
            val existing = q.selectActivity(uid).executeAsOneOrNull()
            if (existing != null && existing.sha256 <= sha256) {
                q.upsertFile(sha256, "activity", uid, 0)
                return@locked Outcome.Superseded
            }
            q.upsertActivity(
                uid, sha256, serial,
                summary.str("started_at"),
                summary.str("sport"), summary.str("sub_sport"), summary.str("name"),
                summary.long("duration_seconds"), summary.dbl("distance_meters"),
                summary.long("avg_heart_rate"), summary.long("max_heart_rate"),
                summary.long("total_calories"), summary.dbl("total_ascent"), summary.dbl("avg_speed"),
                summary.dbl("training_stress_score"), summary.dbl("effective_tss"),
                summary.dbl("vo2max_estimate"), summary.long("avg_power"), summary.long("normalized_power"),
                summary.toString(), detail.toString(),
            )
            q.insertAlias(uid)
            q.upsertFile(sha256, "activity", uid, 0)
            Outcome.Imported
        }.also {
            if (it == Outcome.Imported) {
                library.changed()
                // Outside the library lock: matching reads and writes the replica.
                runCatching {
                    onActivity(
                        uid, summary.str("started_at"),
                        PlanAssembly.DoneActivity(
                            summary.str("sport"), summary.dbl("distance_meters"), summary.long("duration_seconds"),
                        ),
                    )
                }
            }
        }
    }

    // ── Days ────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private suspend fun importDaily(sha256: String, parsed: Map<String, Any?>): Outcome {
        val days = (parsed["days"] as? List<Map<String, Any?>>) ?: listOf(parsed)
        library.locked {
            for (day in days) {
                val date = day["date"]?.let { LocalJson.of(it) as? JsonPrimitive }?.content ?: continue
                val incoming = LocalJson.obj((day["metrics"] as? Map<String, Any?>).orEmpty())
                    .filterKeys { it in DAILY_FIELDS }
                val stages = LocalJson.of(day["stages"] ?: emptyList<Any>()) as JsonArray
                val stress = LocalJson.of(day["stress_series"] ?: emptyList<Any>()) as JsonArray
                val current = library.q.selectDay(date).executeAsOneOrNull()
                val merged = mergeDay(
                    current?.let { TracksJson.parseToJsonElement(it.metrics).jsonObject },
                    current?.let { TracksJson.parseToJsonElement(it.extra).jsonObject },
                    incoming, stages, stress,
                )
                library.q.upsertDay(date, merged.first.toString(), merged.second.toString())
            }
            library.q.upsertFile(sha256, "daily", null, 0)
        }
        library.changed()
        return Outcome.Imported
    }

    private suspend fun record(sha256: String, kind: String?, activityUid: String?) =
        library.locked { library.q.upsertFile(sha256, kind, activityUid, 0) }

    companion object {
        /** Summary keys [backfillSummaries] adds to activities parsed before them. */
        val BACKFILLED_SUMMARY_KEYS = listOf("workout_feel", "workout_rpe")

        /** `_DAILY_METRIC_FIELDS`: what a health file may set on a day. */
        val DAILY_FIELDS = setOf(
            "resting_hr", "hrv", "sleep_hours", "sleep_score",
            "sleep_deep_hours", "sleep_light_hours", "sleep_rem_hours", "sleep_awake_hours",
            "training_load", "spo2", "steps", "active_calories", "resting_calories",
            "avg_stress_level", "avg_respiration_rate",
            "body_battery_high", "body_battery_low", "body_battery_last",
            "body_battery_charged", "body_battery_drained",
        )
        private val MAX = setOf(
            "body_battery_high", "body_battery_charged", "body_battery_drained", "steps",
            "active_calories", "resting_calories",
        )
        private val MIN = setOf("body_battery_low")
        private val LATEST = setOf("body_battery_last", "resting_hr")

        /**
         * `_write_one_day`, as a pure function: first reading wins, except the
         * fields that take the max, the min or the latest; the longer sleep
         * timeline wins; stress series merge by minute, incoming winning its
         * own minute.
         */
        fun mergeDay(
            metrics: JsonObject?,
            extra: JsonObject?,
            incoming: Map<String, JsonElement>,
            stages: JsonArray,
            stress: JsonArray,
        ): Pair<JsonObject, JsonObject> {
            val m = LinkedHashMap(metrics ?: JsonObject(emptyMap()))
            for ((field, value) in incoming) {
                if (value is JsonNull) continue
                val cur = m[field]
                m[field] = when {
                    cur == null || cur is JsonNull -> value
                    field in LATEST -> value
                    field in MAX -> if (num(value) > num(cur)) value else cur
                    field in MIN -> if (num(value) < num(cur)) value else cur
                    else -> cur
                }
            }
            val x = LinkedHashMap(extra ?: JsonObject(emptyMap()))
            if (stages.isNotEmpty()) {
                val existing = (x["sleep_stages"] as? JsonArray)?.size ?: 0
                if (metrics == null || stages.size >= existing) x["sleep_stages"] = stages
            }
            if (stress.isNotEmpty()) {
                x["stress_series"] = mergeStress(x["stress_series"] as? JsonArray, stress)
            }
            return JsonObject(m) to JsonObject(x)
        }

        /** `_merge_stress_series`. */
        fun mergeStress(current: JsonArray?, incoming: JsonArray): JsonArray {
            val merged = HashMap<Long, Long>()
            for (series in listOfNotNull(current, incoming)) {
                for (point in series) {
                    val pair = (point as? JsonArray) ?: continue
                    val minute = pair.getOrNull(0)?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: continue
                    val value = pair.getOrNull(1)?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: continue
                    merged[minute] = value
                }
            }
            return JsonArray(merged.keys.sorted().map { k -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(merged.getValue(k)))) })
        }

        /** `compute_power_tss`. */
        fun powerTss(np: Any?, ftp: Double?, duration: Any?): Double? {
            val n = (np as? Number)?.toDouble() ?: return null
            val d = (duration as? Number)?.toDouble() ?: return null
            if (n == 0.0 || d == 0.0 || ftp == null || ftp <= 0.0) return null
            val hours = d / 3600
            val ratio = n / ftp
            return PyMath.round(hours * (ratio * ratio) * 100, 1)
        }

        /**
         * `_compute_hr_tss`: hrTSS from the samples that survive review
         * ([HrReview]) — unchanged for a clean recording, recomputed from the
         * athlete's own heart rate against effort where samples fail, and
         * scored as an activity with no heart rate when too little survives.
         */
        fun hrTss(activity: Map<String, Any?>, threshold: Double?, points: List<Map<String, Any?>>? = null): Double? {
            val dur = (activity["duration_seconds"] as? Number)?.toDouble()
            var avg = (activity["avg_heart_rate"] as? Number)?.toDouble()
            if (dur == null || dur == 0.0 || avg == null || avg == 0.0) return null
            var max = (activity["max_heart_rate"] as? Number)?.toDouble()
            val sport = activity["sport"]?.let(Py::str)
            val review = if (!points.isNullOrEmpty()) HrReview.reviewPoints(sport, points) else null
            if (review != null) {
                if (!review.usable) {
                    return TrainingLoad.estimatedRawTss(
                        sport, dur, (activity["distance_meters"] as? Number)?.toDouble(),
                        (activity["total_ascent"] as? Number)?.toDouble(),
                    )
                }
                avg = review.avgHr!!
                max = review.maxHr
            }
            val th = threshold ?: ((if (max != null && max != 0.0) max else (avg * 1.15).toLong().toDouble()) * 0.87)
            if (th <= 0) return null
            val ratio = minOf(avg / th, 1.5)
            return PyMath.round((dur / 3600) * (ratio * ratio) * 100, 1)
        }

        /** `registry.activity_uid`: same watch, same start second, whichever bytes. */
        fun activityUid(serial: Any?, startedAt: Any?, sha256: String): String {
            val s = serial?.let(Py::str)?.takeIf { it.isNotEmpty() }
            val start = startedAt as? FitDateTime
            val name = if (s != null && start != null) {
                "activity:$s:${start.epochSeconds}"
            } else {
                "activity:sha256:$sha256"
            }
            return Uids.v5(name)
        }

        private fun num(e: JsonElement): Double = e.jsonPrimitive.doubleOrNull ?: 0.0
    }
}

/** The two thresholds TSS needs, as `_effective_ftp` / `_effective_threshold_hr` resolve them. */
data class ImportThresholds(
    val ftp: Double? = null,
    val thresholdHr: Double? = null,
    /** Manual, else the highest max HR in history — the server's `max_hr_auto`. */
    val maxHr: Double? = null,
)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.long(k: String): Long? =
    (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
