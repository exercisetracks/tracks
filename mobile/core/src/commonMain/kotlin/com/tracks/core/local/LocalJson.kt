// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.fit.decode.FitTimeOfDay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parsers' output, as the JSON the server's API would have sent for it.
 *
 * The parsers return Python-shaped maps (see com.tracks.core.parse) so that
 * their parity tests compare like with like. Screens want the API's models,
 * which already decode the server's JSON — so the cheapest faithful bridge is
 * to write the maps out as that JSON: datetimes as `isoformat()`, dates as
 * `YYYY-MM-DD`, exactly what FastAPI serialises the same values to.
 */
object LocalJson {

    fun of(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value.isNaN() || value.isInfinite()) JsonNull else JsonPrimitive(value)
        is Float -> of(value.toDouble())
        is Number -> JsonPrimitive(value)
        is FitDateTime -> JsonPrimitive(value.isoformat())
        is CivilDate -> JsonPrimitive(value.isoformat())
        is FitTimeOfDay -> JsonPrimitive(value.isoformat())
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to of(v) })
        is Iterable<*> -> JsonArray(value.map { of(it) })
        is Array<*> -> JsonArray(value.map { of(it) })
        is ByteArray -> JsonArray(value.map { JsonPrimitive(it.toInt() and 0xff) })
        else -> JsonPrimitive(value.toString())
    }

    fun obj(value: Map<String, Any?>): JsonObject = of(value) as JsonObject

    /**
     * `8000.0` as `8000`. The parsers keep Python's types and replicas keep
     * whatever JSON a writer sent, so a float that happens to be whole would
     * fail to decode into an `Int` field — steps, calories — where the
     * server's typed columns had already cast it.
     */
    fun integral(e: JsonElement): JsonElement {
        val p = e as? JsonPrimitive ?: return e
        if (p.isString) return p
        val d = p.content.toDoubleOrNull() ?: return p
        return if (p.content.contains('.') && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) {
            JsonPrimitive(d.toLong())
        } else {
            p
        }
    }
}
