// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.Py
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Python dict semantics for the planners' dynamic rows.
 *
 * ## Why the planners keep dicts rather than data classes
 *
 * An exercise-library row, a strength record and an archetype's rep scheme
 * reach the Python as plain dicts, and the planners read them with
 * `ex.get("is_compound", True)` — where a key that is *present and None* reads
 * as None (falsy) and a *missing* key reads as True. A data class with a
 * default cannot tell those apart, and custom exercises really do carry
 * `is_compound: None`. So these stay maps, values typed the way JSON decodes
 * them (Long for an int, Double for a float), and every read goes through
 * [get] so present-and-null and absent keep their different meanings.
 *
 * Numbers keep their Python type for the same reason: a plan step's `sets` is
 * `4` from a scheme but `2` from a floor division, and `target_rpe` can be
 * `min(7, 6)`. The server's JSON writes `6` and `6.0` differently, so the
 * phone has to know which it holds.
 */
internal typealias Dict = Map<String, Any?>

/** `d.get(key, default)`. */
internal fun Dict.get(key: String, default: Any?): Any? = if (containsKey(key)) this[key] else default

internal fun truthy(v: Any?): Boolean = Py.truthy(v)

/** A Python number (bool counts, as in Python) as a Double for comparison. */
internal fun num(v: Any?): Double = when (v) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is Boolean -> if (v) 1.0 else 0.0
    else -> throw IllegalArgumentException("not a number: $v")
}

/** `min(a, b)` keeping whichever object Python would return: the first on a tie. */
internal fun pyMin(a: Any, b: Any): Any = if (num(b) < num(a)) b else a

/** `max(a, b)`, likewise first on a tie. */
internal fun pyMax(a: Any, b: Any): Any = if (num(b) > num(a)) b else a

/** `a // b` for a Python int or float against an int. */
internal fun pyFloorDiv(a: Any, b: Long): Any = when (a) {
    is Long -> a.floorDiv(b)
    else -> kotlin.math.floor(num(a) / b)
}

/** A JSON value as the planners hold it: Long, Double, String, Boolean, null, List, Map. */
internal fun dyn(e: JsonElement): Any? = when (e) {
    JsonNull -> null
    is JsonObject -> e.mapValuesTo(LinkedHashMap()) { dyn(it.value) }
    is JsonArray -> e.map(::dyn)
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.content.any { it == '.' || it == 'e' || it == 'E' } -> e.content.toDouble()
        else -> e.content.toLong()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun strList(v: Any?): List<String> = (v as List<Any?>).map { it as String }

/** Python's `v in s` for a string set and a value of any type. */
internal fun Set<String>.has(v: Any?): Boolean = v is String && v in this
