// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tracks.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The map's own symbol sheet, cropped into individual icons.
 *
 * ## Why fetch the sheet rather than ship the icons
 *
 * The legend has to be pixel-identical to the map or it is actively
 * misleading — a symbol drawn slightly differently in the key is a symbol
 * somebody will fail to recognise on the ground. Bundling copies guarantees
 * they diverge the first time the cartography changes and nobody remembers the
 * app has its own set. So both read the same sheet from the same server, which
 * is what the web legend does and for the same reason.
 *
 * ## Why one bitmap, sliced
 *
 * A sprite sheet is one PNG plus a manifest of rectangles. Decoding it once and
 * handing out crops costs a single request and a single decode for fifty
 * symbols; fetching them individually would be fifty round trips for a panel
 * somebody opens for ten seconds.
 */
class SpriteSheet private constructor(
    private val sheet: Bitmap,
    private val frames: Map<String, Frame>,
) {
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int)

    private val cache = mutableMapOf<String, ImageBitmap?>()

    /**
     * One symbol, or null when the sheet does not carry it.
     *
     * Null is routine rather than an error: the tables name every symbol the
     * cartography can draw, and a sheet built for a trimmed style legitimately
     * lacks some of them. The legend shows a placeholder dot and carries on.
     */
    fun icon(name: String): ImageBitmap? = cache.getOrPut(name) {
        val frame = frames[name] ?: return@getOrPut null
        runCatching {
            Bitmap.createBitmap(sheet, frame.x, frame.y, frame.width, frame.height)
                .asImageBitmap()
        }.getOrNull()
    }

    companion object {
        /**
         * Load and decode, off the main thread.
         *
         * Returns null on any failure — no network, no sheet on this server —
         * and the legend degrades to its line and area sections, which need no
         * sprite at all. A legend missing its point symbols is worth far more
         * than no legend.
         */
        suspend fun load(container: AppContainer): SpriteSheet? = withContext(Dispatchers.IO) {
            runCatching {
                val client = container.client()
                val manifest = JSONObject(client.spriteManifest())
                val bytes = client.spriteSheet()
                val sheet = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@runCatching null

                val frames = buildMap {
                    manifest.keys().forEach { name ->
                        val entry = manifest.optJSONObject(name) ?: return@forEach
                        put(
                            name,
                            Frame(
                                x = entry.optInt("x"),
                                y = entry.optInt("y"),
                                width = entry.optInt("width"),
                                height = entry.optInt("height"),
                            ),
                        )
                    }
                }
                SpriteSheet(sheet, frames)
            }.getOrNull()
        }
    }
}
