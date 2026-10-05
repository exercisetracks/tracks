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

/**
 * What the user reads when the server says no. A music-server 500 once reached
 * the screen as Ktor's "Expected response body of the type … (Kotlin
 * reflection is not available)", which reads like a crash in the app and names
 * nothing the user can act on.
 */
class ServerErrorTest {

    private fun clientAnswering(status: HttpStatusCode, body: String, type: String) = TracksClient(
        baseUrl = "https://tracks.example.com",
        tokens = InMemoryTokenStore(StoredSession(accessToken = "t")),
        engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, type)) },
    )

    @Test
    fun `the server's own detail is the message`() = runTest {
        val client = clientAnswering(HttpStatusCode.BadRequest, """{"detail":"Wrong username or password"}""", "application/json")

        val e = assertFailsWith<ServerErrorException> {
            client.setMusicServer(MusicServerRequest("http://music", "alex", "x"))
        }
        assertEquals("Wrong username or password", e.message)
        assertEquals(400, e.status)
    }

    @Test
    fun `a plain-text crash says the server failed, not that decoding did`() = runTest {
        val client = clientAnswering(HttpStatusCode.InternalServerError, "Internal Server Error", "text/plain")

        val e = assertFailsWith<ServerErrorException> {
            client.setMusicServer(MusicServerRequest("http://music", "alex", "x"))
        }
        assertEquals("The server hit an error (500)", e.message)
    }
}
