// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The merge: how a [Change] lands on whatever a replica already holds.
 *
 * The same code runs for a local edit, a pushed change the fake server
 * receives in tests, and a pulled row — and the server runs the same rules in
 * Python. That sameness is the entire correctness argument: every replica
 * applies every change with one deterministic function whose result does not
 * depend on arrival order, so every replica ends up in the same state.
 * `spec/fixtures/sync_scenarios.json` is what holds the two implementations to
 * one function; `spec/sync.yaml` is where the rules are written down.
 *
 * ## The rules, briefly
 *
 * - A field write lands if its stamp is **strictly** newer than that field's
 *   last. Ties lose, so replaying a change is a no-op.
 * - Fields merge independently: a rename on one phone and a completion tick on
 *   another both survive.
 * - **Delete wins.** A delete clears the row to a tombstone; no later write,
 *   whatever its stamp, brings it back. A newer delete stamp replaces an older
 *   one, so replicas agree on the tombstone itself, not only that it exists.
 * - A log row's fields are written once.
 * - Deleting a parent deletes its children, and a child written under an
 *   already-deleted parent is deleted on arrival. The second half is what
 *   makes the first order-independent: without it, a replica that saw the
 *   parent's delete before the child's create would keep the child, and one
 *   that saw them the other way round would not. A child's parent field is
 *   set once, and a child with a null parent (a hand-added planned workout)
 *   is never cascaded.
 *
 * Fields are applied before the delete in the same change, so a change that
 * both writes and deletes an unseen row leaves a plain tombstone — the same
 * thing a replica that received the two separately would hold.
 */
object Merge {

    /** What a merge did to one row, so the caller can track what is dirty. */
    data class Touched(
        val row: SyncedRow,
        /** Fields whose value changed hands in this merge. */
        val appliedFields: Set<String>,
        /** The row became a tombstone, or its delete stamp moved. */
        val deleteApplied: Boolean,
        /** Deleted as a consequence of a parent, not by this change directly. */
        val cascaded: Boolean = false,
    )

    data class Outcome(val result: ChangeResult, val touched: List<Touched>)

    /**
     * Apply [change] through [rows].
     *
     * [allowReadonly] is true only for rows arriving *from the server*: a
     * readonly entity is the server's to write, and a phone that tried to push
     * one — or a local screen that tried to edit one — is refused.
     *
     * Clock receipt is the caller's job and must happen first: this function
     * trusts every stamp it is given.
     */
    fun apply(rows: RowAccess, change: Change, allowReadonly: Boolean): Outcome {
        val spec = SyncRegistry[change.entity]
            ?: return rejected(change, RejectReason.UNKNOWN_ENTITY)
        if (spec.kind == EntityKind.READONLY && !allowReadonly) {
            return rejected(change, RejectReason.READONLY)
        }

        var row = rows.get(change.entity, change.uid) ?: SyncedRow(change.entity, change.uid)
        val refused = LinkedHashMap<String, Refusal>()
        val applied = LinkedHashSet<String>()
        var fields = row.fields
        var clock = row.clock

        for ((name, write) in change.fields) {
            val previous = clock[name]
            val refusal = when {
                !spec.hasField(name) -> Refusal.UNKNOWN
                row.isTombstone -> Refusal.DELETED
                spec.kind == EntityKind.LOG && name in fields ->
                    if (fields[name] == write.value) Refusal.STALE else Refusal.IMMUTABLE
                // A child's parent is set once. A move racing a delete of the
                // old parent has no order-independent answer, so moving is a
                // delete plus a create. Null is "no parent yet", not a value.
                name == spec.childOf?.field && fields[name].uidOrNull() != null &&
                    fields[name] != write.value -> Refusal.IMMUTABLE
                previous != null && write.stamp <= previous -> Refusal.STALE
                else -> null
            }
            if (refusal != null) {
                refused[name] = refusal
                continue
            }
            fields = fields + (name to write.value)
            clock = clock + (name to write.stamp)
            applied += name
        }
        row = row.copy(fields = fields, clock = clock)

        var deleteApplied = false
        var cascadeStamp: String? = null
        val incomingDelete = change.deleted
        if (incomingDelete != null && (row.deleted == null || incomingDelete > row.deleted!!)) {
            row = row.tombstone(incomingDelete)
            deleteApplied = true
            cascadeStamp = incomingDelete
        }

        // A child written under a parent that is already gone goes with it.
        var consequential = false
        if (!row.isTombstone && spec.childOf != null) {
            val parentUid = (row.fields[spec.childOf.field] as? JsonPrimitive)
                ?.takeUnless { it is JsonNull }?.content
            val parentDeleted = parentUid?.let { rows.get(spec.childOf.entity, it)?.deleted }
            if (parentDeleted != null) {
                row = row.tombstone(parentDeleted)
                consequential = true
                cascadeStamp = parentDeleted
            }
        }

        val attempted = change.fields.size + (if (incomingDelete != null) 1 else 0)
        val succeeded = applied.size + (if (deleteApplied) 1 else 0)
        val status = when {
            succeeded == attempted -> ChangeStatus.APPLIED
            succeeded == 0 -> ChangeStatus.STALE
            else -> ChangeStatus.PARTIAL
        }

        val touched = ArrayList<Touched>()
        if (succeeded > 0 || consequential) {
            rows.put(row)
            touched += Touched(row, applied, deleteApplied || consequential, cascaded = consequential)
        }
        if (cascadeStamp != null) cascade(rows, row.entity, row.uid, cascadeStamp, touched)

        return Outcome(ChangeResult(change.entity, change.uid, status, refused), touched)
    }

