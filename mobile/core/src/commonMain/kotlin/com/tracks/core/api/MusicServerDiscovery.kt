// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Finding a music server from whatever the user typed — or from nothing.
 *
 * A Navidrome address is the kind of thing people know as "the box in the
 * cupboard on port 4533": the scheme is a guess, the port is a default they
 * never chose, and often the address itself is one the phone can find faster
 * than they can. So the field takes anything — `music.example.com`,
 * `10.0.0.5`, a full URL — and this works out the rest, the same way the
 * Tracks-server field does ([discoverApiBase], [probeForTracks]).
 *
 * A server identifies itself by answering the Subsonic `ping` — with a
 * failure, since no credentials are sent, but a failure in the Subsonic
 * envelope. That is enough to tell a music server from a web page, and it
 * needs no password before one has been typed.
 */
object MusicServerDiscovery {

    /** The Subsonic family's defaults: Navidrome, then Airsonic/Subsonic, then Gonic. */
    val DEFAULT_PORTS = listOf(4533, 4040, 4747)

    private const val PING = "/rest/ping.view?v=1.16.1&c=Tracks&f=json"

    /**
     * Every base worth trying for what the user typed, most likely first.
     *
     * With a scheme, what they typed is tried as-is first. Without one, https
     * before http. A bare host also gets each default port — after the bare
     * form, because a name that resolves publicly is usually behind a proxy
     * on 443, and an IP on the LAN usually is not.
     */
    fun candidatesFor(input: String): List<String> {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()

        val withScheme = if (trimmed.contains("://")) listOf(trimmed) else listOf("https://$trimmed", "http://$trimmed")
        return withScheme.flatMap { base ->
            val afterScheme = base.substringAfter("://")
            val hasPort = afterScheme.substringBefore('/').contains(':')
            val hasPath = afterScheme.contains('/')
            if (hasPort || hasPath) {
                listOf(base)
            } else {
                listOf(base) + DEFAULT_PORTS.map { "$base:$it" }
            }
        }.distinct()
    }

    /** What to try on every host of a LAN sweep: plain http on each default port. */
    fun lanCandidates(hosts: List<String>): List<String> =
        DEFAULT_PORTS.flatMap { port -> hosts.map { host -> "http://$host:$port" } }

    /**
     * The first candidate that answers like a Subsonic server, or null.
     *
     * Batched, and the winner is the first *in the order given* rather than
     * the first to answer, so the caller's ordering decides ties — the same
     * contract as [probeForTracks].
     */
    suspend fun find(
        candidates: List<String>,
        concurrency: Int = 48,
        timeoutMillis: Long = 1500,
        engine: HttpClientEngine? = null,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
    ): String? {
        if (candidates.isEmpty()) return null
        val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMillis
                connectTimeoutMillis = timeoutMillis
                socketTimeoutMillis = timeoutMillis
            }
            expectSuccess = false
        }
        val http = if (engine != null) HttpClient(engine, config) else HttpClient(config)
        var checked = 0
        try {
            for (batch in candidates.chunked(concurrency)) {
                val hits = coroutineScope {
                    batch.map { candidate -> async { if (identifiesAsSubsonic(http, candidate)) candidate else null } }.awaitAll()
                }
                checked += batch.size
                onProgress(checked, candidates.size)
                hits.firstOrNull { it != null }?.let { return it }
            }
        } finally {
            http.close()
        }
        return null
    }

    /** True when `base` answers the Subsonic ping in the Subsonic envelope. */
    suspend fun identifiesAsSubsonic(http: HttpClient, base: String): Boolean =
        try {
            val body = http.get(base + PING).bodyAsText()
            body.contains("\"subsonic-response\"")
        } catch (_: Exception) {
            false
        }
}
