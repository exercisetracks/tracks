// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Changing the password from the phone, and signing out.
 *
 * Both are about credentials the server has to be told to stop honouring:
 * a password change revokes this phone's own tokens and key along with
 * everyone else's, and a sign-out that only wiped the phone left all three
 * valid on the server.
 */
class PasswordAndSignOutTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun clientWith(
        store: TokenStore,
        calls: MutableList<String> = mutableListOf(),
        handler: (method: HttpMethod, path: String) -> Pair<HttpStatusCode, String>,
    ): TracksClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            calls += "${request.method.value} $path"
            val (status, body) = handler(request.method, path)
            respond(body, status, json)
        }
        return TracksClient(baseUrl = "https://tracks.example.com", tokens = store, engine = engine)
    }

    private val changed = """{"ok":true,"access_token":"after","refresh_token":"r-after",
        "revoked_device_keys":3,"revoked_sessions":2}"""

    @Test
    fun `a password change keeps this phone signed in on its replacement tokens`() = runTest {
        val store = InMemoryTokenStore(StoredSession(
            accessToken = "before", refreshToken = "r-before",
            deviceKey = DeviceKeyCredential(7, "old-secret"), agentToken = "agent"))
        val client = clientWith(store) { _, path ->
            when (path) {
                "/users/me/password" -> HttpStatusCode.OK to changed
                "/auth/device-keys" ->
                    HttpStatusCode.Created to """{"id":8,"device_secret":"new-secret"}"""
                else -> HttpStatusCode.NotFound to "{}"
            }
        }

        val result = client.changePassword("old", "brand-new-pass", "Pixel")

        val after = store.load()
        assertEquals("after", after.accessToken)
        assertEquals("r-after", after.refreshToken)
        assertEquals(DeviceKeyCredential(8, "new-secret"), after.deviceKey,
            "the revoked key is replaced, so the phone still unlocks on its own")
        assertEquals("agent", after.agentToken, "watch sync does not depend on the password")
        assertTrue(result.deviceKeyReEnrolled)
        assertEquals(2, result.otherDevicesSignedOut, "this phone's own key is not counted")
    }

    @Test
    fun `a failed re-enrolment still records the change and drops the dead key`() = runTest {
        val store = InMemoryTokenStore(StoredSession(
            accessToken = "before", deviceKey = DeviceKeyCredential(7, "old-secret")))
        val client = clientWith(store) { _, path ->
            when (path) {
                "/users/me/password" -> HttpStatusCode.OK to changed
                else -> HttpStatusCode.InternalServerError to "{}"
            }
        }

        val result = client.changePassword("old", "brand-new-pass")

        assertEquals("after", store.load().accessToken)
        assertNull(store.load().deviceKey, "a revoked key would only fail at the next unlock")
        assertFalse(result.deviceKeyReEnrolled)
    }

    @Test
    fun `a wrong current password is its own error, not a sign-out`() = runTest {
        val store = InMemoryTokenStore(StoredSession(accessToken = "before", refreshToken = "r"))
        val calls = mutableListOf<String>()
        val client = clientWith(store, calls) { _, _ ->
            HttpStatusCode.Forbidden to """{"detail":"Current password is incorrect"}"""
        }

        assertFailsWith<WrongPasswordException> { client.changePassword("typo", "brand-new-pass") }

        assertEquals("before", store.load().accessToken)
        assertEquals(listOf("POST /users/me/password"), calls, "no refresh was attempted")
    }

    @Test
    fun `signing out revokes the device key and ends the session on the server`() = runTest {
        val store = InMemoryTokenStore(StoredSession(
            accessToken = "a", refreshToken = "r", deviceKey = DeviceKeyCredential(7, "s")))
        val calls = mutableListOf<String>()
        val client = clientWith(store, calls) { _, _ -> HttpStatusCode.NoContent to "" }

        client.logout()

        assertEquals(listOf("DELETE /auth/device-keys/7", "POST /auth/logout"), calls,
            "the key first: revoking it needs the token logout ends")
        assertEquals(StoredSession(), store.load())
    }

    @Test
    fun `signing out works when the server cannot be reached`() = runTest {
        val store = InMemoryTokenStore(StoredSession(accessToken = "a", refreshToken = "r"))
        val engine = MockEngine { throw IllegalStateException("no route to host") }
        val client = TracksClient(baseUrl = "https://tracks.example.com", tokens = store, engine = engine)

        client.logout()

        assertEquals(StoredSession(), store.load())
    }
}
