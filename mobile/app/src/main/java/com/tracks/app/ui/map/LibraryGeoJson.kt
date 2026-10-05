// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.json.JSONObject

/**
 * The drawn tracks, with one of them restyled.
 *
 * ## The bug this exists for
 *
 * The map draws every saved track from a single GeoJSON source and colours each
 * line by the feature's own `color` property — see `drawLibrary`. That document
 * comes from `/courses/geojson` and was only ever refetched by `loadLibrary()`,
 * which runs when the library sheet opens. So recolouring a track updated the
 * list, the sheet, and the server, and left the line on the map its old colour
 * until the whole screen was closed and reopened. The app appeared to have
 * ignored an instruction it had in fact carried out.
 *
 * Refetching the document instead would be the obvious fix and the wrong one:
 * it is every coordinate of every track the user owns, it needs a network, and
 * a colour change with no signal would go back to doing nothing visible.
 * Changing the one property that changed costs no request and works in a tent.
 *
 * ## Why org.json
 *
 * Because this module holds the document as the text the server sent, precisely
 * so a megabyte of coordinates is never parsed into models it does not need.
 * `org.json` reads it, edits one field and writes it back without a schema.
 *
 * Returns null when there is nothing to draw yet, and the document unchanged
 * when it does not mention this track or cannot be read — a malformed edit that
 * blanked the map would be a far worse outcome than a stale colour.
 */
internal fun restyleCourse(
    geoJson: String?,
    courseId: Int,
    color: String? = null,
    name: String? = null,
): String? {
    if (geoJson.isNullOrBlank()) return geoJson
    if (color == null && name == null) return geoJson

    return runCatching {
        val root = JSONObject(geoJson)
        val features = root.optJSONArray("features") ?: return geoJson
        var touched = false
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            // The id is on the properties as well as on the feature — the
            // server writes both — and properties is the one the map's own
            // expressions read, so it is the one trusted here.
            if (properties.optInt("id", Int.MIN_VALUE) != courseId) continue
            color?.let { properties.put("color", it) }
            name?.let { properties.put("name", it) }
            touched = true
        }
        if (touched) root.toString() else geoJson
    }.getOrDefault(geoJson)
}
