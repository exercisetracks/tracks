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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session state machine.
 *
 * This is the code that decides whether the user gets asked for a password
 * weekly or never, and every interesting case is an unhappy path — so the tests
 * here are mostly about failures: a shut vault, a dead access token, a revoked
 * device key, and several requests failing at once.
 */
class SessionRecoveryTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Recorder {
        val paths = mutableListOf<String>()
        val states = mutableListOf<SessionState>()
    }

    /** A mock server that replies from a script, in order, recording as it goes. */
    private fun clientWith(
        store: TokenStore,
        recorder: Recorder = Recorder(),
        handler: suspend (path: String, call: Int) -> Pair<HttpStatusCode, String>,
    ): Pair<TracksClient, Recorder> {
        var calls = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recorder.paths += path
            val (status, body) = handler(path, calls++)
            respond(body, status, json)
        }
        val client = TracksClient(
            baseUrl = "https://tracks.example.com",
            tokens = store,
            engine = engine,
            onSessionState = { recorder.states += it },
        )
        return client to recorder
    }

    private fun tokenBody(access: String, refresh: String? = null) = buildString {
        append("""{"access_token":"$access","token_type":"bearer"""")
        if (refresh != null) append(""","refresh_token":"$refresh"""")
        append("}")
    }

    private val emptyDelta = """{"id":1,"username":"a"}"""

    // ── The headline case ────────────────────────────────────────────────────

    @Test
    fun `a shut vault is reopened with the device key and the user sees nothing`() = runTest {
        val store = InMemoryTokenStore(
            StoredSession(accessToken = "old", refreshToken = "r1",
                          deviceKey = DeviceKeyCredential(7, "secret")),
        )
        val (client, rec) = clientWith(store) { path, _ ->
            when {
                path == "/users/me" && store.load().accessToken == "old" ->
                    HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
                path == "/auth/device-unlock" ->
                    HttpStatusCode.OK to tokenBody("fresh", "r2")
                path == "/users/me" -> HttpStatusCode.OK to emptyDelta
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        val page = client.currentUser()

        assertEquals(1, page.id)
        assertTrue("/auth/device-unlock" in rec.paths, "should have unlocked")
        assertEquals(2, rec.paths.count { it == "/users/me" }, "should have retried once")
        assertEquals("fresh", store.load().accessToken)
        // The unlock minted a new sid, so the old refresh token is dead and the
        // replacement must have been stored.
        assertEquals("r2", store.load().refreshToken)
        assertTrue(rec.states.none { it is SessionState.VaultLocked },
                   "the user should never have seen a locked vault")
    }

    @Test
    fun `a refresh is not attempted for a shut vault`() = runTest {
        // A refresh cannot reopen the vault — the decryption key is derived
        // from a secret the refresh token does not carry. Trying anyway would
        // burn a rotation and still leave the vault shut.
        val store = InMemoryTokenStore(
            StoredSession("old", "r1", DeviceKeyCredential(7, "secret")),
        )
        val (client, rec) = clientWith(store) { path, _ ->
            when (path) {
                "/users/me" ->
                    if (store.load().accessToken == "old")
                        HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
                    else HttpStatusCode.OK to emptyDelta
                "/auth/device-unlock" -> HttpStatusCode.OK to tokenBody("fresh", "r2")
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        client.currentUser()
        assertTrue("/auth/refresh" !in rec.paths, "refresh must not be used to unlock a vault")
    }

    @Test
    fun `without a device key the app is told to prompt for a password`() = runTest {
        val store = InMemoryTokenStore(StoredSession("old", "r1", deviceKey = null))
        val (client, rec) = clientWith(store) { _, _ ->
            HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
        }

        assertFailsWith<VaultLockedException> { client.currentUser() }
        assertTrue(rec.states.contains(SessionState.VaultLocked))
        // Still authenticated — this is not a logout, and treating it as one is
        // the mistake the whole state machine exists to avoid.
        assertEquals("old", store.load().accessToken)
    }

    // ── Expired access token ─────────────────────────────────────────────────

    @Test
    fun `a dead access token is refreshed and the request retried`() = runTest {
        val store = InMemoryTokenStore(StoredSession("expired", "r1"))
        val (client, rec) = clientWith(store) { path, _ ->
            when (path) {
                "/users/me" ->
                    if (store.load().accessToken == "expired")
                        HttpStatusCode.Unauthorized to """{"detail":"Invalid or expired token"}"""
                    else HttpStatusCode.OK to emptyDelta
                "/auth/refresh" -> HttpStatusCode.OK to tokenBody("fresh", "r2")
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        client.currentUser()
        assertTrue("/auth/refresh" in rec.paths)
        assertEquals("fresh", store.load().accessToken)
    }

    @Test
    fun `the rotated refresh token replaces the old one`() = runTest {
        // Rotation is mandatory server-side: reusing a consumed token revokes
        // the whole family as a suspected replay. Storing the replacement is
        // therefore not an optimisation.
        val store = InMemoryTokenStore(StoredSession("expired", "r1"))
        val (client, _) = clientWith(store) { path, _ ->
            when (path) {
                "/users/me" ->
                    if (store.load().accessToken == "expired")
                        HttpStatusCode.Unauthorized to """{"detail":"expired"}"""
                    else HttpStatusCode.OK to emptyDelta
                "/auth/refresh" -> HttpStatusCode.OK to tokenBody("fresh", "r2")
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        client.currentUser()
        assertEquals("r2", store.load().refreshToken)
    }

    @Test
    fun `a rejected refresh token logs the user out`() = runTest {
        val store = InMemoryTokenStore(StoredSession("expired", "revoked"))
        val (client, rec) = clientWith(store) { path, _ ->
            when (path) {
                "/users/me" -> HttpStatusCode.Unauthorized to """{"detail":"expired"}"""
                "/auth/refresh" -> HttpStatusCode.Unauthorized to """{"detail":"Invalid or expired refresh token"}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        assertFailsWith<NotAuthenticatedException> { client.currentUser() }
        assertTrue(rec.states.contains(SessionState.LoggedOut))
        assertNull(store.load().accessToken, "credentials should be cleared")
    }

    @Test
    fun `no credentials at all fails without touching the network`() = runTest {
        val store = InMemoryTokenStore(StoredSession())
        val (client, rec) = clientWith(store) { _, _ -> HttpStatusCode.OK to emptyDelta }

        assertFailsWith<NotAuthenticatedException> { client.currentUser() }
        assertTrue(rec.paths.isEmpty(), "should not have called the server")
    }

    // ── Not infinite ─────────────────────────────────────────────────────────

    @Test
    fun `recovery is attempted once, not in a loop`() = runTest {
        // A server that keeps returning session_expired must not become a
        // request storm from a phone on a bad connection.
        val store = InMemoryTokenStore(
            StoredSession("old", "r1", DeviceKeyCredential(7, "secret")),
        )
        val (client, rec) = clientWith(store) { path, _ ->
            when (path) {
                "/auth/device-unlock" -> HttpStatusCode.OK to tokenBody("fresh", "r2")
                else -> HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
            }
        }

        assertFailsWith<VaultLockedException> { client.currentUser() }
        assertEquals(1, rec.paths.count { it == "/auth/device-unlock" })
        assertEquals(2, rec.paths.count { it == "/users/me" })
    }

    @Test
    fun `a revoked device key drops it and asks for a password`() = runTest {
        // What a user sees after changing their password on another device.
        val store = InMemoryTokenStore(
            StoredSession("old", "r1", DeviceKeyCredential(7, "revoked")),
        )
        val (client, rec) = clientWith(store) { path, _ ->
            when (path) {
                "/auth/device-unlock" -> HttpStatusCode.Unauthorized to """{"detail":"Invalid device key"}"""
                else -> HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
            }
        }

        assertFailsWith<VaultLockedException> { client.currentUser() }
        assertNull(store.load().deviceKey, "a dead key must not be retried forever")
        assertTrue(rec.states.contains(SessionState.VaultLocked))
    }

    // ── Enrolment ────────────────────────────────────────────────────────────

    @Test
    fun `enrolling a device key stores the one-time secret`() = runTest {
        val store = InMemoryTokenStore(StoredSession("live", "r1"))
        val (client, _) = clientWith(store) { path, _ ->
            when (path) {
                "/auth/device-keys" ->
                    HttpStatusCode.Created to """{"id":42,"label":"Pixel","device_secret":"s3cr3t"}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        val created = client.enrolDeviceKey("Pixel")
        assertEquals(42, created.id)
        assertEquals(DeviceKeyCredential(42, "s3cr3t"), store.load().deviceKey)
        assertTrue(store.load().canSelfUnlock)
    }

    @Test
    fun `enrolling needs an unlocked vault`() = runTest {
        val store = InMemoryTokenStore(StoredSession("live", "r1"))
        val (client, _) = clientWith(store) { _, _ ->
            HttpStatusCode.Unauthorized to """{"detail":"session_expired"}"""
        }
        assertFailsWith<VaultLockedException> { client.enrolDeviceKey("Pixel") }
    }

    @Test
    fun `revoking the stored device key forgets it locally`() = runTest {
        val store = InMemoryTokenStore(
            StoredSession("live", "r1", DeviceKeyCredential(7, "secret")),
        )
        val (client, _) = clientWith(store) { _, _ -> HttpStatusCode.NoContent to "" }

        client.revokeDeviceKey(7)
        assertNull(store.load().deviceKey)
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Test
    fun `login stores both tokens`() = runTest {
        val store = InMemoryTokenStore()
        val (client, rec) = clientWith(store) { _, _ ->
            HttpStatusCode.OK to tokenBody("a1", "r1")
        }

        client.login("alex", "hunter2", deviceLabel = "Pixel")
        assertEquals("a1", store.load().accessToken)
        assertEquals("r1", store.load().refreshToken)
        assertTrue(rec.states.contains(SessionState.Active))
    }

    @Test
    fun `bad credentials do not clobber an existing session`() = runTest {
        val store = InMemoryTokenStore(StoredSession("a1", "r1"))
        val (client, _) = clientWith(store) { _, _ ->
            HttpStatusCode.Unauthorized to """{"detail":"Invalid credentials"}"""
        }

        assertFailsWith<NotAuthenticatedException> { client.login("alex", "wrong") }
        assertEquals("a1", store.load().accessToken)
    }
}
