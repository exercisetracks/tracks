// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every path this client calls must exist on the server.
 *
 * This is the job a generated client would have done — noticing when the
 * contract moves — without generating all 187 endpoints to get at the
 * seventeen that matter. The snapshot in `spec/openapi.json` is the server's
 * own schema; refresh it with:
 *
 *     curl -s http://localhost:8000/openapi.json -o spec/openapi.json
 *
 * A failure here means one of two things, and the fix differs:
 *
 * - the client asks for something the server never had — a typo, caught before
 *   it ships;
 * - the server renamed or dropped an endpoint — the snapshot needs refreshing,
 *   and the diff shows exactly what the app has to change.
 *
 * It checks paths, not request or response shapes. Field-level drift is caught
 * by the lenient decoder failing on a *missing required* field, which is the
 * half that actually breaks a client; an added field is a non-event by design.
 */
class ApiContractTest {

    private val schema: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "spec/openapi.json")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("spec/openapi.json not found above ${System.getProperty("user.dir")}")
    }

    private val serverPaths: Set<String> by lazy {
        Json.parseToJsonElement(schema.readText())
            .jsonObject["paths"]!!.jsonObject.keys
    }

    @Test
    fun `the snapshot looks like the Tracks schema`() {
        // Guards against an empty or truncated snapshot quietly making every
        // assertion below vacuous.
        assertTrue(serverPaths.size > 100, "schema has only ${serverPaths.size} paths")
        assertTrue("/capabilities" in serverPaths)
    }

    @Test
    fun `every endpoint the client calls exists on the server`() {
        val missing = Endpoints.all.filterNot { it in serverPaths }
        assertTrue(
            missing.isEmpty(),
            "client calls paths the server does not expose: $missing\n" +
                "Either the path is wrong, or spec/openapi.json is stale — " +
                "refresh it with: curl -s http://localhost:8000/openapi.json -o spec/openapi.json",
        )
    }

    @Test
    fun `the endpoints the mobile app depends on are all present`() {
        // Named individually so a failure says which capability broke rather
        // than just "a path is missing".
        val critical = mapOf(
            "capability negotiation" to Endpoints.CAPABILITIES,
            "silent vault unlock" to Endpoints.DEVICE_UNLOCK,
            "device enrolment" to Endpoints.DEVICE_KEYS,
            "token refresh" to Endpoints.REFRESH,
            "replica push" to Endpoints.SYNC_PUSH,
            "replica pull" to Endpoints.SYNC_PULL,
            "FIT history download" to Endpoints.SYNC_BLOB,
            "bounded track fetch" to Endpoints.ACTIVITY_TRACK,
            "map style" to Endpoints.MAP_STYLE,
            "watch push list" to Endpoints.PUSH_LIST,
            "sealed ingest (works vault-locked)" to Endpoints.SYNC_INGEST,
        )
        val broken = critical.filterValues { it !in serverPaths }
        assertTrue(broken.isEmpty(), "missing: ${broken.keys}")
    }

    /**
     * v2 shipped with the phone still claiming v1, and the first sign-in
     * against the new server was refused as "too old" — found by hand on a
     * phone, because every other test talks to fakes that never ask.
     */
    @Test
    fun `the client speaks the same api version as the server`() {
        val version = File(schema.parentFile.parentFile, "backend/app/version.py").readLines()
            .first { it.startsWith("API_VERSION") }
            .substringAfter("=").trim().toInt()
        assertEquals(version, CLIENT_API_VERSION)
    }

    @Test
    fun `no endpoint is listed twice`() {
        val dupes = Endpoints.all.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(dupes.isEmpty(), "duplicated in Endpoints.all: ${dupes.keys}")
    }
}