    /** Tombstone every child of a deleted row, recursively, with the parent's stamp. */
    private fun cascade(rows: RowAccess, entity: String, uid: String, stamp: String, into: MutableList<Touched>) {
        for (childSpec in SyncRegistry.childrenOf(entity)) {
            for (child in rows.children(childSpec.name, childSpec.childOf!!.field, uid)) {
                if (child.deleted != null && child.deleted >= stamp) continue
                val gone = child.tombstone(stamp)
                rows.put(gone)
                into += Touched(gone, emptySet(), deleteApplied = true, cascaded = true)
                cascade(rows, gone.entity, gone.uid, stamp, into)
            }
        }
    }

    private fun rejected(change: Change, reason: String) = Outcome(
        ChangeResult(change.entity, change.uid, ChangeStatus.REJECTED, reason = reason),
        emptyList(),
    )
}

/**
 * The storage a merge reads and writes through.
 *
 * An interface so the identical merge runs over the SQL store, the in-memory
 * fake server the convergence tests use, and the fixture replay — a merge
 * that was only ever tested against a map would prove nothing about the one
 * the phone runs.
 */
interface RowAccess {
    fun get(entity: String, uid: String): SyncedRow?
    fun put(row: SyncedRow)

    /**
     * Live-or-dead rows of [childEntity] whose [parentField] holds [parentUid].
     * Tombstones have no fields, so they are never returned — which is fine,
     * because a tombstone needs no further cascading.
     */
    fun children(childEntity: String, parentField: String, parentUid: String): List<SyncedRow>
}

/** A [RowAccess] over a map — the fake server's storage and the fixture replay's. */
class MemoryRows(initial: Collection<SyncedRow> = emptyList()) : RowAccess {
    private val rows = LinkedHashMap<Pair<String, String>, SyncedRow>()

    init {
        initial.forEach(::put)
    }

    val all: List<SyncedRow> get() = rows.values.toList()

    override fun get(entity: String, uid: String): SyncedRow? = rows[entity to uid]

    override fun put(row: SyncedRow) {
        rows[row.entity to row.uid] = row
    }

    override fun children(childEntity: String, parentField: String, parentUid: String): List<SyncedRow> =
        rows.values.filter {
            it.entity == childEntity && (it.fields[parentField] as? JsonPrimitive)?.content == parentUid
        }
}

/** Convenience for a field value that should be a uid string. */
internal fun JsonElement?.uidOrNull(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
