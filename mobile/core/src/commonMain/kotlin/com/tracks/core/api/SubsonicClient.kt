// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import com.tracks.core.replica.Digest
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

/** A music server said no, or could not be reached; the message is for a person. */
class SubsonicException(message: String) : Exception(message)

/**
 * The phone talking to the music server itself — no Tracks server in the path.
 *
 * Music used to be connected through the Tracks server, which held the login
 * and relayed every call. That made the music server unreachable from a phone
 * whose Tracks server was down, misconfigured or absent, though the two have
 * nothing to do with each other: the watch has always played straight from
 * the music server. So the phone now does the same, and this is the
 * backend's `app/services/subsonic.py`, cut down to what the phone asks.
 *
 * Auth is Subsonic's salted token, `t = md5(password + salt)` with a fresh
 * salt per request — weak, but what every Subsonic server accepts, and it
 * keeps the password out of URLs. The salt need not be secret, only fresh,
 * so the plain [Random] is enough for it.
 *
 * Redirects are **not** followed. A redirect would carry the token (and, on
 * Navidrome's own login, the password itself) to whatever host the response
 * names; the backend refused off-host hops for the same reason. A server that
 * moved is reported, so the user enters the address it moved to.
 */
class SubsonicClient(
    baseUrl: String,
    private val username: String,
    private val password: String,
    engine: HttpClientEngine? = null,
) {
    private val base = baseUrl.trim().trimEnd('/')

    private val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
        followRedirects = false
    }
    private val http = if (engine != null) HttpClient(engine, config) else HttpClient(config)

    fun close() = http.close()

    /** Proves the address and the login. Returns the server's name for itself, if it gives one. */
    suspend fun ping(): String? {
        val body = call("ping")
        return body["type"]?.jsonPrimitive?.contentOrNull ?: body["serverVersion"]?.jsonPrimitive?.contentOrNull
    }

    suspend fun playlists(): List<RemotePlaylist> =
        call("getPlaylists")["playlists"]?.objectOrNull()?.get("playlist").arrayOrEmpty().map {
            val p = it.jsonObject
            RemotePlaylist(
                id = p.string("id").orEmpty(),
                name = p.string("name") ?: "Playlist",
                songCount = p["songCount"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

    suspend fun starredCount(): Int =
        call("getStarred2")["starred2"]?.objectOrNull()?.get("song").arrayOrEmpty().size

    suspend fun searchSongs(query: String, count: Int = 50): List<RemoteSong> =
        call(
            "search3",
            mapOf("query" to query, "songCount" to "$count", "albumCount" to "0", "artistCount" to "0"),
        )["searchResult3"]?.objectOrNull()?.get("song").arrayOrEmpty().map {
            val s = it.jsonObject
            RemoteSong(
                id = s.string("id").orEmpty(),
                title = s.string("title") ?: "Untitled",
                artist = s.string("artist"),
                album = s.string("album"),
                albumId = s.string("albumId"),
            )
        }

    /**
     * How many songs the watch's "Recently played" would take: the latest
     * [limit] by last play, stopping at the first never played — the same walk
     * as the watch app's TracksSyncDelegate. Subsonic has no song-level
     * history, so this is Navidrome's own API; null from any other server.
     */
    suspend fun recentlyPlayedCount(limit: Int = 50): Int? = try {
        val login = http.post("$base/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("username", username); put("password", password) }.toString())
        }
        val token = if (login.status.value == 200) {
            Json.parseToJsonElement(login.bodyAsText()).jsonObject.string("token")
        } else null
        if (token == null) {
            null
        } else {
            val rows = http.get("$base/api/song") {
                parameter("_sort", "play_date"); parameter("_order", "DESC")
                parameter("_start", 0); parameter("_end", limit)
                header("x-nd-authorization", "Bearer $token")
            }.takeIf { it.status.value == 200 }?.let { Json.parseToJsonElement(it.bodyAsText()) as? JsonArray }
            rows?.takeWhile { row -> ((row as? JsonObject)?.get("playCount")?.jsonPrimitive?.intOrNull ?: 0) > 0 }?.size
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun call(method: String, params: Map<String, String> = emptyMap()): JsonObject {
        val salt = Digest.hex(Random.nextBytes(8))
        val response: HttpResponse = try {
            // ".view" is the spelling every implementation accepts; the bare form is not.
            http.get("$base/rest/$method.view") {
                parameter("u", username)
                parameter("t", Digest.hex(Digest.md5((password + salt).encodeToByteArray())))
                parameter("s", salt)
                parameter("v", "1.16.1")
                parameter("c", "Tracks")
                parameter("f", "json")
                params.forEach { (k, v) -> parameter(k, v) }
            }
        } catch (e: Exception) {
            throw SubsonicException("Could not reach the music server: ${e.message ?: e::class.simpleName}")
        }
        val status = response.status.value
        if (status in 300..399) {
            val to = response.headers["Location"]
            throw SubsonicException("The music server redirected to ${to ?: "another address"} — enter that address instead.")
        }
        if (status >= 400) throw SubsonicException("Music server returned HTTP $status")
        // Subsonic reports failure as HTTP 200 with an error body, so every
        // answer is unwrapped here rather than trusted as data.
        val body = try {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["subsonic-response"]?.jsonObject
        } catch (_: Exception) {
            null
        } ?: throw SubsonicException("Music server did not return JSON — is that a Subsonic URL?")
        if (body.string("status") != "ok") {
            val error = body["error"]?.objectOrNull()
            val code = error?.get("code")?.jsonPrimitive?.intOrNull
            if (code == 40 || code == 41) throw SubsonicException("Music server rejected the username or password")
            throw SubsonicException("Music server refused $method: ${error?.string("message") ?: "unknown error"}")
        }
        return body
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun kotlinx.serialization.json.JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
    private fun kotlinx.serialization.json.JsonElement?.arrayOrEmpty(): List<kotlinx.serialization.json.JsonElement> =
        (this as? JsonArray) ?: emptyList()
}
