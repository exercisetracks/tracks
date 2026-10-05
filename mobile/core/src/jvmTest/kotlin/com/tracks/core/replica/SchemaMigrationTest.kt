// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every schema a released app ever created migrates to exactly the current one.
 *
 * A phone with no server holds the only copy of its user's data, so an upgrade
 * that leaves a column missing or a table half-built is data loss with no
 * backup to fall back on. Each `resources/schema/<version>.sql` is the schema
 * one release created, frozen; this builds a database from it, runs
 * [TracksSchema.migrate], and requires the result to be statement-for-statement
 * what a fresh install creates.
 */
class SchemaMigrationTest {

    private fun current(): List<String> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return TracksSchema.schemaStatements(driver).sorted()
    }

    private fun snapshots(): Map<Long, List<String>> {
        val dir = javaClass.classLoader.getResource("schema")?.let { File(it.toURI()) }
            ?: return emptyMap()
        return dir.listFiles { f -> f.name.endsWith(".sql") }.orEmpty().associate { f ->
            f.name.removeSuffix(".sql").toLong() to f.readText().split(DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    @Test
    fun `the current schema version has a snapshot`() {
        if (TracksSchema.VERSION in snapshots()) return
        // Written where the developer can copy it from rather than into the
        // source tree, so a test run never edits checked-in files on its own.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val out = File("build/schema/${TracksSchema.VERSION}.sql")
        out.parentFile.mkdirs()
        out.writeText(TracksSchema.schemaStatements(driver).joinToString(DELIMITER, postfix = DELIMITER))
        fail(
            "No snapshot of schema version ${TracksSchema.VERSION}. One was written to " +
                "${out.absolutePath}; copy it to core/src/jvmTest/resources/schema/.",
        )
    }

    @Test
    fun `every released schema migrates to the current one`() {
        val snapshots = snapshots()
        assertTrue(TracksSchema.BASELINE in snapshots, "The 1.0.0 baseline snapshot is missing.")
        val expected = current()
        for ((version, statements) in snapshots.toSortedMap()) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            statements.forEach { driver.execute(null, it, 0) }
            TracksSchema.migrate(driver, version, TracksSchema.VERSION)
            assertEquals(
                expected, TracksSchema.schemaStatements(driver).sorted(),
                "Schema $version migrated to ${TracksSchema.VERSION} differs from a fresh install.",
            )
        }
    }

    @Test
    fun `every version after the baseline has a migration`() {
        for (v in TracksSchema.BASELINE + 1..TracksSchema.VERSION) {
            assertTrue(v in TracksSchema.migrations, "No migration to schema version $v.")
        }
    }

    @Test
    fun `migrating keeps the rows a phone already holds`() {
        // The baseline's own tables survive a no-op migration with their rows:
        // the old drop-and-recreate would have passed the identity checks above
        // while emptying the database.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        snapshots().getValue(TracksSchema.BASELINE).forEach { driver.execute(null, it, 0) }
        driver.execute(null, "CREATE TABLE migration_probe (x INTEGER)", 0)
        driver.execute(null, "INSERT INTO migration_probe VALUES (42)", 0)
        TracksSchema.migrate(driver, TracksSchema.BASELINE, TracksSchema.VERSION)
        val kept = driver.executeQuery(null, "SELECT x FROM migration_probe", { c ->
            c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))
        }, 0).value
        assertEquals(42L, kept)
    }

    private companion object {
        const val DELIMITER = "\n-- statement --\n"
    }
}
