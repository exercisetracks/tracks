// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

/**
 * Handles for a track that has none. They are what somebody drags to re-draw
 * the line, so they must keep its shape and stay few enough to touch.
 */
class TrackHandlesTest {

    @Test
    fun `a straight line needs only its two ends`() {
        val line = (0..100).map { LatLng(10.0 + it * 0.0001, 20.0) }
        assertEquals(listOf(line.first(), line.last()), TrackHandles.fromLine(line))
    }

    @Test
    fun `a corner is kept, because it is the shape`() {
        val out = (0..50).map { LatLng(10.0 + it * 0.0001, 20.0) }
        val back = (1..50).map { LatLng(10.005, 20.0 + it * 0.0001) }
        val handles = TrackHandles.fromLine(out + back)
        assertTrue(LatLng(10.005, 20.0) in handles)
        assertEquals(3, handles.size)
    }

    @Test
    fun `a long winding line is cut down to something editable`() {
        val line = (0..5000).map { LatLng(10.0 + it * 0.0001, 20.0 + 0.001 * kotlin.math.sin(it / 7.0)) }
        val handles = TrackHandles.fromLine(line)
        assertTrue(handles.size <= TrackHandles.MAX_HANDLES)
        assertEquals(line.first(), handles.first())
        assertEquals(line.last(), handles.last())
    }
}
