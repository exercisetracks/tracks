// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimezoneSyncTest {

    /** Travelling left the account's "today" at home: the phone's zone must win. */
    @Test
    fun `a phone in a new zone writes it`() {
        assertEquals("Europe/Rome", TimezoneSync.zoneToWrite("America/Edmonton", "Europe/Rome", rowExists = true))
    }

    @Test
    fun `a zone that already matches is not written again`() {
        // A write is a stamped edit; one per resume would churn the row for nothing.
        assertNull(TimezoneSync.zoneToWrite("Europe/Rome", "Europe/Rome", rowExists = true))
    }

    /**
     * Before its first pull a signed-in phone has no settings row; writing then
     * would create one ahead of the account's.
     */
    @Test
    fun `nothing is written before the settings row exists`() {
        assertNull(TimezoneSync.zoneToWrite(null, "Europe/Rome", rowExists = false))
    }

    @Test
    fun `a zone the server could not parse is never written`() {
        assertNull(TimezoneSync.zoneToWrite("UTC", "Not/AZone", rowExists = true))
        assertNull(TimezoneSync.zoneToWrite("UTC", "", rowExists = true))
    }
}
