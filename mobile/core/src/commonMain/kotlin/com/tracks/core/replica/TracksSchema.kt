// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.tracks.core.db.TracksDb

/**
 * The phone database's schema, and the migrations between its versions.
 *
 * ## Why migrations
 *
 * For a phone with no server this database is the only copy of the user's
 * data, so a schema change has to carry it forward. Version [BASELINE] is the
 * schema Tracks 1.0.0 shipped with; every change since is one entry in
 * [migrations], applied in order. A database below [BASELINE] came from a
 * pre-release build and is dropped and recreated — the only case that still
 * is, because nothing released ever wrote one.
 *
 * ## Why not SQLDelight's own `.sqm` files
 *
 * SQLDelight numbers versions by counting `.sqm` files from 1, and released
 * databases start at [BASELINE], so its generated migrate would try to replay
 * changes those databases already have. [VERSION] is kept by hand instead,
 * and the jvmTest suite holds both ends of it: `SchemaIdentityTest`
 * fingerprints the created tables and fails, naming the new fingerprint,
 * whenever the SQL changes without a bump; `SchemaMigrationTest` builds every
 * released schema from its snapshot, migrates it, and requires the result to
 * be identical to a fresh install.
 *
 * ## Changing the schema
 *
 * 1. Edit the `.sq` files.
 * 2. Bump [VERSION], add `VERSION to { driver -> ... }` to [migrations] with
 *    the ALTER/CREATE statements, and set [FINGERPRINT] to what the test names.
 * 3. Copy the snapshot `SchemaMigrationTest` writes into
 *    `core/src/jvmTest/resources/schema/`, so the next change is tested
 *    against this one.
 */
object TracksSchema : SqlSchema<QueryResult.Value<Unit>> {

    /** The schema 1.0.0 shipped with. Nothing below it is migrated. */
    const val BASELINE: Long = 7

    /** Bump together with [FINGERPRINT] whenever any `.sq` file's schema changes. */
    const val VERSION: Long = 9

    /** SHA-256 of the created schema; see `SchemaIdentityTest`. */
    const val FINGERPRINT: String = "02caae0dd04b0b86a9e091cc16c996da87b38d304f2a5bf036d952a3f4016df1"

    /**
     * The step that takes a database *to* each version, keyed by that version.
     * Every version from [BASELINE] + 1 to [VERSION] must have one.
     */
    val migrations: Map<Long, (SqlDriver) -> Unit> = mapOf(
        // Which server the upload flags belong to (LocalLibrary.uploadsGoTo).
        // Nothing recorded that before, so the flags a phone holds cannot be
        // trusted for whichever server it now syncs with — a phone already
        // moved to a second server has them all set and its history never
        // sent. Clear them: every file goes up once more, and the server
        // answers "duplicate" for each one it already has.
        8L to { driver ->
            driver.execute(
                null,
                "CREATE TABLE local_upload_server (\n" +
                    "    id        INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),\n" +
                    "    server_id TEXT NOT NULL\n" +
                    ")",
                0,
            )
            driver.execute(null, "UPDATE local_file SET uploaded = 0", 0)
        },
        // The dashboard heatmap's packed tracks (LocalLibrary.heatmapTracks).
        // Empty to start: each is cut from its activity's detail the first
        // time the heatmap asks, so the upgrade itself does no work.
        9L to { driver ->
            driver.execute(
                null,
                "CREATE TABLE local_heat_track (\n" +
                    "    uid    TEXT NOT NULL PRIMARY KEY,\n" +
                    "    sha256 TEXT NOT NULL,\n" +
                    "    points BLOB NOT NULL\n" +
                    ")",
                0,
            )
        },
    )

    override val version: Long get() = VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = TracksDb.Schema.create(driver)

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        if (oldVersion < BASELINE) {
            dropAll(driver)
            return create(driver)
        }
        for (target in oldVersion + 1..newVersion) {
            val step = migrations[target]
                ?: error("No migration to schema version $target. See TracksSchema.")
            step(driver)
            // SQLDelight's contract: a callback for version v runs once the
            // database has reached v + 1.
            callbacks.filter { it.afterVersion == target - 1 }.forEach { it.block(driver) }
        }
        return QueryResult.Unit
    }

    /** Every user table, not SQLite's own nor Android's locale bookkeeping. */
    fun dropAll(driver: SqlDriver) {
        val tables = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(0)!!
                QueryResult.Value(names)
            },
            parameters = 0,
        ).value
        tables.forEach { driver.execute(null, "DROP TABLE IF EXISTS \"$it\"", 0) }
    }

    /**
     * The schema's statements in an order that recreates it: tables before
     * the indexes, triggers and views that reference them. What
     * `SchemaMigrationTest`'s snapshots are made of.
     */
    fun schemaStatements(driver: SqlDriver): List<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
            "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' " +
            "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END, name",
        mapper = { cursor ->
            val out = mutableListOf<String>()
            while (cursor.next().value) out += cursor.getString(0)!!
            QueryResult.Value(out)
        },
        parameters = 0,
    ).value

    /** The created schema's SQL, in a stable order — what [FINGERPRINT] is taken over. */
    fun schemaSql(driver: SqlDriver): String = driver.executeQuery(
        identifier = null,
        sql = "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
            "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY type, name",
        mapper = { cursor ->
            val out = StringBuilder()
            while (cursor.next().value) out.append(cursor.getString(0)).append(";\n")
            QueryResult.Value(out.toString())
        },
        parameters = 0,
    ).value

    fun fingerprint(driver: SqlDriver): String = Digest.hex(Digest.sha256(schemaSql(driver).encodeToByteArray()))
}
