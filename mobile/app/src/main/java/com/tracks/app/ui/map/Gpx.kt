// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

/**
 * GPX 1.1 for one track or one place, for handing to another app.
 *
 * Written by hand rather than through an XML library: two element shapes, and
 * the only thing that can go wrong is an unescaped name.
 */
object Gpx {
    private const val HEAD = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Tracks" xmlns="http://www.topografix.com/GPX/1/1">
"""

    /** `[lng, lat, ele?]` per vertex, as the track stores its line. */
    fun track(name: String, line: List<List<Double?>>): String = buildString {
        append(HEAD)
        append("  <trk>\n    <name>").append(escape(name)).append("</name>\n    <trkseg>\n")
        for (p in line) {
            val lng = p.getOrNull(0) ?: continue
            val lat = p.getOrNull(1) ?: continue
            append("      <trkpt lat=\"").append(lat).append("\" lon=\"").append(lng).append("\">")
            p.getOrNull(2)?.let { append("<ele>").append(it).append("</ele>") }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    fun place(name: String, lat: Double, lng: Double, ele: Double?): String = buildString {
        append(HEAD)
        append("  <wpt lat=\"").append(lat).append("\" lon=\"").append(lng).append("\">")
        ele?.let { append("<ele>").append(it).append("</ele>") }
        append("<name>").append(escape(name)).append("</name></wpt>\n</gpx>\n")
    }

    /** A file name for the system save dialog. */
    fun fileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "track" } + ".gpx"

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
