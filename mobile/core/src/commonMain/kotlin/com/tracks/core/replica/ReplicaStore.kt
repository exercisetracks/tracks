// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.db.SqlDriver
import com.tracks.core.db.Synced_row
import com.tracks.core.db.TracksDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * The phone's synced rows, and the only way to change them.
 *
 * ## One door for edits
 *
 * Every local change goes through [create], [edit] or [delete], which tick the
 * clock, run the same [Merge] a pulled row goes through, and record what is
 * now dirty — all in one transaction with the clock itself. A screen that
 * wrote a row any other way would produce an edit with no stamp, which no
 * other replica could order, and which would never be pushed.
 *
 * ## Dirty tracking, and the race it exists for
 *
 * A row remembers which of its fields were edited here and not yet
 * acknowledged. [acknowledge] clears a field only if its stamp is still the
 * one that was pushed: a field edited again while the push was in flight
 * stays dirty, so the second edit goes out on the next push instead of being
 * forgotten because the first one was accepted.
 *
 * A pulled write that wins a field also clears it — the local edit lost, and
 * there is nothing left to send. One that loses leaves it dirty, which is what
 * stops a pull from undoing an edit the server has not heard about yet.
 *
 * ## Serialised
 *
 * Behind a [Mutex] as well as SQL transactions, because the clock is in memory
 * between writes: two coroutines ticking it concurrently could otherwise issue
 * the same stamp twice.
 */
