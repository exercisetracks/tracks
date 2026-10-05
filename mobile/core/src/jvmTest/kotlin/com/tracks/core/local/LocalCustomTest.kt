// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "+ Custom" and ✓/✗ on the phone, offline, as the web does them. */
class LocalCustomTest {

    private fun training(): LocalTraining {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        return LocalTraining(LocalSources(ReplicaStore(driver, { 1_727_190_000_000L }), LocalLibrary(driver)))
    }

    @Test
    fun a_custom_exercise_joins_the_library_and_can_be_edited_and_removed() = runBlocking {
        val t = training()
        val id = t.saveCustom(false, null, mapOf("name" to "Sandbag Carry", "primary_muscles" to listOf("core")))
        assertEquals(listOf("core"), t.exercises().single { it.name == "Sandbag Carry" }.primaryMuscles)
        t.saveCustom(false, id, mapOf("name" to "Sandbag Carry", "primary_muscles" to listOf("core", "forearms")))
        assertEquals(2, t.exercises().single { it.name == "Sandbag Carry" }.primaryMuscles.size)
        t.deleteCustom(false, id)
        assertTrue(t.exercises().none { it.name == "Sandbag Carry" })
    }

    /** A field the contract does not sync is dropped, not written somewhere nothing reads. */
    @Test
    fun fields_outside_the_contract_are_dropped() = runBlocking {
        val t = training()
        t.saveCustom(true, null, mapOf("name" to "Wall Angel", "each_side" to false, "secret" to 1))
        assertTrue(t.stretches().any { it.name == "Wall Angel" })
    }

    @Test
    fun a_preference_can_be_set_and_cleared() = runBlocking {
        val t = training()
        val name = t.exercises().first().name
        t.setPreference(false, name, "excluded")
        assertEquals("excluded", t.exercises().first { it.name == name }.preference)
        t.setPreference(false, name, null)
        assertNull(t.exercises().first { it.name == name }.preference)
    }
}
