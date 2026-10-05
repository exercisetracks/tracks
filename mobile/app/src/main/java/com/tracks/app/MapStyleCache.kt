// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The last map style the server gave us, on disk.
 *
 * Small, but it is the difference between a Map tab that opens in a valley and
 * one that does not: MapLibre cannot render anything without a style, and the
 * style is fetched over the network. Tiles have their own offline story (a
 * downloaded region); this is only about the document that describes how to
 * draw them.
 *
 * Written whole rather than merged. A style is a single server-authored
 * document with an internally consistent set of layers and sources — half of a
 * new one on top of half of an old one is not a style, so a failed write
 * leaves the previous copy untouched by going through a temp file.
 */
class MapStyleCache(context: Context) {

    private val file = File(context.filesDir, "map-style.json")

    fun read(): String? = runCatching {
        if (file.isFile) file.readText() else null
    }.getOrElse {
        Log.w(TAG, "Could not read the cached map style", it)
        null
    }

    fun write(json: String) {
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                // renameTo is atomic within a filesystem but can still fail;
                // falling back keeps the cache fresh rather than silently stale.
                file.writeText(json)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Could not cache the map style", it) }
    }

    private companion object {
        const val TAG = "TracksMapStyle"
    }
}
