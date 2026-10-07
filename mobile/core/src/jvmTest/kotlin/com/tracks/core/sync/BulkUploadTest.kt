// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import com.tracks.core.api.InMemoryTokenStore
import com.tracks.core.api.Limits
import com.tracks.core.api.StoredSession
import com.tracks.core.api.TracksClient
import com.tracks.core.crypto.BouncyCastleIngestCrypto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The backlog upload, against a server that behaves like the `/sync/ingest` endpoints.
 *
 * What these pin is the reason [BulkUpload] exists — a first upload of years
 * of history running at two files a second — and the one property that must
 * survive making it fast: a file is marked sent only when the server said it
 * holds that file.
 */
class BulkUploadTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 9 })

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A server holding [held] that takes everything else, except what [refuse] names. */
    private inner class Server(
        val held: Set<String> = emptySet(),
        val refuse: Set<String> = emptySet(),
        val failBatch: Int = -1,
        val latencyMs: Long = 0,
    ) {
        val batchSizes = mutableListOf<Int>()
        val asked = mutableListOf<Int>()
        val sentHashes = mutableListOf<String>()
        private val concurrent = AtomicInteger()
        val peak = AtomicInteger()

        val uploader = WatchUploader(
            TracksClient(
                baseUrl = "https://tracks.example.com",
                tokens = InMemoryTokenStore(StoredSession(accessToken = "t")),
                engine = MockEngine { req ->
                    val body = req.body.toByteArray().decodeToString()
                    when (req.url.encodedPath) {
                        "/sync/pubkey" -> respond("""{"user_id":1,"public_key":"$publicKey"}""", HttpStatusCode.OK, json)
                        "/sync/ingest/missing" -> {
                            val hashes = Json.parseToJsonElement(body).jsonObject.getValue("hashes").jsonArray
                                .map { it.jsonPrimitive.content }
                            synchronized(this@Server) { asked += hashes.size }
                            val missing = hashes.filterNot { it in held }.joinToString(",") { "\"$it\"" }
                            respond("""{"missing":[$missing]}""", HttpStatusCode.OK, json)
                        }
                        "/sync/ingest/batch" -> {
                            val now = concurrent.incrementAndGet()
                            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                            try {
                                if (latencyMs > 0) delay(latencyMs)
                                val hashes = Json.parseToJsonElement(body).jsonObject.getValue("files").jsonArray
                                    .map { it.jsonObject.getValue("content_hash").jsonPrimitive.content }
                                val index = synchronized(this@Server) {
                                    batchSizes += hashes.size
                                    sentHashes += hashes
                                    batchSizes.size - 1
                                }
                                if (index == failBatch) {
                                    respond("""{"detail":"boom"}""", HttpStatusCode.InternalServerError, json)
                                } else {
                                    val results = hashes.joinToString(",") {
                                        val status = if (it in refuse) "invalid_hash" else "queued"
                                        """{"content_hash":"$it","status":"$status"}"""
                                    }
                                    respond("""{"results":[$results]}""", HttpStatusCode.OK, json)
                                }
                            } finally {
                                concurrent.decrementAndGet()
                            }
                        }
                        else -> error("unexpected ${req.url.encodedPath}")
                    }
                },
            ),
            BouncyCastleIngestCrypto(),
            agentToken = "agent",
        )
    }

    private val files: Map<String, ByteArray> = (1..40).associate {
        val bytes = ByteArray(1000) { i -> (i * it).toByte() }
        sha(bytes) to bytes
    }
    private val hashes = files.keys.toList()

    @Test
    fun `files the server already holds are marked sent without being sent`() = runBlocking {
        val server = Server(held = hashes.take(30).toSet())
        val delivered = mutableListOf<String>()

        BulkUpload(server.uploader, { files[it] }).send(hashes, onDelivered = { delivered += it })

        assertEquals(hashes.toSet(), delivered.toSet())
        assertEquals(hashes.drop(30).toSet(), server.sentHashes.toSet())
    }

    @Test
    fun `a backlog goes up in a few requests rather than one per file`() = runBlocking {
        val server = Server()
        var lastProgress = 0 to 0

        BulkUpload(server.uploader, { files[it] }, batchBytes = 10_000)
            .send(hashes, onDelivered = {}, onProgress = { d, t -> lastProgress = d to t })

        // 40 files of 1000 bytes in batches of at most 10 000.
        assertEquals(4, server.batchSizes.size)
        assertTrue(server.batchSizes.all { it <= 10 })
        assertEquals(hashes.sorted(), server.sentHashes.sorted())
        assertEquals(40 to 40, lastProgress)
    }

    @Test
    fun `batches travel together instead of waiting on each other`() = runBlocking {
        val server = Server(latencyMs = 200)

        BulkUpload(server.uploader, { files[it] }, inFlight = 4, batchBytes = 5_000)
            .send(hashes, onDelivered = {})

        assertTrue(server.peak.get() > 1, "only ever one batch in flight")
    }

    @Test
    fun `the server's file cap and ask cap are respected`() = runBlocking {
        val server = Server()
        val limits = Limits(ingestBatchFiles = 7, ingestMissingHashes = 15)

        BulkUpload(server.uploader, { files[it] }, limits).send(hashes, onDelivered = {})

        assertTrue(server.batchSizes.all { it <= 7 })
        assertEquals(listOf(15, 15, 10), server.asked)
    }

    @Test
    fun `a file the server refused is not marked sent`() = runBlocking {
        val refused = hashes[3]
        val server = Server(refuse = setOf(refused))
        val delivered = mutableListOf<String>()

        BulkUpload(server.uploader, { files[it] }).send(hashes, onDelivered = { delivered += it })

        assertEquals(hashes.toSet() - refused, delivered.toSet())
    }

    @Test
    fun `a failed request keeps what got through and marks nothing it carried`() = runBlocking {
        val server = Server(failBatch = 1)
        val delivered = mutableListOf<String>()

        assertFailsWith<Exception> {
            BulkUpload(server.uploader, { files[it] }, inFlight = 1, batchBytes = 10_000)
                .send(hashes, onDelivered = { delivered += it })
        }

        assertEquals(hashes.take(10).toSet(), delivered.toSet())
    }

    @Test
    fun `a file gone from storage is skipped, not sent empty`() = runBlocking {
        val server = Server()
        val gone = hashes[0]

        BulkUpload(server.uploader, { if (it == gone) null else files[it] }).send(hashes, onDelivered = {})

        assertTrue(gone !in server.sentHashes)
        assertEquals(39, server.sentHashes.size)
    }
}
