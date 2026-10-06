// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.cycling.GoalForDiscipline
import com.tracks.core.cycling.Mtb
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.TrainingGoal
import com.tracks.core.api.TracksJson
import com.tracks.core.replica.OrderKeys
import com.tracks.core.replica.ReadRules
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.SyncRegistry
import com.tracks.core.replica.SyncedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a person said, read and written as the API's own models.
 *
 * The replica stores rows by entity and uid with contract field names
 * (spec/sync.yaml). The screens were written against the server's JSON
 * models, whose names are — deliberately — the same, with two differences
 * this class bridges and nothing else:
 *
 * - **ids**: a model's `id` is the row's stable local alias
 *   ([LocalLibrary.alias]), and a reference the contract spells `goal_uid` is
 *   the model's `goal_id`, the alias of the row it names.
 * - **children**: a flow's stretches, a workout's exercises and a
 *   medication's schedules are rows of their own in the contract (so an edit
 *   on one phone and a reorder on another both survive) but a nested list in
 *   the model. [children]/[replaceChildren] do the nesting.
 *
 * Every write bumps [version], which screens collect to reload.
 */
class LocalSources(
    val replica: ReplicaStore,
    internal val library: LocalLibrary,
) {

    /**
     * Every read and write here is a blocking SQLCipher call. Callers are
     * view models on the main thread, and during a first sync the history
     * import holds the database for long stretches — a main-thread query then
     * waits past Android's 5 s input deadline and the app is reported as not
     * responding. So each suspend function moves itself onto the IO pool
     * (the usual main-safe convention), and no caller has to remember to.
     */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> get() = _version

    private val createJson = kotlinx.serialization.json.Json(TracksJson) { encodeDefaults = true }

    /** Something outside this class changed rows — a pull, an erase. */
    fun changed() {
        _version.value = _version.value + 1
    }

    // ── Read ────────────────────────────────────────────────────────────────

    /** Every live row of [entity], as the model, in no particular order. */
    suspend fun <T> list(entity: String, serializer: KSerializer<T>): List<T> = io {
        replica.rows(entity).mapNotNull { decode(it, serializer) }
    }

    suspend fun <T> get(entity: String, id: Int, serializer: KSerializer<T>): T? = io {
        val uid = library.uidOf(id) ?: return@io null
        return@io replica.row(entity, uid)?.takeIf { !it.isTombstone }?.let { decode(it, serializer) }
    }

    /** The raw row behind a model id, for fields the model does not carry. */
    suspend fun row(entity: String, id: Int): SyncedRow? = io {
        val uid = library.uidOf(id) ?: return@io null
        return@io replica.row(entity, uid)?.takeIf { !it.isTombstone }
    }

    /** The model's JSON for [row]: contract fields, `id`, and `*_uid` refs as `*_id` aliases. */
    fun modelJson(row: SyncedRow): JsonObject {
        val spec = SyncRegistry.require(row.entity)
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(library.alias(row.uid))
        out["uid"] = JsonPrimitive(row.uid)
        for ((field, value) in row.fields) {
            if (field in spec.refs.keys && field.endsWith("_uid")) {
                val idKey = field.removeSuffix("_uid") + "_id"
                out[idKey] = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }
                    ?.let { JsonPrimitive(library.alias(it.content)) } ?: JsonNull
            }
            out[field] = LocalJson.integral(value)
        }
        return JsonObject(out)
    }

    fun <T> decode(row: SyncedRow, serializer: KSerializer<T>): T? =
        runCatching { TracksJson.decodeFromJsonElement(serializer, modelJson(row)) }.getOrNull()

    // ── Activities ──────────────────────────────────────────────────────────

    /**
     * The activity list: what this phone derived from its files, with what
     * the person said about each one laid over it — a rename, a sport
     * correction, a hide, a delete. The measurements are the file's; the name
     * is theirs.
     */
    suspend fun activities(): List<ActivitySummary> = io {
        val said = replica.rows("activity", includeDeleted = true).associateBy { it.uid }
        val uids = library.uidsByAlias()
        return@io library.activities().mapNotNull { a ->
            val edit = uids[a.id]?.let(said::get) ?: return@mapNotNull a
            if (edit.isTombstone || (edit.fields["hidden"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
            a.copy(
                name = if ("name" in edit.fields) edit.str("name") else a.name,
                sport = edit.str("sport") ?: a.sport,
                subSport = edit.str("sub_sport") ?: a.subSport,
            )
        }
    }

    /** Rename, re-sport, note or hide one activity — a source edit on a derived row. */
    suspend fun editActivity(id: Int, values: Map<String, Any?>): Unit = io {
        val uid = library.uidOf(id) ?: return@io
        val spec = SyncRegistry.require("activity")
        replica.edit("activity", uid, values.filterKeys { spec.hasField(it) }.mapValues { LocalJson.of(it.value) })
        changed()
    }

    /** Delete an activity. The file is kept, as every file is; a delete wins over any copy of it. */
    suspend fun deleteActivity(id: Int): Unit = io {
        val uid = library.uidOf(id) ?: return@io
        replica.delete("activity", uid)
        changed()
    }

    // ── Trips ───────────────────────────────────────────────────────────────

    /** Every trip, newest first, with totals derived from members that are still visible. */
    suspend fun trips(): List<TripSummary> = io {
        val visible = activities().associateBy { it.id }
        return@io replica.rows("trip").map { row ->
            val members = (row.fields["activity_uids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .mapNotNull { visible[library.alias(it)] }
            TripTotals.of(library.alias(row.uid), row.str("name") ?: "Trip", row.str("notes"), members)
        }.sortedByDescending { it.startedAt.orEmpty() }
    }

    /** Merge these activities into a new trip. The activities themselves are untouched. */
    suspend fun createTrip(name: String, activityIds: List<Int>): Int? = io {
        val uids = activityIds.mapNotNull { library.uidOf(it) }.distinct()
        if (uids.size < 2) return@io null
        return@io createValues("trip", mapOf("name" to name.trim(), "activity_uids" to uids, "track_uids" to emptyList<String>()))
    }

    // ── The plan ────────────────────────────────────────────────────────────

    /**
     * Planned workouts between two ISO dates (inclusive, either open), by
     * date — minus any left behind by a regeneration (see
     * [com.tracks.core.replica.ReadRules.deadWorkouts]), which are dead the
     * moment a newer generation's plan row arrives even if nobody has deleted
     * them yet.
     */
    suspend fun plannedWorkouts(from: String? = null, to: String? = null): List<PlannedWorkout> = io {
        val rows = replica.rows("planned_workout")
        val dead = ReadRules.deadWorkouts(rows + replica.rows("plan")).toSet()
        return@io rows.asSequence()
            .filter { it.uid !in dead }
            .filter { r ->
                val d = r.str("scheduled_date") ?: return@filter false
                (from == null || d >= from) && (to == null || d <= to)
            }
            .mapNotNull { decode(it, PlannedWorkout.serializer()) }
            .sortedBy { it.scheduledDate }
            .toList()
    }

    /**
     * Every goal, with at most one active: two phones can each activate a
     * different goal offline, and neither write is refused — the one activated
     * last wins when read ([ReadRules.activeGoal]).
     */
    suspend fun goals(): List<TrainingGoal> = io {
        val rows = replica.rows("goal")
        val active = ReadRules.activeGoal(rows)
        return@io rows.mapNotNull { r -> decode(r, TrainingGoal.serializer())?.copy(isActive = r.uid == active) }
    }

    // ── Write ───────────────────────────────────────────────────────────────

    /**
     * Create a row from a model (or any serialisable input shaped like one)
     * and return its id. Only contract fields are written; `x_id` becomes
     * `x_uid`. [keyValues] fills a natural-key entity's uid template.
     */
    suspend fun <T> create(
        entity: String,
        value: T,
        serializer: KSerializer<T>,
        keyValues: Map<String, String?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Int = io {
        // Defaults encoded here, unlike for an edit: a create model's defaults
        // (a workout's `sport = "running"`) are values somebody chose, and a
        // row created without them would sync as a workout with no sport.
        val fields = contractFields(entity, createJson.encodeToJsonElement(serializer, value).jsonObject) +
            extra.mapValues { LocalJson.of(it.value) }
        val (uid, _) = replica.create(entity, fields, keyValues)
        changed()
        return@io library.alias(uid)
    }

    /**
     * Create (or, for a natural-key entity, write into) a row from plain
     * values — strings, numbers, booleans, null. For `:app`, which keeps JSON
     * types out of its own code.
     */
    suspend fun createValues(entity: String, values: Map<String, Any?>, keyValues: Map<String, String?> = emptyMap()): Int = io {
        createFields(entity, values.mapValues { LocalJson.of(it.value) }, keyValues)
    }

    /** Set fields of one row from plain values. */
    suspend fun setValues(entity: String, id: Int, values: Map<String, Any?>) = io {
        editFields(entity, id, values.mapValues { LocalJson.of(it.value) })
    }

    /**
     * The whole settings row as plain values — String, Double, Boolean,
     * List<String>, Map<String, Any?> or null — for the settings and onboarding forms, which edit
     * many fields at once and keep JSON types out of `:app`. A field never
     * written is absent, so a form can tell "not set" from "set to nothing".
     */
    suspend fun settingValues(): Map<String, Any?> = io {
        val row = replica.rows("settings").firstOrNull() ?: return@io emptyMap()
        return@io row.fields.mapValues { (_, v) -> plain(v) }
    }

    private fun plain(v: JsonElement): Any? = when (v) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive ->
            if (v.isString) v.content
            else v.content.toBooleanStrictOrNull() ?: v.content.toDoubleOrNull() ?: v.content
        is kotlinx.serialization.json.JsonArray -> v.map { plain(it)?.toString() ?: "" }
        // A map (activity_frequency, tour_seen) as a map: the form edits one
        // key and writes the whole map back, which a JSON string would not let it.
        is kotlinx.serialization.json.JsonObject -> v.mapValues { (_, x) -> plain(x) }
    }

    /**
     * `activity_frequency` for the plan generator: sport family → level
     * (com.tracks.core.plan.PlanStart). Empty when never answered.
     */
    suspend fun activityFrequency(): Map<String, Any?> = io {
        val v = replica.rows("settings").firstOrNull()?.fields?.get("activity_frequency")
        (v as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, x) -> plain(x) } ?: emptyMap()
    }

    /**
     * The account's zone (`settings.timezone`), which every activity's day is
     * read in ([LocalLibrary.localDay]); null when never set, which reads as
     * UTC.
     */
    suspend fun accountZone(): String? = io { replica.rows("settings").firstOrNull()?.str("timezone") }

    /** One field of the account's settings row, as text; null when never set. */
    suspend fun setting(field: String): String? = io {
        replica.rows("settings").firstOrNull()?.str(field)
    }

    /**
     * `_effective_ftp` / `_effective_threshold_hr`, from the settings row: the
     * manual value when the mode is manual. The automatic values are derived
     * from history and not yet computed on the phone, so an "auto" setting
     * imports with no threshold — the server's own behaviour for an account
     * whose auto value has not been worked out yet.
     */
    suspend fun importThresholds(): ImportThresholds = io {
        val row = replica.rows("settings").firstOrNull() ?: return@io ImportThresholds()
        fun manual(mode: String, value: String) =
            if (row.str(mode) == "manual") row.str(value)?.toDoubleOrNull() else null
        // "auto" is the same rule as the server's: the latest completed field
        // test (LocalMatchEffects), else the whole history (LocalAutoThresholds,
        // the server's recalculate_auto_values). A test is a measurement, so it
        // beats the estimate; the server applies it in both of its writers so
        // a restart's recompute can no longer undo one.
        val tests = LocalMatchEffects(this, library).fieldTests(row.str("ftp_mode"), row.str("threshold_hr_mode"))
        val history = LocalAutoThresholds(this, library).history()
        return@io ImportThresholds(
            ftp = manual("ftp_mode", "ftp_manual") ?: tests.ftpAuto?.toDouble() ?: history.ftp?.toDouble(),
            thresholdHr = manual("threshold_hr_mode", "threshold_hr_manual")
                ?: tests.thresholdHrAuto?.toDouble() ?: history.thresholdHr?.toDouble(),
            maxHr = manual("max_hr_mode", "max_hr_manual") ?: history.maxHr?.toDouble(),
        )
    }

    /**
     * What "auto" resolves to for each threshold, whatever the mode is set to
     * — the web's `*_auto` columns, so Settings can show the value an Auto
     * choice would use before anyone picks it. Same sources as
     * [importThresholds]: the latest field test, else the whole history.
     */
    suspend fun autoThresholds(): ImportThresholds = io {
        val tests = LocalMatchEffects(this, library).fieldTests("auto", "auto")
        val history = LocalAutoThresholds(this, library).history()
        return@io ImportThresholds(
            ftp = tests.ftpAuto?.toDouble() ?: history.ftp?.toDouble(),
            thresholdHr = tests.thresholdHrAuto?.toDouble() ?: history.thresholdHr?.toDouble(),
            maxHr = history.maxHr?.toDouble(),
        )
    }

    /**
     * `active_mtb_discipline`: the active MTB event goal's discipline, which
     * scales MTB load when it is read (TrainingLoad.scaleTss).
     */
    suspend fun mtbDiscipline(): String? = io {
        val rows = replica.rows("goal")
        val active = ReadRules.activeGoal(rows)
        return@io Mtb.activeDiscipline(rows.map { r ->
            GoalForDiscipline(
                isActive = r.uid == active,
                goalType = r.str("goal_type"),
                eventSport = r.str("event_sport"),
                eventDate = r.str("event_date"),
                discipline = r.str("mtb_discipline"),
                eventDistanceMeters = r.str("event_distance_meters")?.toDoubleOrNull(),
            )
        })
    }

    /** Write one field of the account's settings row, which exists once per account. */
    suspend fun writeSetting(field: String, value: Any?): Unit = io {
        replica.create("settings", mapOf(field to LocalJson.of(value)))
        changed()
    }

    /** Create from contract fields directly. */
    suspend fun createFields(entity: String, fields: Map<String, JsonElement>, keyValues: Map<String, String?> = emptyMap()): Int = io {
        val (uid, _) = replica.create(entity, fields, keyValues)
        changed()
        return@io library.alias(uid)
    }

    /**
     * Write the fields [value] sets. A patch model's absent fields are simply
     * not in its JSON (`explicitNulls = false`), so only what was set is
     * written — and only those get fresh stamps, which is the whole point of
     * merging by field.
     */
    suspend fun <T> edit(entity: String, id: Int, value: T, serializer: KSerializer<T>, extra: Map<String, Any?> = emptyMap()): Unit = io {
        editFields(
            entity, id,
            contractFields(entity, TracksJson.encodeToJsonElement(serializer, value).jsonObject) +
                extra.mapValues { LocalJson.of(it.value) },
        )
    }

    suspend fun editFields(entity: String, id: Int, fields: Map<String, JsonElement>): Unit = io {
        val uid = library.uidOf(id) ?: return@io
        if (fields.isEmpty()) return@io
        replica.edit(entity, uid, fields)
        changed()
    }

    suspend fun delete(entity: String, id: Int): Unit = io {
        val uid = library.uidOf(id) ?: return@io
        replica.delete(entity, uid)
        changed()
    }

    /** Delete a row named by uid — for rows with no alias yet (a child never shown). */
    suspend fun deleteUid(entity: String, uid: String): Unit = io {
        replica.delete(entity, uid)
        changed()
    }

    fun uidOf(id: Int): String? = library.uidOf(id)
    fun idOf(uid: String): Int = library.alias(uid)

    // ── Children ────────────────────────────────────────────────────────────

    /** A parent's live children, ordered by their `order` key. */
    suspend fun children(childEntity: String, parentId: Int): List<SyncedRow> = io {
        val spec = SyncRegistry.require(childEntity)
        val link = spec.childOf ?: error("$childEntity has no parent")
        val parentUid = library.uidOf(parentId) ?: return@io emptyList()
        return@io replica.rows(childEntity)
            .filter { (it.fields[link.field] as? JsonPrimitive)?.content == parentUid }
            .sortedWith(compareBy({ (it.fields["order"] as? JsonPrimitive)?.content ?: "" }, { it.uid }))
    }

    /**
     * Make a parent's children exactly [items], in that order.
     *
     * Children are matched by position to what is already there: a stretch
     * whose hold time changed is an edit of that stretch, not a delete and a
     * new one, so a concurrent edit to it on another phone still lands. Extra
     * rows are deleted, new ones created, and `order` keys are rewritten only
     * where the sequence actually changed.
     */
    /** [replaceChildren] from models; `x_id` aliases become `x_uid` as in [create]. */
    suspend fun <T> replaceChildren(childEntity: String, parentId: Int, items: List<T>, serializer: KSerializer<T>) = io {
        replaceChildren(
            childEntity, parentId,
            items.map { contractFields(childEntity, createJson.encodeToJsonElement(serializer, it).jsonObject) },
        )
    }

    suspend fun replaceChildren(childEntity: String, parentId: Int, items: List<Map<String, JsonElement>>): Unit = io {
        val spec = SyncRegistry.require(childEntity)
        val link = spec.childOf ?: error("$childEntity has no parent")
        val parentUid = library.uidOf(parentId) ?: return@io
        val existing = children(childEntity, parentId)
        val keys = OrderKeys.sequence(items.size)
        items.forEachIndexed { i, item ->
            val fields = item.filterKeys { spec.hasField(it) } + ("order" to JsonPrimitive(keys[i]))
            val current = existing.getOrNull(i)
            if (current == null) {
                replica.create(childEntity, fields + (link.field to JsonPrimitive(parentUid)))
            } else {
                val changedFields = fields.filter { (k, v) -> current.fields[k] != v }
                if (changedFields.isNotEmpty()) replica.edit(childEntity, current.uid, changedFields)
            }
        }
        for (extra in existing.drop(items.size)) replica.delete(childEntity, extra.uid)
        changed()
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    /** Keep only [entity]'s contract fields; translate `x_id` aliases to `x_uid`. */
    fun contractFields(entity: String, json: JsonObject): Map<String, JsonElement> {
        val spec = SyncRegistry.require(entity)
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in json) {
            if (k == "id" || k == "uid") continue
            if (k.endsWith("_id")) {
                val uidKey = k.removeSuffix("_id") + "_uid"
                if (spec.hasField(uidKey)) {
                    val alias = (v as? JsonPrimitive)?.intOrNull
                    out[uidKey] = alias?.let { library.uidOf(it) }?.let(::JsonPrimitive) ?: JsonNull
                    continue
                }
            }
            if (spec.hasField(k)) out[k] = v
        }
        return out
    }
}

/** A field's value as a plain string, or null. */
fun SyncedRow.str(field: String): String? =
    (fields[field] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun SyncedRow.int(field: String): Int? = (fields[field] as? JsonPrimitive)?.intOrNull

fun JsonElement?.primitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }
