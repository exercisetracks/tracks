// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The reader against an archive written by the reference `pmtiles` Python
 * writer: 21,845 tiles, enough for leaf directories, gzip-compressed
 * directories, and runs of identical tiles.
 */
class PmTilesTest {

    private val fixture = File(javaClass.getResource("/pmtiles/fixture.pmtiles")!!.toURI())
    private val ids = javaClass.getResource("/pmtiles/fixture_ids.txt")!!.readText().lines()
        .filter { it.isNotBlank() }.map { l -> l.split(" ").map(String::toLong) }

    @Test
    fun tile_ids_match_the_reference_implementation() {
        for ((z, x, y, id) in ids) assertEquals("$z/$x/$y", id, PmTiles.tileId(z.toInt(), x.toInt(), y.toInt()))
    }

    @Test
    fun every_tile_comes_back_as_written_including_runs_and_leaves() {
        PmTiles(fixture).use { p ->
            for ((z, x, y, _) in ids) {
                val got = p.tileDecoded(z.toInt(), x.toInt(), y.toInt())
                val want = if ((x + y) % 3 != 0L) "$z/$x/$y" else "same"
                assertArrayEquals("$z/$x/$y", want.toByteArray(), got)
            }
        }
    }

    @Test
    fun a_tile_the_archive_does_not_hold_is_null() {
        PmTiles(fixture).use { p -> assertNull(p.tile(9, 0, 0)) }
    }
}
