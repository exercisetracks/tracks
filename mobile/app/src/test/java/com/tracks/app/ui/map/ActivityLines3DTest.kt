// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityLines3DTest {

    /** The glow is a straight-down, north-up picture; that is the only view it may draw. */
    @Test
    fun `the glow keeps the flat north-up view`() {
        assertTrue(ActivityLines3D.isFlat(tilt = 0.0, bearing = 0.0))
        assertTrue(ActivityLines3D.isFlat(tilt = 0.0, bearing = 359.6))
    }

    /** Tilted, activities vanished: the terrain does not drape the glow's image. */
    @Test
    fun `a tilted or turned map draws lines instead`() {
        assertFalse(ActivityLines3D.isFlat(tilt = 30.0, bearing = 0.0))
        assertFalse(ActivityLines3D.isFlat(tilt = 0.0, bearing = 45.0))
        assertFalse(ActivityLines3D.isFlat(tilt = 0.0, bearing = -90.0))
    }
}
