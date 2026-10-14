// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map.packs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tracks.core.api.PackCell
import com.tracks.core.api.PackEntry
import com.tracks.core.api.PackManifest
import com.tracks.core.api.PackResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PackStoreTest {

    private lateinit var store: PackStore
    private val fixture = File(javaClass.getResource("/pmtiles/fixture.pmtiles")!!.toURI())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir, "map-packs.json").delete()
        store = PackStore(app)
        // Plenty of room unless a test says otherwise.
        ShadowStatFs.registerStats(store.root().apply { mkdirs() }, 1_000_000, 900_000, 900_000)
    }

    private fun manifest(vararg cells: Pair<String, String>, shared: String? = "s1") = PackManifest(
        cells = cells.map { (cell, v) ->
            PackCell(cell, covered = true, layers = mapOf("basemap" to PackEntry(v, 100, null, "/api/maps/packs/$cell/basemap?v=$v")))
        },
        shared = if (shared == null) emptyMap()
        else mapOf("world" to PackEntry(shared, 100, null, "/api/maps/packs/shared/world?v=$shared")),
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A server that serves `body` honouring Range/If-Range, optionally dropping the connection. */
    private class FakeServer(var body: ByteArray, var etag: String, var dropAfter: Int? = null, var status: Int? = null) :
        PackDownloader.Fetch {
        val asked = mutableListOf<Pair<Long, String?>>()
        override suspend fun fetch(
            url: String, from: Long, ifRange: String?,
            onStatus: (Int, String?) -> Unit, sink: suspend (ByteArray, Int) -> Unit,
        ): PackResponse {
            asked += from to ifRange
            status?.let { return PackResponse(it, null, null) }
            val partial = from > 0 && ifRange == "\"$etag\""
            val start = if (partial) from.toInt() else 0
            val code = if (partial) 206 else 200
            onStatus(code, "\"$etag\"")
            var sent = 0
            for (i in start until body.size step 7) {
                val chunk = body.copyOfRange(i, minOf(i + 7, body.size))
                if (dropAfter != null && sent >= dropAfter!!) { dropAfter = null; throw IOException("connection reset") }
                sink(chunk, chunk.size)
                sent += chunk.size
            }
            return PackResponse(code, "\"$etag\"", (body.size - start).toLong())
        }
    }

    private fun downloader(server: PackDownloader.Fetch, refreshed: MutableList<String> = mutableListOf()) =
        PackDownloader(store, server) { refreshed += it.key }

    @Test
    fun a_pack_downloads_verifies_and_serves() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        val server = FakeServer(body, sha(body))
        assertTrue(downloader(server).run())
        val p = store.state.value.packs.getValue("8/4/4/basemap")
        assertTrue(p.current)
        assertArrayEquals(body, File(p.file!!).readBytes())
    }

    /** One pack dropping does not stop the others; it resumes in the same run. */
    @Test
    fun one_dropped_pack_does_not_stop_the_rest() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        val server = FakeServer(body, sha(body), dropAfter = 70_000)
        assertTrue(downloader(server).run())
        assertTrue(store.state.value.packs.values.all { it.current })
    }

    @Test
    fun a_dropped_download_resumes_where_it_stopped() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1", shared = null))
        val body = fixture.readBytes()
        val server = FakeServer(body, sha(body), dropAfter = 70_000)
        assertFalse(downloader(server).run())
        assertFalse(store.state.value.packs.getValue("8/4/4/basemap").current)
        assertTrue(downloader(server).run())
        val resumedFrom = server.asked.last { it.second != null }.first
        assertTrue("asked for the rest, not the whole", resumedFrom >= 70_000)
        assertArrayEquals(body, File(store.state.value.packs.getValue("8/4/4/basemap").file!!).readBytes())
    }

    @Test
    fun a_pack_that_changed_since_the_partial_starts_over() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1", shared = null))
        val old = fixture.readBytes()
        val server = FakeServer(old, sha(old), dropAfter = 50_000)
        downloader(server).run()
        val new = old.copyOf().also { it[200] = (it[200] + 1).toByte() }
        server.body = new; server.etag = sha(new)
        assertTrue(downloader(server).run())
        assertArrayEquals(new, File(store.state.value.packs.getValue("8/4/4/basemap").file!!).readBytes())
    }

    @Test
    fun a_damaged_download_is_never_served() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        downloader(FakeServer(body, "0".repeat(64))).run()
        val p = store.state.value.packs.getValue("8/4/4/basemap")
        assertNull(p.file)
        assertNotNull(p.error)
    }

    @Test
    fun a_pack_rebuilt_on_the_server_asks_for_the_manifest_again() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val refreshed = mutableListOf<String>()
        downloader(FakeServer(ByteArray(1), "x", status = 409), refreshed).run()
        assertTrue("8/4/4/basemap" in refreshed)
    }

    @Test
    fun a_full_phone_stops_with_the_reason_not_a_retry_loop() = runBlocking {
        // Free space just over the reserve: nothing more fits.
        ShadowStatFs.registerStats(store.root(), 1_000_000, 1, 1)
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        downloader(FakeServer(fixture.readBytes(), "x")).run()
        val p = store.state.value.packs.getValue("8/4/4/basemap")
        assertEquals(PackStore.State.FAILED, p.state)
        assertTrue(p.error!!.contains("space"))
    }

    @Test
    fun a_newer_version_keeps_the_old_file_serving_until_it_lands() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        downloader(FakeServer(body, sha(body))).run()
        store.applyManifest(manifest("8/4/4" to "v2"))
        val p = store.state.value.packs.getValue("8/4/4/basemap")
        assertEquals("v2", p.version)
        assertNotNull("still serving v1", p.file)
        assertFalse(p.current)
    }

    @Test
    fun removing_an_area_frees_only_what_no_other_area_needs() = runBlocking {
        val a = store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1", "8/4/5" to "v1"))
        store.addArea("B", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/5" to "v1"))
        store.removeArea(a.id)
        val keys = store.state.value.packs.keys
        assertFalse("8/4/4/basemap" in keys)
        assertTrue("8/4/5/basemap" in keys)
        assertTrue("shared/world" in keys)
    }

    @Test
    fun tiles_are_found_in_the_pack_that_holds_them() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        downloader(FakeServer(body, sha(body))).run()
        // The fixture holds z0-7, so the shared world pack answers low zooms.
        assertArrayEquals("3/1/1".toByteArray(), store.tile("basemap", 3, 1, 1))
        assertNull("nothing for an unsaved layer", store.tile("master_contours", 9, 10, 10))
    }

    @Test
    fun the_registry_survives_the_process_and_resumes_what_was_running() {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        store.markDownloading("8/4/4/basemap", 10)
        val again = PackStore(ApplicationProvider.getApplicationContext<Application>())
        assertEquals(PackStore.State.QUEUED, again.state.value.packs.getValue("8/4/4/basemap").state)
        assertEquals(1, again.state.value.areas.size)
    }

    @Test
    fun the_interceptor_answers_held_tiles_and_passes_the_rest_on() = runBlocking {
        store.addArea("A", listOf(0.0, 0.0, 1.0, 1.0), manifest("8/4/4" to "v1"))
        val body = fixture.readBytes()
        downloader(FakeServer(body, sha(body))).run()
        var reachedNetwork = 0
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(PackInterceptor { store })
            .addInterceptor { chain ->
                reachedNetwork++
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204).message("network").body(ByteArray(0).toResponseBody(null)).build()
            }
            .build()
        fun get(path: String) = client.newCall(okhttp3.Request.Builder()
            .url("https://tracks.example.com$path?v=123").build()).execute()
        get("/api/tiles/basemap/3/1/1.mvt").use { r ->
            assertEquals(200, r.code)
            assertArrayEquals("3/1/1".toByteArray(), r.body!!.bytes())
        }
        assertEquals("a held tile never reaches the network", 0, reachedNetwork)
        get("/api/tiles/master_overlay/12/700/1400.mvt").use { assertEquals(204, it.code) }
        assertEquals(1, reachedNetwork)
    }
}
