// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Works out where the API actually lives, given whatever the user typed.
 *
 * Tracks is self-hosted, and it is reachable two ways depending on how the
 * operator runs it:
 *
 * - Behind the bundled Caddy, which is the documented setup: one origin, with
 *   `/api` paths routed to the backend and everything else serving the web app. The
 *   API base is then `https://host/api`.
 * - Straight at the backend container, ports exposed directly. The API base is
 *   the origin itself.
 *
 * Asking the user to know which is a poor trade — they typed the address they
 * use in a browser, and it is the app's job to find the API behind it. So this
 * probes both.
 *
 * A 200 is not enough to accept a candidate. A single-page app behind a
 * catch-all route will happily answer `/capabilities` with `index.html` and a
 * 200, so the response has to parse as [Capabilities] *and* identify itself as
 * Tracks. Anything less and the app would "connect" to a web server that has
 * never heard of it and fail confusingly later.
 */
class ServerNotFoundException(origin: String, tried: List<String> = emptyList()) : Exception(
    buildString {
        append("No Tracks server at $origin")
        if (tried.isNotEmpty()) {
            append(" — tried ")
            append(tried.joinToString(", ") { "$it${Endpoints.CAPABILITIES}" })
        }
    },
)

/**
 * Every base URL worth probing for what the user typed, in the order to try.
 *
 * Split out from the network call so the ordering is testable without a server.
 *
 * ## The scheme
 *
 * A missing scheme is not an edge case, it is the common case. Tracks is
 * self-hosted and most deployments are reached by address and port on a LAN, so
 * `10.0.0.5:4080` is exactly what someone types — and treating that as a
 * complete URL produces a request that cannot succeed and an error saying the
 * server is not there, when it is. Found by typing it, in onboarding, which is
 * now the first thing anyone does with this app.
 *
 * Both schemes get tried, `https` first: guessing `http` first would silently
 * downgrade a proper deployment, and sending a password over it is a worse
 * failure than a wasted request. The cost is one extra round trip during setup,
 * once, and only for input that had no scheme.
 */
internal fun apiBaseCandidates(origin: String): List<String> {
    val trimmed = origin.trim().trimEnd('/')
    if (trimmed.isEmpty()) return emptyList()

    val afterScheme = trimmed.substringAfter("://")
    val hostPort = afterScheme.substringBefore('/')
    val hasPort = hostPort.contains(':')
    val hasPath = afterScheme.contains('/')

    val origins = if (trimmed.contains("://")) {
        listOf(trimmed)
    } else {
        listOf("https://$trimmed", "http://$trimmed")
    }
    // A bare host with no port is the LAN case, and the shipped compose exposes
    // Tracks on 4080 — so try that too, rather than only 443/80. Only when the
    // user gave neither a port nor a path: if they were specific, honour exactly
    // what they typed. The default port comes after the plain origin, so a
    // deployment behind a proxy on 443/80 is still found in the first request
    // and 4080 is the fallback for a backend exposed directly.
    val withDefaultPort = if (!hasPort && !hasPath) origins.map { "$it:$DEFAULT_PORT" } else emptyList()

    // `/api` before the bare origin within each: it is the documented Caddy
    // deployment, so the common case costs one request rather than two.
    return (origins + withDefaultPort).flatMap { listOf("$it/api", it) }
}

/** `TRACKS_PORT`'s default and the only port the shipped compose exposes beyond loopback. */
private const val DEFAULT_PORT = 4080

/**
 * @return the base URL to hand [TracksClient], with no trailing slash.
 * @throws ServerNotFoundException if no candidate answers as Tracks.
 */
suspend fun discoverApiBase(origin: String, engine: HttpClientEngine? = null): String {
    val candidates = apiBaseCandidates(origin)
    if (candidates.isEmpty()) throw ServerNotFoundException(origin)

    val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) { json(TracksJson) }
        expectSuccess = false
    }
    val http = if (engine != null) HttpClient(engine, config) else HttpClient(config)

    try {
        for (candidate in candidates) {
            if (identifiesAsTracks(http, candidate)) return candidate
        }
    } finally {
        http.close()
    }
    throw ServerNotFoundException(origin.trim().trimEnd('/'), candidates)
}

/**
 * Find a Tracks server among many candidate base URLs.
 *
 * For discovery on a local network, where the question is not "is the API at
 * /api or at the origin" but "which of 254 addresses is the server". Candidates
 * are tried in the order given, in batches, and the first that identifies
 * itself wins — so the caller orders by likelihood and the common case costs
 * one batch rather than the whole sweep.
 *
 * Batched rather than all-at-once. A sweep of a /24 is a thousand-odd requests,
 * and firing them together would open a thousand sockets at a phone's network
 * stack to discover that 250 of them go nowhere.
 *
 * [timeoutMillis] is deliberately short. Every address that is not in use costs
 * exactly this much, because an unused address does not refuse a connection —
 * nothing answers at all — while a server on the same LAN answers in single
 * digits. A timeout generous enough for the internet would turn a sweep into
 * minutes of waiting for silence.
 *
 * @return the API base of the first server found, or null if none answered.
 */
suspend fun probeForTracks(
    candidates: List<String>,
    concurrency: Int = 48,
    timeoutMillis: Long = 600,
    engine: HttpClientEngine? = null,
    onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> },
): String? {
    if (candidates.isEmpty()) return null

    val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) { json(TracksJson) }
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
                batch.map { candidate ->
                    async { if (identifiesAsTracks(http, candidate)) candidate else null }
                }.awaitAll()
            }
            checked += batch.size
            onProgress(checked, candidates.size)
            // First in the batch, not first to answer: the caller's ordering is
            // meaningful — /api before the bare origin — and racing would make
            // the answer depend on which host replied fastest.
            hits.firstNotNullOfOrNull { it }?.let { return it }
        }
    } finally {
        http.close()
    }
    return null
}

private suspend fun identifiesAsTracks(http: HttpClient, base: String): Boolean = try {
    val resp: HttpResponse = http.get("$base${Endpoints.CAPABILITIES}")
    if (resp.status != HttpStatusCode.OK) {
        false
    } else {
        // Body must parse AND self-identify — see the note about SPA catch-alls.
        val caps: Capabilities = resp.body()
        caps.app == "tracks"
    }
} catch (e: Exception) {
    // A wrong guess throws on parse; that is a "no", not a failure to report.
    false
}
