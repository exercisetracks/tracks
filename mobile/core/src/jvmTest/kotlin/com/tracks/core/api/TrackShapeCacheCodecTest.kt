// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackShapeCacheCodecTest {
    @Test
    fun a_cached_outline_and_a_known_absence_both_survive_a_round_trip() {
        val shape = TrackShape(listOf(0f to 0.25f, 1f to 0.75f), insetX = 0f, insetY = 0.25f)
        val back = TrackShapes.decodeCache(TrackShapes.encodeCache(mapOf("a" to shape, "b" to null)))
        assertEquals(mapOf("a" to shape, "b" to null), back)
    }

    @Test
    fun a_damaged_cache_file_is_an_empty_cache_not_a_crash() {
        assertEquals(emptyMap(), TrackShapes.decodeCache("{not json"))
    }
}
