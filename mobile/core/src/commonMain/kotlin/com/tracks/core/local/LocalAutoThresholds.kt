// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.AutoThresholds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Auto max HR, threshold HR and FTP from this phone's whole history — the
 * server's `recalculate_auto_values`, over the same inputs.
 *
 * "The same inputs" is the part worth reading. The server queries its
 * `activities` table, where a deleted activity is gone and a sport correction
 * has replaced the parsed sport, then scans each candidate's stored points in
 * time order with nulls dropped. This phone keeps the parsed row of a deleted
 * activity (the file is still here) and holds corrections as replica edits,
 * so both are applied here before the search, or the two sides would disagree
 * about the same account.
 *
 * Only the history half of the rule: [LocalSources.importThresholds] puts a
 * completed field test over it, as the server now does in both places.
 */
class LocalAutoThresholds(private val sources: LocalSources, private val library: LocalLibrary) {

    data class Values(val maxHr: Int? = null, val thresholdHr: Long? = null, val ftp: Long? = null)

    suspend fun history(): Values {
        val edits = sources.replica.rows("activity", includeDeleted = true).associateBy { it.uid }
        val rows = library.activityRows().filter { edits[it.uid]?.isTombstone != true }
        val uidOf = HashMap<Long, String>()
        val candidates = rows.mapIndexed { i, r ->
            uidOf[i.toLong()] = r.uid
            val sport = (edits[r.uid]?.fields?.get("sport") as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content ?: r.sport
            AutoThresholds.Candidate(
                id = i.toLong(),
                sport = sport,
                durationSeconds = r.duration_seconds,
                avgHeartRate = r.avg_heart_rate?.toInt(),
                maxHeartRate = r.max_heart_rate?.toInt(),
                avgPower = r.avg_power?.toInt(),
                normalizedPower = r.normalized_power?.toInt(),
            )
        }
        fun rolling(field: String) = { c: AutoThresholds.Candidate ->
            AutoThresholds.bestRollingAvg(series(uidOf.getValue(c.id), field))
        }
        return Values(
            maxHr = AutoThresholds.maxHr(candidates),
            thresholdHr = AutoThresholds.thresholdHr(
                candidates.filter(AutoThresholds::qualifiesForThresholdHr), rolling("heart_rate"),
            ),
            ftp = AutoThresholds.ftp(candidates.filter(AutoThresholds::qualifiesForFtp), rolling("power")),
        )
    }

    /** One stored series as (epoch seconds, value), time order, nulls dropped — `_best_rolling_avg`'s query. */
    private fun series(uid: String, field: String): List<Pair<Double, Double>> {
        val points = library.detailJson(uid)?.get("data_points") as? JsonArray ?: return emptyList()
        return points.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val v = (o[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull ?: return@mapNotNull null
            val t = (o["recorded_at"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                ?.let(::epochSeconds) ?: return@mapNotNull null
            t to v
        }.sortedBy { it.first }
    }

    companion object {
        /**
         * Python's `datetime.timestamp()` for the importer's own isoformat,
         * `YYYY-MM-DDTHH:MM:SS[.ffffff]+00:00` — always UTC, always this shape,
         * because [LocalJson] writes it. Anything else reads as null rather
         * than as a guessed zone.
         */
        fun epochSeconds(iso: String): Double? {
            if (iso.length < 19 || iso[10] != 'T') return null
            val date = runCatching {
                CivilDate(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt())
            }.getOrNull() ?: return null
            val h = iso.substring(11, 13).toIntOrNull() ?: return null
            val m = iso.substring(14, 16).toIntOrNull() ?: return null
            val s = iso.substring(17, 19).toIntOrNull() ?: return null
            var micros = 0L
            var rest = iso.substring(19)
            if (rest.startsWith(".")) {
                val digits = rest.drop(1).takeWhile { it.isDigit() }
                micros = digits.padEnd(6, '0').take(6).toLongOrNull() ?: return null
                rest = rest.drop(1 + digits.length)
            }
            if (rest.isNotEmpty() && rest != "+00:00" && rest != "Z") return null
            val whole = date.epochDay * 86_400L + h * 3600L + m * 60L + s
            // Total microseconds over 10^6, as Python divides — adding a
            // fractional part to the seconds can differ in the last bit.
            return (whole * 1_000_000L + micros) / 1_000_000.0
        }
    }
}
