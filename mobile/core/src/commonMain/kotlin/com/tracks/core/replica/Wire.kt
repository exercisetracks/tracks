// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The sync protocol's JSON, exactly as `spec/sync.yaml` ("Wire") spells it.
 *
 * Hand-built rather than `@Serializable` data classes because a field travels
 * as a two-element array — `[value, stamp]` — whose first element can be any
 * JSON at all. A serializer for that would be more code than this, and the
 * value must survive untouched: a number the server sends as `2` has to come
 * back as `2`, not `2.0`, or a replay would look like a different write.
 */
object Wire {

    fun encodeChange(change: Change): JsonObject = buildJsonObject {
        put("entity", change.entity)
        put("uid", change.uid)
        put("fields", buildJsonObject {
            for ((name, stamped) in change.fields) {
                put(name, buildJsonArray { add(stamped.value); add(JsonPrimitive(stamped.stamp)) })
            }
        })
        change.deleted?.let { put("deleted", it) }
    }

    fun decodeChange(json: JsonObject): Change = Change(
        entity = json.getValue("entity").jsonPrimitive.content,
        uid = json.getValue("uid").jsonPrimitive.content,
        fields = (json["fields"] as? JsonObject).orEmpty().mapValues { (name, pair) ->
            val arr = pair.jsonArray
            require(arr.size == 2) { "field $name is not [value, stamp]" }
            Stamped(arr[0], arr[1].jsonPrimitive.content)
        },
        deleted = json["deleted"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
    )

    fun encodePush(request: PushRequest): JsonObject = buildJsonObject {
        put("node", request.node)
        put("clock", request.clock)
        put("epoch", request.epoch)
        put("changes", JsonArray(request.changes.map(::encodeChange)))
    }

    fun decodePush(json: JsonObject): PushRequest = PushRequest(
        node = json.getValue("node").jsonPrimitive.content,
        clock = json.getValue("clock").jsonPrimitive.content,
        changes = json.getValue("changes").jsonArray.map { decodeChange(it.jsonObject) },
        epoch = json["epoch"]?.jsonPrimitive?.longOrNull ?: 0,
    )

    fun encodePushResponse(response: PushResponse): JsonObject = buildJsonObject {
        put("clock", response.clock)
        put("results", JsonArray(response.results.map { r ->
            buildJsonObject {
                put("entity", r.entity)
                put("uid", r.uid)
                put("status", r.status.wire)
                put("refused", buildJsonObject { r.refused.forEach { (f, why) -> put(f, why.wire) } })
                r.reason?.let { put("reason", it) }
            }
        }))
    }

    fun decodePushResponse(json: JsonObject): PushResponse = PushResponse(
        clock = json.getValue("clock").jsonPrimitive.content,
        results = json.getValue("results").jsonArray.map { el ->
            val r = el.jsonObject
            ChangeResult(
                entity = r.getValue("entity").jsonPrimitive.content,
                uid = r.getValue("uid").jsonPrimitive.content,
                status = ChangeStatus.ofWire(r.getValue("status").jsonPrimitive.content),
                // A refusal this build does not know is still a refusal; it is
                // kept as "stale", the reading that errs towards not resending.
                refused = (r["refused"] as? JsonObject).orEmpty().mapValues {
                    Refusal.ofWire(it.value.jsonPrimitive.content) ?: Refusal.STALE
                },
                reason = r["reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            )
        },
    )

    fun encodePull(page: PullPage): JsonObject = buildJsonObject {
        put("changes", JsonArray(page.changes.map(::encodeChange)))
        put("next", page.next)
        put("has_more", page.hasMore)
        put("server_id", page.serverId)
        put("epoch", page.epoch)
    }

    fun decodePull(json: JsonObject): PullPage = PullPage(
        changes = json.getValue("changes").jsonArray.map { decodeChange(it.jsonObject) },
        next = json.getValue("next").jsonPrimitive.longOrNull
            ?: throw IllegalArgumentException("pull page without a numeric 'next'"),
        hasMore = json["has_more"]?.jsonPrimitive?.booleanOrNull ?: false,
        // Required: without them a phone cannot tell a recreated server from a
        // wiped account, and guessing wrong either loses data or resurrects it.
        serverId = json.getValue("server_id").jsonPrimitive.content,
        epoch = json.getValue("epoch").jsonPrimitive.longOrNull
            ?: throw IllegalArgumentException("pull page without a numeric 'epoch'"),
    )

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}

/**
 * [epoch] is the account epoch this phone last saw. A server whose account has
 * moved past it — the user deleted their data — rejects the whole push as
 * [RejectReason.WIPED] rather than letting the phone put it back.
 */
data class PushRequest(val node: String, val clock: String, val changes: List<Change>, val epoch: Long = 0)

data class PushResponse(val clock: String, val results: List<ChangeResult>)

data class PullPage(
    val changes: List<Change>,
    val next: Long,
    val hasMore: Boolean,
    /** The server's node id — changes only when its database was recreated. */
    val serverId: String,
    /** The account's epoch — rises only when the user deleted their data. */
    val epoch: Long,
)

/** Who a server is and where the account stands, without any rows. */
data class ServerIdentity(val serverId: String, val epoch: Long)

/**
 * What the engine needs from a server. An interface so the engine's tests run
 * against an in-memory server applying the real [Merge] — the only honest way
 * to test convergence — while the app uses [com.tracks.core.api.TracksClient].
 */
interface SyncTransport {
    suspend fun push(request: PushRequest): PushResponse
    suspend fun pull(since: Long, limit: Int): PullPage

    /** The FIT file with this SHA-256, as bytes. */
    suspend fun blob(sha256: String): ByteArray

    /** [ServerIdentity] with no rows — for binding an account at sign-in. */
    suspend fun identify(): ServerIdentity
}
