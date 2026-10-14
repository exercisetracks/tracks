// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Answers MapLibre's requests for tiles, glyphs and the sprite from the map
 * packs on this phone, before they reach the network.
 *
 * Installed on the OkHttp client MapLibre uses (TracksApplication), so the
 * served style needs no change: the same `/api/tiles/{set}/{z}/{x}/{y}` URLs
 * are answered here when a pack holds them, whatever the host, port or query
 * string. That is also what makes saved maps immune to the server's address
 * changing or its tile versions moving — the two ways tiles saved by URL in
 * MapLibre's own offline database were lost (server audit F1, F9).
 *
 * A request no pack holds goes to the network as it always did.
 */
class PackInterceptor(private val store: () -> PackStore?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val local = runCatching { answer(request.url.encodedPath) }.getOrNull()
        if (local != null) {
            val (bytes, type) = local
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK (offline pack)")
                // Long-lived, so MapLibre's own cache does not keep asking.
                .header("Cache-Control", "max-age=86400")
                .body(bytes.toResponseBody(type.toMediaType()))
                .build()
        }
        return chain.proceed(request)
    }

    private fun answer(path: String): Pair<ByteArray, String>? {
        val s = store() ?: return null
        TILE.matchEntire(path)?.let { m ->
            val (set, z, x, y, ext) = m.destructured
            val bytes = s.tile(set, z.toInt(), x.toInt(), y.toInt()) ?: return null
            val type = when (ext) {
                "webp" -> "image/webp"
                "png" -> "image/png"
                else -> "application/x-protobuf"
            }
            return bytes to type
        }
        FONT.matchEntire(path)?.let { m ->
            val (stack, range) = m.destructured
            val bytes = s.asset("fonts/${java.net.URLDecoder.decode(stack, "UTF-8")}/$range.pbf") ?: return null
            return bytes to "application/x-protobuf"
        }
        SPRITE.matchEntire(path)?.let { m ->
            val (name, ext) = m.destructured
            val bytes = s.asset("sprite/$name.$ext") ?: return null
            return bytes to if (ext == "png") "image/png" else "application/json"
        }
        return null
    }

    companion object {
        private val TILE = Regex(""".*/api/tiles/([a-z0-9_]+)/(\d+)/(\d+)/(\d+)\.(mvt|pbf|webp|png)""")
        private val FONT = Regex(""".*/api/fonts/([^/]+)/(\d+-\d+)\.pbf""")
        private val SPRITE = Regex(""".*/api/sprite/([A-Za-z0-9_@-]+)\.(json|png)""")
    }
}
