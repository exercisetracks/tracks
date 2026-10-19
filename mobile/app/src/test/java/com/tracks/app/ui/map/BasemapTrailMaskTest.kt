// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inside a downloaded area the basemap drew a second line under every trail. */
class BasemapTrailMaskTest {

    @Test
    fun `no areas leave the basemap trails as they are`() {
        assertNull(BasemapTrailMask.outsideJson(emptyList()))
    }

    @Test
    fun `the areas become one within test the trails must fail`() {
        val json = BasemapTrailMask.outsideJson(listOf(AreaOutline.box(-117.0, 47.0, -116.0, 48.0)))
        assertEquals(
            """["!",["within",{"type":"MultiPolygon","coordinates":[[[[-117.0,47.0],[-116.0,47.0],[-116.0,48.0],[-117.0,48.0],[-117.0,47.0]]]]}]]""",
            json,
        )
    }

    @Test
    fun `it masks the same layers the browser does`() {
        // frontend style/layers/trails.js buildTrails, in order.
        assertEquals(8, BasemapTrailMask.LAYERS.size)
        assertEquals("trails_labels", BasemapTrailMask.LAYERS.last())
    }
}
