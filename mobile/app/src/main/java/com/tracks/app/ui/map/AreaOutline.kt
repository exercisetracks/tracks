// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

/**
 * The combined outline of downloaded areas: where boxes overlap, the lines
 * inside the overlap are gone, so several areas read as one shape.
 *
 * The same algorithm as the web's (frontend/src/pages/maps/utils/
 * areaOutline.js), with the same test cases, so the two draw alike. Every area
 * is a rectangle or a union of rectangles, so the union is exact on a grid:
 * the edge coordinates split the plane into cells, a cell is covered when its
 * centre is inside any area, and the outline is each cell edge with covered
 * ground on one side only, joined into runs. Display only — the areas, and
 * what is stored, are untouched.
 */
/** A ring of (lng, lat) points; a polygon is its rings, holes included. */
typealias AreaRing = List<Pair<Double, Double>>
typealias AreaPolygon = List<AreaRing>

object AreaOutline {

    fun box(w: Double, s: Double, e: Double, n: Double): AreaPolygon =
        listOf(listOf(w to s, e to s, e to n, w to n, w to s))

    /** Line segments, each a pair of (lng, lat) end points. */
    fun union(polygons: List<AreaPolygon>): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
        if (polygons.isEmpty()) return emptyList()
        val xs = polygons.flatMap { p -> p.flatMap { r -> r.map { it.first } } }.distinct().sorted()
        val ys = polygons.flatMap { p -> p.flatMap { r -> r.map { it.second } } }.distinct().sorted()
        val nx = xs.size - 1
        val ny = ys.size - 1
        if (nx <= 0 || ny <= 0) return emptyList()
        val grid = BooleanArray(nx * ny)
        for (i in 0 until nx) {
            val cx = (xs[i] + xs[i + 1]) / 2
            for (j in 0 until ny) {
                val cy = (ys[j] + ys[j + 1]) / 2
                grid[i * ny + j] = polygons.any { inside(it, cx, cy) }
            }
        }
        fun covered(i: Int, j: Int) = i in 0 until nx && j in 0 until ny && grid[i * ny + j]
        val out = ArrayList<Pair<Pair<Double, Double>, Pair<Double, Double>>>()
        for (j in 0..ny) {
            var start = -1
            for (i in 0..nx) {
                val edge = i < nx && covered(i, j - 1) != covered(i, j)
                if (edge && start < 0) start = i
                if (!edge && start >= 0) { out += (xs[start] to ys[j]) to (xs[i] to ys[j]); start = -1 }
            }
        }
        for (i in 0..nx) {
            var start = -1
            for (j in 0..ny) {
                val edge = j < ny && covered(i - 1, j) != covered(i, j)
                if (edge && start < 0) start = j
                if (!edge && start >= 0) { out += (xs[i] to ys[start]) to (xs[i] to ys[j]); start = -1 }
            }
        }
        return out
    }

    private fun inside(polygon: AreaPolygon, x: Double, y: Double): Boolean {
        var hit = false
        for (ring in polygon) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val (xi, yi) = ring[i]
                val (xj, yj) = ring[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) hit = !hit
                j = i
            }
        }
        return hit
    }
}
