// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The phone's own Subsonic client: that it logs in the way every music server
 * checks, and that a failure reads as a reason rather than as data.
 */
class SubsonicClientTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `the token is md5 of password and salt, and the password never travels`() = runBlocking {
        var seen: io.ktor.http.Url? = null
        val engine = MockEngine { request ->
            seen = request.url
            respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""", headers = json)
        }
        assertEquals("navidrome", SubsonicClient("http://music:4533/", "alex", "s3cret", engine).ping())

        val url = seen!!
        assertEquals("/rest/ping.view", url.encodedPath)
        val salt = url.parameters["s"]!!
        val expected = MessageDigest.getInstance("MD5").digest("s3cret$salt".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, url.parameters["t"])
        assertTrue("s3cret" !in url.toString())
    }

    @Test
    fun `a wrong login says so instead of returning an empty list`() = runBlocking {
        val engine = MockEngine {
            respond("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong"}}}""", headers = json)
        }
        val e = assertFailsWith<SubsonicException> { SubsonicClient("http://music", "a", "b", engine).playlists() }
        assertEquals("Music server rejected the username or password", e.message)
    }

    @Test
    fun `a redirect is reported, not followed with the login attached`() = runBlocking {
        val engine = MockEngine {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/rest/ping.view"))
        }
        val e = assertFailsWith<SubsonicException> { SubsonicClient("http://music", "a", "b", engine).ping() }
        assertTrue(e.message!!.contains("elsewhere.example"))
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `playlists and search parse into the phone's models`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/getPlaylists.view" -> respond(
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"p1","name":"Run","songCount":12}]}}}""",
                    headers = json,
                )
                else -> respond(
                    """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"s1","title":"Song","albumId":"al1"}]}}}""",
                    headers = json,
                )
            }
        }
        val client = SubsonicClient("http://music", "a", "b", engine)
        assertEquals(listOf(RemotePlaylist("p1", "Run", 12)), client.playlists())
        assertEquals(listOf(RemoteSong("s1", "Song", null, null, "al1")), client.searchSongs("song"))
    }

    @Test
    fun `recently played is null off Navidrome rather than an error`() = runBlocking {
        val engine = MockEngine { respond("not found", HttpStatusCode.NotFound) }
        assertNull(SubsonicClient("http://music", "a", "b", engine).recentlyPlayedCount())
    }
}
