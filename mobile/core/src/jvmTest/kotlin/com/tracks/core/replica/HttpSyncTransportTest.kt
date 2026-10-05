// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import com.tracks.core.api.InMemoryTokenStore
import com.tracks.core.api.StoredSession
import com.tracks.core.api.SyncHttpException
import com.tracks.core.api.TracksClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire, as `spec/sync.yaml` spells it, through the real client.
 *
 * The engine tests talk to [FakeServer] directly, so this is where the paths,
 * query parameters and JSON a real server will see are pinned down — the
 * half of the contract the Python side reads.
 */
class HttpSyncTransportTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun transport(respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpSyncTransport(
            TracksClient(
                baseUrl = "https://tracks.example.com",
                tokens = InMemoryTokenStore(StoredSession(accessToken = "t")),
                engine = MockEngine { req -> requests += req; respond(req) },
            ),
        )

    private val uid = "01922a8c-0000-7000-8000-000000000001"
    private val stamp = "0001727190000000-0000-a1b2c3d4e5f60718"

    @Test
    fun `a push posts the spec's shape and reads per-change results`() = runTest {
        val t = transport {
            respond(
                """{"clock":"$stamp","results":[{"entity":"waypoint","uid":"$uid","status":"partial",
                   "refused":{"color":"stale"}}]}""",
                HttpStatusCode.OK, json,
            )
        }
        val response = t.push(
            PushRequest(
                node = "a1b2c3d4e5f60718", clock = stamp,
                changes = listOf(Change("waypoint", uid, mapOf("name" to Stamped(JsonPrimitive(2), stamp)), deleted = stamp)),
                epoch = 3,
            ),
        )

        val req = requests.single()
        assertEquals("/sync/push", req.url.encodedPath)
        val body = Json.parseToJsonElement(String(req.body.toByteArray())).jsonObject
        assertEquals("a1b2c3d4e5f60718", body.getValue("node").jsonPrimitive.content)
        assertEquals("3", body.getValue("epoch").jsonPrimitive.content)
        val change = body.getValue("changes").jsonArray.single().jsonObject
        // A number stays a number, and the pair is [value, stamp].
        assertEquals("""[2,"$stamp"]""", change.getValue("fields").jsonObject.getValue("name").toString())
        assertEquals(stamp, change.getValue("deleted").jsonPrimitive.content)

        val result = response.results.single()
        assertEquals(ChangeStatus.PARTIAL, result.status)
        assertEquals(mapOf("color" to Refusal.STALE), result.refused)
    }

    @Test
    fun `a pull sends the cursor and limit`() = runTest {
        val t = transport {
            respond("""{"changes":[],"next":42,"has_more":false,"server_id":"5e5e5e5e5e5e5e5e","epoch":3}""", HttpStatusCode.OK, json)
        }
        val page = t.pull(since = 7, limit = 100)
        val url = requests.single().url
        assertEquals("/sync/pull", url.encodedPath)
        assertEquals("7", url.parameters["since"])
        assertEquals("100", url.parameters["limit"])
        assertEquals(42, page.next)
        assertEquals("5e5e5e5e5e5e5e5e", page.serverId)
        assertEquals(3, page.epoch)
    }

    @Test
    fun `a blob comes back as bytes`() = runTest {
        val t = transport { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }
        assertContentEquals(byteArrayOf(1, 2, 3), t.blob("ab".repeat(32)))
        assertEquals("/sync/blobs/${"ab".repeat(32)}", requests.single().url.encodedPath)
    }

    /**
     * A refused sign-in must leave no session behind: one that survived could
     * sync this phone's data into the wrong account.
     */
    @Test
    fun `signing in as another account is refused and signs back out`() = runTest {
        val tokens = InMemoryTokenStore(StoredSession(accessToken = "t"))
        val client = TracksClient(
            baseUrl = "https://tracks.example.com",
            tokens = tokens,
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/sync/pull" -> respond(
                        """{"changes":[],"next":0,"has_more":false,"server_id":"5e5e5e5e5e5e5e5e","epoch":0}""",
                        HttpStatusCode.OK, json,
                    )
                    "/users/me" -> respond("""{"id":2,"username":"someone"}""", HttpStatusCode.OK, json)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val (store, _) = newReplica(now = { 1_000L })
        store.link(ServerIdentity("5e5e5e5e5e5e5e5e", 0), "1")
        store.create("waypoint", mapOf("name" to JsonPrimitive("mine")))

        val result = AccountGate(client, store).afterSignIn()
        assertTrue(result is LinkResult.OtherAccount)
        assertNull(tokens.load().accessToken)
        assertEquals("1", store.boundAccount()!!.account)
    }

    /**
     * A different server is a question: the session stays up while it is
     * asked (so "yes" needs no second password), and "no" drops it.
     */
    @Test
    fun `a rebuilt server is asked about, and declining signs back out`() = runTest {
        val tokens = InMemoryTokenStore(StoredSession(accessToken = "t"))
        val client = TracksClient(
            baseUrl = "https://tracks.example.com",
            tokens = tokens,
            engine = MockEngine { req ->
                when (req.url.encodedPath) {
                    "/sync/pull" -> respond(
                        """{"changes":[],"next":0,"has_more":false,"server_id":"6f6f6f6f6f6f6f6f","epoch":0}""",
                        HttpStatusCode.OK, json,
                    )
                    "/users/me" -> respond("""{"id":1,"username":"alex"}""", HttpStatusCode.OK, json)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        )
        val (store, _) = newReplica(now = { 1_000L })
        store.link(ServerIdentity("5e5e5e5e5e5e5e5e", 0), "1")
        store.create("waypoint", mapOf("name" to JsonPrimitive("mine")))

        val gate = AccountGate(client, store)
        val result = gate.afterSignIn()
        assertTrue(result is LinkResult.DifferentServer)
        assertEquals("alex", result.username)
        assertEquals("t", tokens.load().accessToken)

        gate.decline()
        assertNull(tokens.load().accessToken)
        assertEquals("5e5e5e5e5e5e5e5e", store.boundAccount()!!.serverId)
        assertEquals(1, store.rows("waypoint").size)
    }

    @Test
    fun `a server without the sync protocol lets the sign-in through unbound`() = runTest {
        val client = TracksClient(
            baseUrl = "https://tracks.example.com",
            tokens = InMemoryTokenStore(StoredSession(accessToken = "t")),
            engine = MockEngine { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json) },
        )
        val (store, _) = newReplica(now = { 1_000L })
        assertEquals(LinkResult.Linked, AccountGate(client, store).afterSignIn())
        assertNull(store.boundAccount())
    }

    /** A pull missing the identity fields cannot be applied safely; it must not decode as a page. */
    @Test
    fun `a pull without a server id is refused`() = runTest {
        val t = transport { respond("""{"changes":[],"next":1,"has_more":false}""", HttpStatusCode.OK, json) }
        assertFailsWith<NoSuchElementException> { t.pull(0, 10) }
    }

    @Test
    fun `identify reads the identity from an empty pull past the end`() = runTest {
        val t = transport {
            respond("""{"changes":[],"next":0,"has_more":false,"server_id":"5e5e5e5e5e5e5e5e","epoch":1}""", HttpStatusCode.OK, json)
        }
        assertEquals(ServerIdentity("5e5e5e5e5e5e5e5e", 1), t.identify())
        assertEquals(Long.MAX_VALUE.toString(), requests.single().url.parameters["since"])
    }

    /** An error body decoded as a page would read as "nothing changed" — the worst possible misreading. */
    @Test
    fun `a server that does not speak the protocol is an error, not an empty page`() = runTest {
        val t = transport { respond("""{"detail":"Not Found"}""", HttpStatusCode.NotFound, json) }
        val error = assertFailsWith<SyncHttpException> { t.pull(0, 10) }
        assertEquals(404, error.status)
    }
}
