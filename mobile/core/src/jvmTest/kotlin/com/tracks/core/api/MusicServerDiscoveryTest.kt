// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * What the music-server field makes of what people type, and how it tells a
 * music server from anything else that answers.
 */
class MusicServerDiscoveryTest {

    @Test
    fun `a bare host tries https, then http, each with the default ports after`() {
        assertEquals(
            listOf(
                "https://music.home", "https://music.home:4533", "https://music.home:4040", "https://music.home:4747",
                "http://music.home", "http://music.home:4533", "http://music.home:4040", "http://music.home:4747",
            ),
            MusicServerDiscovery.candidatesFor("  music.home/ "),
        )
    }

    @Test
    fun `an explicit scheme skips the other one, but still gets the default ports`() {
        assertEquals(
            listOf("https://music.example.com", "https://music.example.com:4533", "https://music.example.com:4040", "https://music.example.com:4747"),
            MusicServerDiscovery.candidatesFor("https://music.example.com/"),
        )
    }

    @Test
    fun `an explicit port or path is taken as written`() {
        assertEquals(listOf("http://10.0.0.5:4533"), MusicServerDiscovery.candidatesFor("http://10.0.0.5:4533"))
        assertEquals(listOf("https://box.home:4533", "http://box.home:4533"), MusicServerDiscovery.candidatesFor("box.home:4533"))
        assertEquals(listOf("https://box.home/navidrome", "http://box.home/navidrome"), MusicServerDiscovery.candidatesFor("box.home/navidrome"))
    }

    @Test
    fun `only a subsonic envelope counts, even a failed one`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.host) {
                // Navidrome with no credentials: a failure, but in the envelope.
                "navidrome.home" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":10}}}""")
                // A web server that answers everything with a page.
                "web.home" -> respond("<!doctype html><title>hi</title>", HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        assertEquals(
            "https://navidrome.home",
            MusicServerDiscovery.find(listOf("https://web.home", "https://navidrome.home", "http://navidrome.home"), engine = engine),
        )
        assertNull(MusicServerDiscovery.find(listOf("https://web.home", "https://nothing.home"), engine = engine))
    }

    @Test
    fun `the first in the order given wins, not the first to answer`() = runBlocking {
        val engine = MockEngine { respond("""{"subsonic-response":{"status":"ok"}}""") }
        assertEquals("https://a", MusicServerDiscovery.find(listOf("https://a", "http://a"), engine = engine))
    }
}
