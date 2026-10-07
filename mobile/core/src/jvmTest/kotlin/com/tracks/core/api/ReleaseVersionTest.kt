// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's half of "which side is behind". The cases mirror the server's
 * (backend tests/test_api/test_version_status.py), because a phone and a web
 * page disagreeing about whether an update exists is worse than either being
 * wrong alone.
 */
class ReleaseVersionTest {

    @Test
    fun `a newer version is newer`() {
        listOf(
            "1.2.0" to "1.1.9",
            "1.10.0" to "1.9.0",
            "v2.0.0" to "1.99.99",
            "1.2.0" to "1.2.0-beta.1",
            "1.2.0-beta.2" to "1.2.0-beta.1",
        ).forEach { (newer, older) ->
            assertTrue(ReleaseVersion.isNewer(newer, older), "$newer > $older")
            assertFalse(ReleaseVersion.isNewer(older, newer), "$older < $newer")
        }
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(ReleaseVersion.isNewer("1.1.3", "v1.1.3"))
    }

    @Test
    fun `a debug build's suffix does not make it a different version`() {
        // versionNameSuffix = "-debug" in app/build.gradle.kts. Read as a
        // pre-release label it would sort below the release it was built
        // from, and every debug build would be told to update to itself.
        assertFalse(ReleaseVersion.isNewer("1.1.3", ReleaseVersion.ofBuild("1.1.3-debug")))
        assertEquals("1.1.3-beta.1", ReleaseVersion.ofBuild("1.1.3-beta.1"))
    }

    @Test
    fun `an unparseable version is never an update`() {
        assertFalse(ReleaseVersion.isNewer("nightly", "1.0.0"))
        assertFalse(ReleaseVersion.isNewer("2.0.0", null))
        assertNull(ReleaseVersion.parse("nightly"))
    }

    @Test
    fun `every request says which app sent it`() = runBlocking {
        // The server lists each phone with the version this header reports;
        // a request that leaves it off makes the web show the phone as
        // whatever it last was.
        var seen: String? = null
        val engine = MockEngine { request ->
            seen = request.headers[CLIENT_VERSION_HEADER]
            respond(
                """{"app":"tracks","server_version":"1.1.3","api_version":2,"min_client_api_version":2}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = TracksClient(
            baseUrl = "http://tracks.test",
            tokens = InMemoryTokenStore(),
            engine = engine,
            clientVersion = "android/1.1.3 (10103)",
        )
        client.capabilities()
        assertEquals("android/1.1.3 (10103)", seen)
    }
}
