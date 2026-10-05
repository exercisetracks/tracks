// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Finding the API behind whatever address the user typed.
 *
 * The case that matters is the one a status-code check gets wrong. Behind the
 * bundled Caddy, the bare origin does not 404 — it falls through to the web app
 * and answers `/capabilities` with **200 and an HTML page**. A client that
 * accepted the first 200 would "connect" to a server that has never heard of
 * it and fail later, somewhere less obvious. Verified against the real stack:
 * `curl localhost:4080/capabilities` returns 200 HTML while
 * `curl localhost:4080/api/capabilities` returns the JSON.
 */
class DiscoveryTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val htmlHeaders = headersOf(HttpHeaders.ContentType, "text/html")

    private val capabilities =
        """{"app":"tracks","server_version":"1.0.0","api_version":1,"min_client_api_version":1}"""

    private val spaPage = "<!doctype html><html><body>Tracks</body></html>"

    private fun engine(handler: (path: String) -> Pair<HttpStatusCode, Pair<String, Boolean>>) =
        MockEngine { request ->
            val (status, body) = handler(request.url.encodedPath)
            val (text, isJson) = body
            respond(text, status, if (isJson) jsonHeaders else htmlHeaders)
        }

    private fun json(body: String) = HttpStatusCode.OK to (body to true)
    private fun html(body: String) = HttpStatusCode.OK to (body to false)
    private fun notFound() = HttpStatusCode.NotFound to ("" to false)

    @Test
    fun `finds the API under slash api behind the reverse proxy`() = runTest {
        val e = engine { path ->
            if (path == "/api/capabilities") json(capabilities) else notFound()
        }
        assertEquals(
            "https://tracks.example.com/api",
            discoverApiBase("https://tracks.example.com", e),
        )
    }

    @Test
    fun `finds the API at the origin when the backend is exposed directly`() = runTest {
        val e = engine { path ->
            if (path == "/capabilities") json(capabilities) else notFound()
        }
        assertEquals("http://192.168.1.10:8000", discoverApiBase("http://192.168.1.10:8000", e))
    }

    @Test
    fun `is not fooled by a single-page app answering 200 with HTML`() = runTest {
        // The real failure mode. Caddy sends anything that is not /api to the
        // web app, which serves index.html with a 200 for unknown paths.
        val e = engine { path ->
            when (path) {
                "/api/capabilities" -> json(capabilities)
                else -> html(spaPage)
            }
        }
        assertEquals(
            "https://tracks.example.com/api",
            discoverApiBase("https://tracks.example.com", e),
        )
    }

    @Test
    fun `rejects a server that is not Tracks`() = runTest {
        // Valid JSON, wrong application. Pointing at some other service should
        // say so rather than fail later with a decoding error.
        val e = engine { json("""{"app":"something-else","server_version":"9","api_version":1,"min_client_api_version":1}""") }
        assertFailsWith<ServerNotFoundException> { discoverApiBase("https://example.com", e) }
    }

    @Test
    fun `rejects a host that only ever serves HTML`() = runTest {
        val e = engine { html(spaPage) }
        assertFailsWith<ServerNotFoundException> { discoverApiBase("https://example.com", e) }
    }

    @Test
    fun `rejects a host that answers nothing`() = runTest {
        val e = engine { notFound() }
        assertFailsWith<ServerNotFoundException> { discoverApiBase("https://example.com", e) }
    }

    @Test
    fun `an empty address is rejected without a request`() = runTest {
        val e = engine { json(capabilities) }
        assertFailsWith<ServerNotFoundException> { discoverApiBase("   ", e) }
    }

    @Test
    fun `a trailing slash does not produce a doubled path`() = runTest {
        val seen = mutableListOf<String>()
        val e = MockEngine { request ->
            seen += request.url.encodedPath
            if (request.url.encodedPath == "/api/capabilities") {
                respond(capabilities, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("", HttpStatusCode.NotFound, htmlHeaders)
            }
        }
        assertEquals(
            "https://tracks.example.com/api",
            discoverApiBase("https://tracks.example.com/", e),
        )
        assertEquals(listOf("/api/capabilities"), seen)
    }

    @Test
    fun `the proxied layout is tried first`() = runTest {
        // Both shapes answer here. /api wins because it is the documented
        // deployment, so the common case costs one request rather than two.
        val seen = mutableListOf<String>()
        val e = MockEngine { request ->
            seen += request.url.encodedPath
            respond(capabilities, HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("https://x.example.com/api", discoverApiBase("https://x.example.com", e))
        assertEquals(1, seen.size)
    }

    // ── A missing scheme ─────────────────────────────────────────────────────
    // Not an edge case. `10.0.0.5:4080` is what a self-hoster types, and it
    // used to produce "No Tracks server at 10.0.0.5:4080" against a server that
    // was running and reachable. Caught by typing it into the onboarding flow,
    // which is now the first thing anyone does with the app.

    @Test
    fun `a bare host and port is tried over both schemes`() {
        assertEquals(
            listOf(
                "https://10.0.0.5:4080/api",
                "https://10.0.0.5:4080",
                "http://10.0.0.5:4080/api",
                "http://10.0.0.5:4080",
            ),
            apiBaseCandidates("10.0.0.5:4080"),
        )
    }

    @Test
    fun `a bare host with no port also tries the default 4080`() {
        // A self-hoster who types just the LAN address, no port, is reaching the
        // backend the shipped compose exposes on 4080 — so it has to be among the
        // candidates, after the plain origin (a proxy on 443/80 is found first).
        val candidates = apiBaseCandidates("10.0.0.5")
        assertTrue("http://10.0.0.5:4080/api" in candidates, "missing 4080: $candidates")
        assertTrue("https://10.0.0.5:4080/api" in candidates, "missing 4080: $candidates")
        assertTrue(
            candidates.indexOf("https://10.0.0.5/api") < candidates.indexOf("https://10.0.0.5:4080/api"),
            "the plain origin should be tried before 4080: $candidates",
        )
    }

    @Test
    fun `an explicit port is never widened to 4080`() {
        // Someone who named a port meant it; do not also probe 4080.
        assertTrue(apiBaseCandidates("10.0.0.5:9000").none { it.contains(":4080") })
    }

    @Test
    fun `https is attempted before http`() {
        // Order, not merely presence. Guessing http first would quietly
        // downgrade a deployment that supports TLS, and the very next thing
        // this flow does is send a password.
        val candidates = apiBaseCandidates("tracks.example.com")
        assertTrue(
            candidates.indexOfFirst { it.startsWith("https://") } <
                candidates.indexOfFirst { it.startsWith("http://") },
            "http was tried before https: $candidates",
        )
    }

    @Test
    fun `an explicit scheme is never second-guessed`() {
        // Someone who typed http:// on purpose — a LAN box with no TLS — must
        // not have the app silently try https and report that as the address.
        assertEquals(
            listOf("http://10.0.0.5:4080/api", "http://10.0.0.5:4080"),
            apiBaseCandidates("http://10.0.0.5:4080"),
        )
    }

    @Test
    fun `a schemeless host resolves against a real answer`() = runTest {
        val seen = mutableListOf<String>()
        val e = MockEngine { request ->
            seen += request.url.toString()
            // Only plain http answers, as on a LAN deployment with no TLS.
            if (request.url.protocol.name == "http" &&
                request.url.encodedPath == "/api/capabilities"
            ) {
                respond(capabilities, HttpStatusCode.OK, jsonHeaders)
            } else {
                respond("", HttpStatusCode.NotFound, htmlHeaders)
            }
        }
        assertEquals("http://10.0.0.5:4080/api", discoverApiBase("10.0.0.5:4080", e))
    }

    @Test
    fun `the failure says what was actually tried`() = runTest {
        val e = engine { notFound() }
        val failure = assertFailsWith<ServerNotFoundException> {
            discoverApiBase("10.0.0.5:4080", e)
        }
        // The old message named two URLs that were never requested, which sent
        // at least one person looking in the wrong place.
        assertTrue(
            failure.message!!.contains("https://10.0.0.5:4080/api/capabilities"),
            "unhelpful failure: ${failure.message}",
        )
    }
}
