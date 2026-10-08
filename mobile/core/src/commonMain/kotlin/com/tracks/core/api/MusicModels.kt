// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Music: the server it comes from, and the watch app that plays it.
 *
 * Only that. The USB library — tracks carried over a cable, smart playlists,
 * the device plan — is the web app's: a phone cannot push files into a
 * watch's own library, so it had no business choosing what to carry there.
 *
 * Every field is nullable or defaulted for the usual reason ([ModelShapeTest]
 * enforces it): a server that stops sending one should cost a hidden row, not a
 * parse failure that empties the screen.
 */

/** What the phone knows about the user's music server. Never the password. */
@Serializable
data class MusicServer(
    val url: String? = null,
    val username: String? = null,
    val configured: Boolean = false,
    @SerialName("auto_rotate") val autoRotate: Boolean = false,
    @SerialName("rotate_count") val rotateCount: Int = 40,
    @SerialName("last_run") val lastRun: String? = null,
)

@Serializable
data class MusicServerRequest(
    val url: String,
    val username: String,
    /** Omitted to keep whatever is already stored. */
    val password: String? = null,
)

@Serializable
data class MusicServerResult(
    val connected: Boolean = false,
    val server: String? = null,
    val url: String? = null,
)

@Serializable
data class RemotePlaylist(
    val id: String = "",
    val name: String = "",
    @SerialName("song_count") val songCount: Int = 0,
)

@Serializable
data class RemotePlaylistList(
    val playlists: List<RemotePlaylist> = emptyList(),
    /** Songs behind the watch's built-in "Liked songs"; null when the server could not say. */
    @SerialName("starred_count") val starredCount: Int? = null,
    /** Songs the watch's built-in "Recently played" would take; null off Navidrome. */
    @SerialName("recent_count") val recentCount: Int? = null,
)

/** One song on the music server, from a search. [albumId] is what the watch keys cover art on. */
@Serializable
data class RemoteSong(
    val id: String = "",
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_id") val albumId: String? = null,
)

@Serializable
data class RemoteSongList(val songs: List<RemoteSong> = emptyList())

/**
 * What the on-watch music app needs: the music server and how to log in.
 *
 * The one response that carries the password. The phone forwards it to the
 * watch over Bluetooth and keeps nothing — the watch logs in to the music
 * server by itself, with no Tracks server in the path.
 */
@Serializable
data class MusicWatchConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
)
