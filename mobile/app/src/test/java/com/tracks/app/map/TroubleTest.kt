// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.offline.OfflineRegionError

/**
 * What MapLibre's download errors actually mean.
 *
 * ## The bug this is the fix for
 *
 * Every error was treated as the end of the download: the flow reported a
 * failure and closed, the sheet said "Could not store it on the phone", and the
 * download carried on in the background regardless, because MapLibre had never
 * agreed to stop.
 *
 * The most common error is a 404, and Tracks' own tile server produces them by
 * the thousand on purpose — go-pmtiles answers 204 for a tile it has no data
 * for and Caddy turns that into a 404 (see caddy/Caddyfile). Every extracted
 * region has an edge, and every region has empty ground inside it, so *every*
 * download announced itself as failed within seconds of starting. There was no
 * working case to compare against, so saving an area simply looked broken.
 *
 * MapLibre's own behaviour is the specification here, and it is not symmetric:
 * a 404 drops the requirement and moves on (offline_download.cpp: "On error
 * 404, we skip this request and go further"), while every other error keeps the
 * request and retries it on a backoff. Neither is a reason to give up.
 */
class TroubleTest {

    @Test
    fun `a missing tile is not a failure`() {
        assertEquals(Trouble.Missing, troubleOf(OfflineRegionError.REASON_NOT_FOUND))
    }

    @Test
    fun `losing the connection is something to wait out`() {
        assertEquals(Trouble.Transient, troubleOf(OfflineRegionError.REASON_CONNECTION))
    }

    @Test
    fun `a server error is retried, not fatal`() {
        assertEquals(Trouble.Transient, troubleOf(OfflineRegionError.REASON_SERVER))
    }

    @Test
    fun `anything unrecognised is treated as worth retrying`() {
        // The safe default in both directions: MapLibre keeps the request, so
        // reporting a failure would be a lie, and the download either recovers
        // or sits visibly waiting. Nothing is lost either way.
        assertEquals(Trouble.Transient, troubleOf(OfflineRegionError.REASON_OTHER))
        assertEquals(Trouble.Transient, troubleOf(null))
        assertEquals(Trouble.Transient, troubleOf("REASON_SOMETHING_NEW"))
    }
}
