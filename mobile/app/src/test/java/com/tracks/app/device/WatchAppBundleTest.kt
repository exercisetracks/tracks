// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

/** Robolectric for org.json, which is a stub on the plain JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WatchAppBundleTest {

    private val fenix6x = ByteArray(5000) { (it % 251).toByte() }
    private val fenix7 = ByteArray(4000) { (it % 241).toByte() }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The two assets, as watchapp/devices.py bundle writes them. */
    private fun assets(corruptSecond: Boolean = false): Map<String, ByteArray> {
        val stream = ByteArrayOutputStream()
        XZOutputStream(stream, LZMA2Options()).use { it.write(fenix6x); it.write(fenix7) }
        val index = JSONObject()
            .put("format", 1)
            .put("builds", JSONObject()
                .put("fenix6xpro", JSONObject().put("name", "fēnix 6X Pro").put("offset", 0)
                    .put("length", fenix6x.size).put("sha256", sha(fenix6x)))
                .put("fenix7", JSONObject().put("name", "fēnix 7").put("offset", fenix6x.size)
                    .put("length", fenix7.size)
                    .put("sha256", if (corruptSecond) sha(fenix6x) else sha(fenix7))))
            // 3291 and 3516 are both the 6X Pro — the latter its APAC variant.
            .put("products", JSONObject().put("3291", "fenix6xpro").put("3516", "fenix6xpro")
                .put("3906", "fenix7"))
        return mapOf(
            WatchAppBundle.INDEX to index.toString().toByteArray(),
            WatchAppBundle.STREAM to stream.toByteArray(),
        )
    }

    private fun bundle(files: Map<String, ByteArray>) = WatchAppBundle { name ->
        files[name]?.inputStream() ?: throw FileNotFoundException(name)
    }

    @Test
    fun `each watch gets the build compiled for its model`() {
        val b = bundle(assets())
        val found = assertIs<WatchAppBundle.Lookup.Found>(b.forProduct(3906))
        assertEquals("fenix7", found.device)
        // Not the first build in the stream: the offset has to be honoured, or
        // every watch would be sent whichever build happens to come first.
        assertContentEquals(fenix7, found.bytes)
    }

    @Test
    fun `a regional variant gets the same build as its sibling`() {
        val b = bundle(assets())
        val apac = assertIs<WatchAppBundle.Lookup.Found>(b.forProduct(3516))
        assertContentEquals(fenix6x, apac.bytes)
    }

    @Test
    fun `a watch with no build is unsupported, not an error`() {
        assertEquals(WatchAppBundle.Lookup.Unsupported(9999), bundle(assets()).forProduct(9999))
        assertTrue(!bundle(assets()).supports(9999))
    }

    @Test
    fun `an app built without a bundle says so`() {
        assertEquals(WatchAppBundle.Lookup.Missing, bundle(emptyMap()).forProduct(3291))
        assertEquals(0, bundle(emptyMap()).modelCount)
    }

    @Test
    fun `a build that fails its checksum is never handed out`() {
        // The watch says nothing useful about a corrupt install, so the phone
        // has to refuse before sending rather than leave the user to guess.
        assertFailsWith<IllegalStateException> { bundle(assets(corruptSecond = true)).forProduct(3906) }
    }

    /**
     * The real bundle, when a `watchapp/build.sh` has produced one: holds the
     * Python writer and this reader to the same format. Skipped in a checkout
     * that has not built the watch app — the bundle is a signed binary and is
     * not committed.
     */
    @Test
    fun `the shipped bundle has a verified build for the fenix 6X Pro`() {
        val dir = File("src/main/assets")
        assumeTrue(File(dir, WatchAppBundle.INDEX).isFile)
        val real = WatchAppBundle { File(dir, it).inputStream() }
        assumeTrue(real.supports(3291))
        val found = assertIs<WatchAppBundle.Lookup.Found>(real.forProduct(3291))
        assertEquals("fenix6xpro", found.device)
    }
}
