// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import com.tracks.core.api.RemotePlaylist
import com.tracks.core.api.RemotePlaylistList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The watch's built-in "Liked songs" and "Recently played" beside the server's
 * own playlists. A user who keeps smart playlists of the same names saw each
 * twice — the built-in ticked, their playlist not.
 */
class WatchPlaylistsTest {

    private fun remote(vararg names: Pair<String, String>) = RemotePlaylistList(
        playlists = names.map { (id, name) -> RemotePlaylist(id, name, 10) },
        starredCount = 460,
        recentCount = 50,
    )

    @Test
    fun `a built-in with a same-named playlist on the server is not offered twice`() {
        val out = watchPlaylistsFor(remote("p1" to "Liked Songs", "p2" to "Dancing"), emptySet())

        assertEquals(listOf("~recent", "p1", "p2"), out.playlists.map { it.id })
    }

    @Test
    fun `a tick on a dropped built-in moves to its twin`() {
        val out = watchPlaylistsFor(remote("p9" to "recently-played"), setOf("~recent", "~starred"))

        assertEquals(setOf("p9", "~starred"), out.selected)
    }

    @Test
    fun `built-ins carry the server's counts`() {
        val out = watchPlaylistsFor(remote(), emptySet())

        assertEquals(listOf(460, 50), out.playlists.map { it.songCount })
    }
}
