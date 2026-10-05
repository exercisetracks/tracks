// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.ClimbSplit
import com.tracks.core.api.Lap
import com.tracks.core.api.StrengthSet
import com.tracks.core.api.TrackPoint
import com.tracks.core.api.TracksJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Everything the activity screen shows about one activity. */
data class ActivityPieces(
    val detail: ActivityDetail,
    val laps: List<Lap>,
    val climbs: List<ClimbSplit>,
    val sets: List<StrengthSet>,
    val track: List<TrackPoint>,
)

/**
 * One activity, read from what this phone parsed out of its file.
 *
 * The server serves these as five endpoints over five tables; on the phone
 * they are one parser output (see [LocalImporter]), split here into the same
 * models. The person's edits — a rename, a note, a sport correction — are laid
 * over the parsed values, as on the list.
 */
class LocalActivityDetail(private val library: LocalLibrary, private val sources: LocalSources) {

    suspend fun load(id: Int, maxPoints: Int = 2000): ActivityPieces? {
        val uid = library.uidOf(id) ?: return null
        val summary = library.summaryJson(uid) ?: return null
        val detail = library.detailJson(uid) ?: JsonObject(emptyMap())
        val edit = sources.replica.row("activity", uid)?.takeIf { !it.isTombstone }

        val fields = LinkedHashMap<String, JsonElement>(summary)
        fields["id"] = JsonPrimitive(id)
        edit?.fields?.forEach { (k, v) -> if (k != "hidden") fields[k] = v }
        fields["lap_count"] = JsonPrimitive((detail["laps"] as? JsonArray)?.size ?: 0)

        val activity = decode(JsonObject(fields), ActivityDetail.serializer()) ?: return null
        return ActivityPieces(
            detail = activity,
            laps = rows(detail["laps"], Lap.serializer()),
            climbs = rows(detail["climb_splits"], ClimbSplit.serializer()),
            sets = rows(detail["strength_sets"], StrengthSet.serializer()),
            track = thin((detail["data_points"] as? JsonArray).orEmpty(), maxPoints)
                .mapNotNull { decode(it as? JsonObject ?: return@mapNotNull null, TrackPoint.serializer()) },
        )
    }

    /** Child rows, each given an id by position — they have no identity of their own to sync. */
    private fun <T> rows(array: JsonElement?, serializer: KSerializer<T>): List<T> =
        (array as? JsonArray).orEmpty().mapIndexedNotNull { i, e ->
            val o = e as? JsonObject ?: return@mapIndexedNotNull null
            decode(JsonObject(o + ("id" to JsonPrimitive(i + 1))), serializer)
        }

    /** At most [max] points, evenly strided, always keeping the last — `max_points` on the track endpoint. */
    private fun thin(points: List<JsonElement>, max: Int): List<JsonElement> {
        if (points.size <= max || max <= 0) return points
        val step = (points.size + max - 1) / max
        return points.filterIndexed { i, _ -> i % step == 0 || i == points.lastIndex }
    }

    private fun <T> decode(o: JsonObject, serializer: KSerializer<T>): T? =
        runCatching { TracksJson.decodeFromJsonElement(serializer, JsonObject(o.mapValues { LocalJson.integral(it.value) })) }.getOrNull()
}