class ReplicaStore(
    driver: SqlDriver,
    private val now: () -> Long,
    /**
     * Only used to mint this install's node id and new uids. The platform
     * should pass a cryptographically seeded source; a predictable one could
     * let two installs pick the same node id.
     */
    private val random: Random = Random.Default,
) {
    private val db = TracksDb(driver)
    private val q = db.replicaQueries
    private val json = Json
    private val dirtyList = ListSerializer(String.serializer())
    private val lock = Mutex()

    private val clock: HlcClock = run {
        val state = q.selectState().executeAsOneOrNull()
        if (state == null) {
            val node = Digest.hex(random.nextBytes(8))
            q.insertState(node)
            HlcClock(node)
        } else {
            HlcClock(state.node, state.last_hlc?.let(Hlc::parse))
        }
    }

    val node: String get() = clock.node

    suspend fun currentClock(): String = lock.withLock { clock.last.encoded }

    suspend fun cursor(): Long = lock.withLock { q.selectState().executeAsOne().pull_cursor }

    /**
     * The server identity and account epoch last seen in a pull, or null when
     * either is unknown — before the first pull, or after a wipe the phone
     * learned of from a rejected push, which carries no epoch. A null here
     * makes the next pull record what it sees rather than react to it.
     */
    suspend fun server(): ServerIdentity? = lock.withLock {
        val s = q.selectState().executeAsOne()
        val id = s.server_id ?: return@withLock null
        ServerIdentity(id, s.epoch ?: return@withLock null)
    }

    /** The epoch to send with a push: the last one seen, or 0 before any. */
    suspend fun epoch(): Long = lock.withLock { q.selectState().executeAsOne().epoch ?: 0 }

    /** The account this phone's data belongs to, or null if it has never been linked. */
    suspend fun boundAccount(): AccountBinding? = lock.withLock {
        val s = q.selectState().executeAsOne()
        val server = s.bound_server ?: return@withLock null
        AccountBinding(server, s.bound_account ?: return@withLock null)
    }

    suspend fun hasData(): Boolean = lock.withLock { q.countRows().executeAsOne() > 0 }

    // ── Accounts ────────────────────────────────────────────────────────────

    /**
     * Tie this phone's data to an account at sign-in, or refuse.
     *
     * The first account a phone links to owns what is on it — including
     * everything recorded standalone before any server existed, which that
     * first link merges both ways. Signing in again to the same account
     * resumes. Signing in to anyone else is refused while data remains:
     * merging one person's history into another's account is not something a
     * later sync can undo, so it takes an explicit [erase] first.
     *
     * A phone holding no data has nothing to protect and simply rebinds.
     */
    suspend fun link(server: ServerIdentity, account: String, username: String? = null): LinkResult = lock.withLock {
        val s = q.selectState().executeAsOne()
        val bound = s.bound_server?.let { AccountBinding(it, s.bound_account.orEmpty()) }
        val wanted = AccountBinding(server.serverId, account)
        when {
            bound == wanted -> LinkResult.Resumed
            bound == null || q.countRows().executeAsOne() == 0L -> {
                q.setBinding(wanted.serverId, wanted.account)
                LinkResult.Linked
            }
            bound.serverId != wanted.serverId -> LinkResult.DifferentServer(bound, server, account, username)
            else -> LinkResult.OtherAccount(bound)
        }
    }

    /**
     * Yes to [LinkResult.DifferentServer]: this phone's data now belongs to
     * [account] on [server], and all of it — tombstones included — is marked
     * for the next push, with the pull starting again from zero. The same
     * re-push a recreated server gets, plus the new binding, in one transaction
     * so a crash cannot leave the phone bound but not queued, or the reverse.
     */
    suspend fun restoreInto(server: ServerIdentity, account: String) = lock.withLock {
        db.transaction {
            markAllDirty()
            q.setCursor(0)
            q.setServer(server.serverId, server.epoch)
            q.setBinding(server.serverId, account)
        }
    }

    private fun markAllDirty() {
        for (r in q.selectAll().executeAsList()) {
            val row = r.toRow()
            writeDirty(row, if (row.isTombstone) setOf(DELETED) else row.fields.keys)
        }
    }

    /**
     * Delete everything this phone holds for the replica: synced rows (unpushed
     * edits included), the account binding, the server identity and cursor.
     * The node id and clock survive — a new identity would gain nothing and
     * a clock that went backwards could mint stamps older than ones already
     * sent. FIT files are the caller's: see [ReplicaSyncEngine.erase].
     */
    suspend fun erase() = lock.withLock {
        db.transaction {
            q.deleteAllRows()
            q.setCursor(0)
            q.setServer(null, null)
            q.setBinding(null, null)
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    suspend fun row(entity: String, uid: String): SyncedRow? = lock.withLock {
        q.selectRow(entity, uid).executeAsOneOrNull()?.toRow()
    }

    /** Live rows of [entity]; tombstones only on request. */
    suspend fun rows(entity: String, includeDeleted: Boolean = false): List<SyncedRow> = lock.withLock {
        val query = if (includeDeleted) q.selectEntity(entity) else q.selectLiveEntity(entity)
        query.executeAsList().map { it.toRow() }
    }

    suspend fun allRows(): List<SyncedRow> = lock.withLock { q.selectAll().executeAsList().map { it.toRow() } }

    suspend fun pendingCount(): Long = lock.withLock { q.countDirty().executeAsOne() }

    // ── Local edits ─────────────────────────────────────────────────────────

    /**
     * Create a row and return its uid. [keyValues] fills a natural-key
     * entity's uid template (see [Uids.forEntity]); for such an entity a
     * "create" of a row that already exists is simply an edit of it, which is
     * the point.
     */
    suspend fun create(
        entity: String,
        values: Map<String, JsonElement>,
        keyValues: Map<String, String?> = emptyMap(),
    ): Pair<String, ChangeResult> {
        val uid = Uids.forEntity(entity, keyValues, now(), random)
        return uid to edit(entity, uid, values)
    }

    /** Write [values] to a row as one edit: every field gets the same fresh stamp. */
    suspend fun edit(entity: String, uid: String, values: Map<String, JsonElement>): ChangeResult =
        local { stamp -> Change(entity, uid, fields = values.mapValues { Stamped(it.value, stamp) }) }

    suspend fun delete(entity: String, uid: String): ChangeResult =
        local { stamp -> Change(entity, uid, deleted = stamp) }

    private suspend fun local(build: (String) -> Change): ChangeResult = lock.withLock {
        db.transactionWithResult {
            val stamp = clock.tick(now()).encoded
            val rows = SqlRows()
            val outcome = Merge.apply(rows, build(stamp), allowReadonly = false)
            for (t in outcome.touched) {
                val dirty = rows.dirtyOf(t.row.entity, t.row.uid)
                val next = if (t.row.isTombstone) setOf(DELETED) else dirty + t.appliedFields
                writeDirty(t.row, next)
            }
            q.setClock(clock.last.encoded)
            outcome.result
        }
    }

    // ── Push ────────────────────────────────────────────────────────────────

    /**
     * Up to [limit] changes carrying everything this replica has not had
     * acknowledged. A tombstone goes as its delete alone — its fields are gone.
     */
    suspend fun dirtyChanges(limit: Long): List<Change> = lock.withLock {
        q.selectDirty(limit).executeAsList().map { r ->
            val row = r.toRow()
            val dirty = decodeDirty(r.dirty)
            if (row.isTombstone) {
                Change(row.entity, row.uid, deleted = row.deleted)
            } else {
                Change(
                    row.entity, row.uid,
                    fields = dirty.filter { it != DELETED && it in row.fields }
                        .associateWith { Stamped(row.fields.getValue(it), row.clock.getValue(it)) },
                )
            }
        }
    }

    /**
     * Clear what the server has accepted — or refused for good.
     *
     * Any answer but a transient rejection acknowledges the change: a field
     * the server refused as stale or deleted will never be accepted, and the
     * server's newer value arrives with the next pull. Only a clock-skew
     * rejection keeps the change queued, because it becomes acceptable once
     * the server's clock catches up.
     */
    suspend fun acknowledge(sent: List<Change>, results: List<ChangeResult>) = lock.withLock {
        val byKey = results.associateBy { it.entity to it.uid }
        db.transaction {
            for (change in sent) {
                val result = byKey[change.entity to change.uid] ?: continue
                if (result.status == ChangeStatus.REJECTED && result.reason == RejectReason.CLOCK_SKEW) continue
                val current = q.selectRow(change.entity, change.uid).executeAsOneOrNull() ?: continue
                val row = current.toRow()
                val remaining = decodeDirty(current.dirty).filterNot { field ->
                    if (field == DELETED) {
                        row.deleted == change.deleted
                    } else {
                        // Edited again since this push was built: keep it.
                        change.fields[field]?.stamp == row.clock[field] || row.isTombstone
                    }
                }.toSet()
                writeDirty(row, remaining)
            }
        }
    }

    /** Move the clock past the server's, as the push response asks. */
    suspend fun receiveClock(stamp: String) = lock.withLock {
        val hlc = Hlc.parseOrNull(stamp) ?: return@withLock
        // A server clock a day ahead of ours is refused like any other stamp;
        // the push itself already happened, so there is nothing to undo.
        if (clock.wouldRefuse(hlc, now())) return@withLock
        clock.receive(hlc, now())
        q.setClock(clock.last.encoded)
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    /**
     * Merge a pulled page and move the cursor, together or not at all.
     *
     * Pulled stamps are never refused, however far ahead of this phone's
     * clock: the server vetted them against its own, and a phone whose clock
     * is wrong would otherwise stall its cursor forever. The clock just moves
     * past them ([HlcClock.receivePulled]).
     *
     * The caller must have settled the page's [ServerIdentity] first — see
     * [ReplicaSyncEngine] — because rows from a recreated server, or from an
     * account wiped since, must not be merged as if nothing had happened.
     */
    suspend fun applyPulled(changes: List<Change>, nextCursor: Long): List<ChangeResult> = lock.withLock {
        val nowMs = now()
        db.transactionWithResult {
            val rows = SqlRows()
            val results = changes.map { change ->
                change.stamps.forEach { clock.receivePulled(Hlc.parse(it), nowMs) }
                val outcome = Merge.apply(rows, change, allowReadonly = true)
                for (t in outcome.touched) {
                    val dirty = rows.dirtyOf(t.row.entity, t.row.uid)
                    // A remote write that won a field makes the local edit to it
                    // moot; a remote delete makes every local edit moot.
                    val next = if (t.row.isTombstone) emptySet() else dirty - t.appliedFields
                    writeDirty(t.row, next)
                }
                outcome.result
            }
            q.setClock(clock.last.encoded)
            q.setCursor(nextCursor)
            results
        }
    }

    /**
     * Load a backup's rows onto this phone.
     *
     * Merged exactly as a pull would be — stamps and all — so restoring onto a
     * phone that already holds some of the same rows keeps whichever write is
     * newer rather than overwriting. Then everything is marked for the next
     * push and the cursor goes back to zero: a restored phone cannot know what
     * a server has already seen, and re-sending is idempotent where guessing
     * is not.
     *
     * [binding] is the account the backup's phone was linked to, kept so a
     * restored phone can only be linked back to that account (see [link]).
     * The node id is NOT restored: the old phone may still exist, and two
     * installs minting stamps under one node id could tie where they must not.
     */
    suspend fun restoreBackup(changes: List<Change>, binding: AccountBinding?) = lock.withLock {
        val nowMs = now()
        db.transaction {
            val rows = SqlRows()
            for (change in changes) {
                change.stamps.forEach { clock.receivePulled(Hlc.parse(it), nowMs) }
                val outcome = Merge.apply(rows, change, allowReadonly = true)
                for (t in outcome.touched) writeDirty(t.row, rows.dirtyOf(t.row.entity, t.row.uid))
            }
            q.setClock(clock.last.encoded)
            markAllDirty()
            q.setCursor(0)
            if (binding != null && q.selectState().executeAsOne().bound_server == null) {
                q.setBinding(binding.serverId, binding.account)
            }
        }
    }

    /** Record the server identity a pull reported, without touching any rows. */
    suspend fun recordServer(server: ServerIdentity) = lock.withLock {
        q.setServer(server.serverId, server.epoch)
    }

    /**
     * The server was recreated: a new [ServerIdentity.serverId]. Its database
     * is new, so everything this phone holds is marked dirty for the next push
     * and the pull restarts from zero against the new server.
     *
     * Tombstones are re-pushed too, not only live rows. The new server has no
     * record of the deletes, and another phone that has not heard of them yet
     * would otherwise put those rows back.
     *
     * Deliberately not "drop the local rows", which is what the old mirror did:
     * these are sources, and for data the server no longer has, this phone may
     * be the only copy left.
     */
    suspend fun resetForNewServer(server: ServerIdentity) = lock.withLock {
        db.transaction {
            markAllDirty()
            q.setCursor(0)
            q.setServer(server.serverId, server.epoch)
        }
    }

    /**
     * The account was wiped on purpose — its epoch rose, or a push came back
     * [RejectReason.WIPED]. Every synced row goes, unpushed edits included:
     * a phone that kept them would quietly undo the deletion on its next push.
     * The binding stays; this is still that account's phone, now empty.
     *
     * [server] is the identity to record, or null when it is not known yet
     * (a rejected push carries none), in which case the next pull records it
     * without treating it as a second wipe.
     */
    suspend fun wipeForEpoch(server: ServerIdentity?) = lock.withLock {
        db.transaction {
            q.deleteAllRows()
            q.setCursor(0)
            val s = q.selectState().executeAsOne()
            q.setServer(server?.serverId ?: s.server_id, server?.epoch)
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    /**
     * Run [block] against the SQL-backed [RowAccess] in a transaction, with no
     * clock or dirty bookkeeping. For the fixture replay, which has to prove
     * the merge behaves identically over this table and over a map — the JSON
     * round trip and the parent index are where the two could differ.
     */
    internal suspend fun <T> withRows(block: (RowAccess) -> T): T = lock.withLock {
        db.transactionWithResult { block(SqlRows()) }
    }

    private fun writeDirty(row: SyncedRow, dirty: Set<String>) {
        q.setDirty(
            dirty = json.encodeToString(dirtyList, dirty.sorted()),
            is_dirty = if (dirty.isEmpty()) 0L else 1L,
            entity = row.entity,
            uid = row.uid,
        )
    }

    private fun decodeDirty(text: String): Set<String> = json.decodeFromString(dirtyList, text).toSet()

    private fun Synced_row.toRow(): SyncedRow = SyncedRow(
        entity = entity,
        uid = uid,
        fields = json.parseToJsonElement(fields).jsonObject,
        clock = json.parseToJsonElement(clock).jsonObject.mapValues { it.value.jsonPrimitive.content },
        deleted = deleted,
    )

    /** [RowAccess] over the table, preserving each row's dirty set across a merge's writes. */
    private inner class SqlRows : RowAccess {
        fun dirtyOf(entity: String, uid: String): Set<String> =
            q.selectRow(entity, uid).executeAsOneOrNull()?.let { decodeDirty(it.dirty) }.orEmpty()

        override fun get(entity: String, uid: String): SyncedRow? =
            q.selectRow(entity, uid).executeAsOneOrNull()?.toRow()

        override fun put(row: SyncedRow) {
            val existing = q.selectRow(row.entity, row.uid).executeAsOneOrNull()
            val parentField = SyncRegistry[row.entity]?.childOf?.field
            q.upsertRow(
                entity = row.entity,
                uid = row.uid,
                fields = JsonObject(row.fields).toString(),
                clock = JsonObject(row.clock.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString(),
                deleted = row.deleted,
                dirty = existing?.dirty ?: "[]",
                parent_uid = parentField?.let { row.fields[it].uidOrNull() },
                is_dirty = existing?.is_dirty ?: 0L,
            )
        }

        override fun children(childEntity: String, parentField: String, parentUid: String): List<SyncedRow> =
            q.selectChildren(childEntity, parentUid).executeAsList().map { it.toRow() }
    }

    companion object {
        /** Stands for the delete in a dirty set. Not a legal field name, so it cannot collide. */
        const val DELETED = "_deleted"

        /** Creates the schema on a fresh driver, for tests and first runs outside Android. */
        fun create(driver: SqlDriver, now: () -> Long, random: Random = Random.Default): ReplicaStore {
            TracksSchema.create(driver)
            return ReplicaStore(driver, now, random)
        }
    }
}
