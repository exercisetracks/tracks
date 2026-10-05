// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

/**
 * What syncs, as the phone knows it.
 *
 * The facts come from `spec/sync.yaml` through codegen ([ENTITY_SPECS]); this
 * file holds only their shape. The server reads the same YAML, which is the
 * whole point: a field one side syncs and the other does not would be a field
 * that silently stops agreeing, with nothing failing to say so.
 *
 * "Not listed here" means "does not sync". A column added to a server model,
 * or a property to a phone model, does not travel until the spec says so.
 */
enum class EntityKind {
    /** Anyone may create, edit, and delete. */
    SOURCE,

    /**
     * Anyone may create and retract; fields are written once. A dose taken is a
     * fact, and "correcting" it on one phone while another logs it would be a
     * conflict with no right answer — so a log is never edited, only retracted
     * and re-entered.
     */
    LOG,

    /** Only the server writes. The phone keeps what it is sent. */
    READONLY,
}

/** A row owned by a parent: deleting the parent deletes it. */
data class ChildOf(val field: String, val entity: String)

data class EntitySpec(
    val name: String,
    val kind: EntityKind,
    val fields: List<String>,
    /** Field → the entity whose uid it holds. The value may also be a list of uids. */
    val refs: Map<String, String>,
    /** UUIDv5 name template, e.g. `device:{serial_number}`; null means UUIDv7. */
    val uidKey: String?,
    /** Used when [uidKey] names a value that is missing. */
    val uidKeyFallback: String?,
    val childOf: ChildOf?,
) {
    private val fieldSet: Set<String> = fields.toSet()

    fun hasField(name: String): Boolean = name in fieldSet
}

object SyncRegistry {

    private val byName: Map<String, EntitySpec> = ENTITY_SPECS.associateBy { it.name }

    /** Entities whose rows are children of each entity — for cascading deletes. */
    private val childrenByParent: Map<String, List<EntitySpec>> =
        ENTITY_SPECS.filter { it.childOf != null }.groupBy { it.childOf!!.entity }

    val entities: List<EntitySpec> get() = ENTITY_SPECS

    val specVersion: Int get() = SYNC_SPEC_VERSION

    operator fun get(entity: String): EntitySpec? = byName[entity]

    fun require(entity: String): EntitySpec =
        byName[entity] ?: throw IllegalArgumentException("'$entity' does not sync (spec/sync.yaml)")

    fun childrenOf(entity: String): List<EntitySpec> = childrenByParent[entity].orEmpty()
}
