// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

/**
 * The Tracks Music builds this app carries, one per watch model, and the
 * choice between them.
 *
 * A Connect IQ app is compiled per device — its screen, its fonts, its memory
 * — and a build for the wrong model either refuses to install or installs and
 * misdraws. So the app ships every build `watchapp/build.sh all` produced, and
 * picks by the product number the watch announced in its handshake.
 *
 * ## The format
 *
 * `watchapp/devices.py bundle` writes two assets:
 *
 * - `index.json`: product number → build id, and per build its byte range
 *   in the stream and a sha256.
 * - `TracksMusic.xz`: every build back to back, in one xz stream.
 *
 * One stream rather than a file per build because the builds are nearly
 * identical, and only a compressor that sees all of them at once can tell: 93
 * builds are 6.8 MB compressed one by one, and about 0.2 MB as one stream.
 * Reading one build means decompressing up to its end, which is ~17 MB of
 * output at most and well under a second on a phone — once per install, which
 * already takes minutes over Bluetooth.
 *
 * The sha256 is checked before anything leaves the phone. A wrong slice of the
 * stream would otherwise go to the watch as a corrupt app, and the watch says
 * nothing useful about why an install failed.
 */
class WatchAppBundle(private val open: (String) -> InputStream) {

    sealed interface Lookup {
        /** [bytes] is the verified build for [device], named [name] for display. */
        class Found(val device: String, val name: String, val bytes: ByteArray) : Lookup

        /** This app has no build for the watch. Not a failure to retry. */
        data class Unsupported(val productNumber: Int) : Lookup

        /** This app was built without a bundle — see `watchapp/build.sh`. */
        data object Missing : Lookup
    }

    private class Build(val name: String, val offset: Long, val length: Int, val sha256: String)

    private class Index(val builds: Map<String, Build>, val products: Map<Int, String>)

    private val index: Index? by lazy { readIndex() }

    /** How many watch models this app carries a build for. */
    val modelCount: Int get() = index?.builds?.size ?: 0

    /** Whether there is a build for [productNumber], without unpacking anything. */
    fun supports(productNumber: Int): Boolean = index?.products?.containsKey(productNumber) == true

    fun forProduct(productNumber: Int): Lookup {
        val index = index ?: return Lookup.Missing
        val id = index.products[productNumber] ?: return Lookup.Unsupported(productNumber)
        val build = index.builds.getValue(id)
        return Lookup.Found(id, build.name, extract(build))
    }

    private fun readIndex(): Index? {
        val text = try {
            open(INDEX).use { it.readBytes().decodeToString() }
        } catch (e: java.io.IOException) {
            return null
        }
        val json = JSONObject(text)
        require(json.getInt("format") == FORMAT) { "watch app bundle format ${json.getInt("format")}" }
        val builds = json.getJSONObject("builds").let { o ->
            o.keys().asSequence().associateWith { id ->
                val b = o.getJSONObject(id)
                Build(b.getString("name"), b.getLong("offset"), b.getInt("length"), b.getString("sha256"))
            }
        }
        val products = json.getJSONObject("products").let { o ->
            o.keys().asSequence().associate { it.toInt() to o.getString(it) }
        }
        return Index(builds, products)
    }

    private fun extract(build: Build): ByteArray {
        val bytes = ByteArray(build.length)
        open(STREAM).use { raw ->
            // The dictionary is 4 MiB (see devices.py); the limit is only a
            // guard against a malformed stream asking for far more.
            XZInputStream(raw.buffered(), MEMORY_LIMIT_KIB).use { xz ->
                var skip = build.offset
                while (skip > 0) {
                    val n = xz.skip(skip)
                    check(n > 0) { "watch app bundle ends before ${build.name}" }
                    skip -= n
                }
                var read = 0
                while (read < bytes.size) {
                    val n = xz.read(bytes, read, bytes.size - read)
                    check(n > 0) { "watch app bundle ends inside ${build.name}" }
                    read += n
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(digest == build.sha256) { "watch app build for ${build.name} is corrupt" }
        return bytes
    }

    companion object {
        const val INDEX = "watchapp/index.json"
        const val STREAM = "watchapp/TracksMusic.xz"
        private const val FORMAT = 1
        private const val MEMORY_LIMIT_KIB = 16 * 1024
    }
}
