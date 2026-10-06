// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import com.tracks.app.WeatherLocationSync.Change
import com.tracks.app.WeatherLocationSync.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * When the phone writes its position into the synced settings row. Every
 * write is a stamped edit that syncs to the server and every other device, so
 * the rules are as much about what *not* to write as what to.
 */
class WeatherLocationSyncTest {

    private val monday = Instant.parse("2026-10-05T08:00:00Z")
    private val tuesday = Instant.parse("2026-10-06T08:00:00Z")
    private val home = Fix(46.87, -113.99, monday)

    @Test
    fun `a newer fix somewhere else is written`() {
        val trip = Fix(47.5, -111.3, tuesday)
        assertEquals(
            Change.Set(mapOf("lat" to 47.5, "lon" to -111.3, "at" to "2026-10-06T08:00:00Z")),
            WeatherLocationSync.change(home, trip, enabled = true, rowExists = true),
        )
    }

    @Test
    fun `standing still writes nothing, however often the app is opened`() {
        assertNull(WeatherLocationSync.change(home, home.copy(at = tuesday), enabled = true, rowExists = true))
    }

    @Test
    fun `an older fix cannot drag the location back`() {
        // A second phone with a stale OS cache, opened after the first synced.
        assertNull(WeatherLocationSync.change(home.copy(at = tuesday), Fix(10.0, 10.0, monday), true, true))
    }

    @Test
    fun `turning weather off clears what was stored, and then leaves it alone`() {
        assertEquals(Change.Clear, WeatherLocationSync.change(home, home, enabled = false, rowExists = true))
        assertNull(WeatherLocationSync.change(null, home, enabled = false, rowExists = true))
    }

    @Test
    fun `nothing is written before the account's settings row has arrived`() {
        // Writing would create a row of this phone's own ahead of the account's.
        assertNull(WeatherLocationSync.change(null, home, enabled = true, rowExists = false))
    }

    @Test
    fun `positions are rounded to about a kilometre before they are stored`() {
        assertEquals(46.88, WeatherLocationSync.round2(46.87654), 0.0)
        assertEquals(-113.99, WeatherLocationSync.round2(-113.98765), 0.0)
    }

    @Test
    fun `a malformed synced value is not trusted`() {
        assertNull(WeatherLocationSync.stored(mapOf("lat" to "46.87", "lon" to -113.99)))
        assertNull(WeatherLocationSync.stored(mapOf("lat" to 95.0, "lon" to 0.0)))
        assertNull(WeatherLocationSync.stored(listOf(1.0, 2.0)))
        assertEquals(home, WeatherLocationSync.stored(mapOf("lat" to 46.87, "lon" to -113.99, "at" to "2026-10-05T08:00:00Z")))
    }
}
