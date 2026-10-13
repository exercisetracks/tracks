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

    /**
     * The name and line of a GPX file — its track points, or its route points
     * if it has no track — or null when it is not a GPX with a line in it.
     * `[lng, lat]` or `[lng, lat, ele]` per point, as a track stores its line.
     */
    fun parse(bytes: ByteArray): Pair<String?, List<List<Double>>>? = runCatching {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(bytes.inputStream(), null)
        val trk = ArrayList<List<Double>>()
        val rte = ArrayList<List<Double>>()
        var name: String? = null
        var current: MutableList<Double>? = null
        var into: MutableList<List<Double>>? = null
        var depth = 0
        var nameDepth = -1
        var text = StringBuilder()
        while (true) {
            when (parser.next()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    depth++
                    text = StringBuilder()
                    when (parser.name.substringAfter(':')) {
                        "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            current = if (lat != null && lon != null) mutableListOf(lon, lat) else null
                            into = if (parser.name.endsWith("trkpt")) trk else rte
                        }
                        "name" -> if (name == null && current == null) nameDepth = depth
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> text.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfter(':')) {
                        "ele" -> current?.let { c -> text.toString().trim().toDoubleOrNull()?.let { if (c.size == 2) c += it } }
                        "trkpt", "rtept" -> { current?.let { into?.add(it) }; current = null }
                        "name" -> if (depth == nameDepth) { name = text.toString().trim().ifEmpty { null }; nameDepth = -1 }
                    }
                    depth--
                }
            }
        }
        val line = trk.ifEmpty { rte }
        if (line.size < 2) null else name to line
    }.getOrNull()

    /** One point of a GPX file that is a place rather than part of a line. */
    data class Place(val name: String, val lat: Double, val lng: Double, val ele: Double?)

    /**
     * The places in a GPX file with no line in it ([parse] returned null):
     * its waypoints, or else a lone track or route point. A one-point GPX is
     * the usual way a single spot is shared, and it used to be refused as a
     * file with no track. Unnamed places take the file's name ([base]),
     * numbered when there are several; a lone point takes its track's name.
     * The web reads files the same way (frontend utils/gpxImport.js).
     */
    fun places(bytes: ByteArray, base: String): List<Place> = runCatching {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(bytes.inputStream(), null)
        data class Pt(val lat: Double, val lng: Double, var ele: Double? = null, var name: String? = null)
        val wpts = ArrayList<Pt>()
        val lines = ArrayList<Pt>()
        var ownerName: String? = null
        var current: Pt? = null
        var inOwner = false
        var text = StringBuilder()
        while (true) {
            when (parser.next()) {
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT -> break
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (val tag = parser.name.substringAfter(':')) {
                        "wpt", "trkpt", "rtept" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            current = if (lat != null && lon != null) Pt(lat, lon) else null
                            current?.let { if (tag == "wpt") wpts += it else lines += it }
                        }
                        "trk", "rte" -> inOwner = true
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> text.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    when (parser.name.substringAfter(':')) {
                        "ele" -> current?.ele = value.toDoubleOrNull()
                        "name" -> when {
                            current != null -> current?.name = value.ifEmpty { null }
                            inOwner && ownerName == null -> ownerName = value.ifEmpty { null }
                        }
                        "wpt", "trkpt", "rtept" -> current = null
                    }
                }
            }
        }
        when {
            wpts.isNotEmpty() -> wpts.mapIndexed { i, w ->
                Place(w.name ?: if (wpts.size > 1) "$base ${i + 1}" else base, w.lat, w.lng, w.ele)
            }
            lines.size == 1 -> lines.first().let { listOf(Place(it.name ?: ownerName ?: base, it.lat, it.lng, it.ele)) }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** A file name for the system save dialog. */
    fun fileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "track" } + ".gpx"

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
