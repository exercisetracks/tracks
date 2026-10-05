// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

/**
 * A strength set's FIT category and subtype, as the name the server stores.
 *
 * Port of `backend/app/parsers/exercise_names/resolver.py`, over the same
 * tables (generated into [ExerciseNamesData]). The phone and the server must
 * name a set identically: the name is what an estimated 1RM and a progression
 * stage are keyed on, so "Barbell Back Squat" on one and "Squat" on the other
 * would be two lifts.
 */
internal object ExerciseNames {

    private val names: Map<Pair<String, Long>, String> by lazy {
        ExerciseNamesData.names.flatMap { it.split('\n') }.associate { line ->
            val (cat, sub, name) = line.split('\t', limit = 3)
            (cat to sub.toLong()) to name
        }
    }

    private val categoryIds: Map<Long, String> by lazy {
        ExerciseNamesData.categoryIds.flatMap { it.split('\n') }.associate { line ->
            val (id, cat) = line.split('\t', limit = 2)
            id.toLong() to cat
        }
    }

    /**
     * `decode_category`: an enum name passes through lower-cased; a raw
     * number is looked up, or kept as its own digits; "unknown" and 65534
     * mean there is none.
     */
    fun decodeCategory(raw: Any?): String? {
        if (raw == null) return null
        // Python checks `hasattr(raw, "name")`, which no value fitdecode
        // produces has — its enum values are plain strings. So a name goes the
        // `int()` route, fails, and falls back to `str(raw).lower()`.
        val name = try {
            val id = Py.int(raw)
            (categoryIds[id] ?: Py.str(raw)).lowercase()
        } catch (e: PyOverflow) {
            throw e
        } catch (e: PyError) {
            Py.str(raw).lowercase()
        }
        return if (name == "unknown" || name == "65534") null else name
    }

    /** `resolve_exercise_name`: the table's name, or the category prettified. */
    fun resolve(category: String?, subtype: Long?): String? {
        if (category == null) return null
        val cat = category.lowercase()
        if (cat == "unknown" || cat == "65534") return null
        if (subtype != null) {
            val name = names[cat to subtype]
            if (!name.isNullOrEmpty()) return name
        }
        return Py.title(cat.replace("_", " "))
    }
}
