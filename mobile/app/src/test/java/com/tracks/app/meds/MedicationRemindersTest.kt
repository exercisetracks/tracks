// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.meds

import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * When the next reminder fires.
 *
 * The chain is self-perpetuating — each alarm arms its successor from the rules
 * carried in its own extras — so two things have to hold or reminders stop
 * silently. [MedicationReminders.nextOccurrence] must always move *forward*
 * (returning the same instant would re-arm an alarm for a moment that has
 * passed, and returning null wrongly ends the chain for good), and the encoding
 * has to survive a round trip through an intent, because a spec that fails to
 * decode is a reminder that fires once and never again.
 */
class MedicationRemindersTest {

    private fun spec(
        time: String = "08:00",
        days: List<Int>? = null,
        start: String? = null,
        end: String? = null,
    ) = MedicationReminders.Spec(
        medicationId = 1,
        scheduleId = 7,
        name = "Metformin",
        dose = "500 mg",
        time = time,
        days = days,
        startDate = start,
        endDate = end,
    )

    @Test
    fun `the next daily dose is today when it has not passed`() {
        val from = LocalDateTime.of(2026, 8, 17, 6, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 17, 8, 0),
            MedicationReminders.nextOccurrence(spec(), from),
        )
    }

    @Test
    fun `the next daily dose is tomorrow once today's has passed`() {
        val from = LocalDateTime.of(2026, 8, 17, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 18, 8, 0),
            MedicationReminders.nextOccurrence(spec(), from),
        )
    }

    @Test
    fun `a dose exactly now is not the next one`() {
        // Strictly after, or an alarm firing at 08:00 would re-arm itself for
        // 08:00 the same day and loop.
        val at = LocalDateTime.of(2026, 8, 17, 8, 0)
        val next = MedicationReminders.nextOccurrence(spec(), at)
        assertEquals(LocalDateTime.of(2026, 8, 18, 8, 0), next)
    }

    @Test
    fun `a weekly schedule skips to its own day`() {
        // Sunday only, from a Monday: six days away.
        val from = LocalDateTime.of(2026, 8, 17, 9, 0)
        assertEquals(java.time.DayOfWeek.MONDAY, from.toLocalDate().dayOfWeek)
        assertEquals(
            LocalDateTime.of(2026, 8, 23, 8, 0),
            MedicationReminders.nextOccurrence(spec(days = listOf(0)), from),
        )
    }

    @Test
    fun `a course that has ended has no next dose`() {
        val from = LocalDateTime.of(2026, 8, 17, 9, 0)
        assertNull(MedicationReminders.nextOccurrence(spec(end = "2026-08-16"), from))
    }

    @Test
    fun `a course that has not started yet still finds its first dose`() {
        val from = LocalDateTime.of(2026, 8, 17, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 1, 8, 0),
            MedicationReminders.nextOccurrence(spec(start = "2026-09-01"), from),
        )
    }

    @Test
    fun `a spec survives the round trip through an intent extra`() {
        val original = spec(time = "21:30", days = listOf(1, 3, 5), start = "2026-01-01", end = "2026-12-31")
        val decoded = MedicationReminders.decodeSpec(MedicationReminders.encodeSpec(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an empty-optional spec round trips too`() {
        val original = MedicationReminders.Spec(
            medicationId = 4,
            scheduleId = 9,
            name = "Vitamin D",
            dose = null,
            time = "07:00",
            days = null,
            startDate = null,
            endDate = null,
        )
        assertEquals(original, MedicationReminders.decodeSpec(MedicationReminders.encodeSpec(original)))
    }

    @Test
    fun `garbage decodes to null rather than throwing`() {
        assertNull(MedicationReminders.decodeSpec(null))
        assertNull(MedicationReminders.decodeSpec("not a spec"))
    }

    @Test
    fun `only schedules that asked for a reminder produce one`() {
        val medication = Medication(
            id = 1,
            name = "Metformin",
            isActive = true,
            schedules = listOf(
                MedicationSchedule(id = 1, timeOfDay = "08:00", notify = true),
                MedicationSchedule(id = 2, timeOfDay = "20:00", notify = false),
                // As-needed has no time to fire at, whatever the flag says.
                MedicationSchedule(id = 3, timeOfDay = "12:00", notify = true, isAsNeeded = true),
            ),
        )
        val stopped = medication.copy(id = 2, isActive = false)

        val specs = MedicationReminders.specsFrom(listOf(medication, stopped))
        assertEquals(1, specs.size)
        assertEquals(1, specs.single().scheduleId)
        assertTrue(specs.single().name == "Metformin")
    }
}
