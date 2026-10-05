// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.meds

import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MedicationLogCreate
import com.tracks.core.api.MedicationSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Which doses are due, and whether they have been taken.
 *
 * This is the one calculation in the app where being wrong is a health outcome
 * rather than a cosmetic one. Two specific ways it can be wrong are covered
 * here because both have precedent: the day-of-week convention (the server
 * counts Sunday as 0, `java.time` counts Monday as 1, and confusing them shifts
 * every reminder by a day while still looking plausible), and matching a logged
 * dose to its slot across a timezone boundary — a dose ticked off at 9pm in
 * Edmonton is stored as tomorrow in UTC, and comparing ISO prefixes would re-offer
 * a dose already taken.
 */
class MedicationPlanTest {

    private val edmonton = ZoneId.of("America/Edmonton")

    private fun schedule(
        id: Int,
        time: String = "08:00",
        days: List<Int>? = null,
        start: String? = null,
        end: String? = null,
        notify: Boolean = false,
        asNeeded: Boolean = false,
    ) = MedicationSchedule(
        id = id,
        timeOfDay = time,
        daysOfWeek = days,
        startDate = start,
        endDate = end,
        notify = notify,
        isAsNeeded = asNeeded,
    )

    private fun medication(
        id: Int = 1,
        active: Boolean = true,
        schedules: List<MedicationSchedule>,
    ) = Medication(id = id, name = "Metformin", isActive = active, schedules = schedules)

    @Test
    fun `an every-day schedule is due every day`() {
        val med = medication(schedules = listOf(schedule(1)))
        val monday = LocalDate.of(2026, 8, 17)
        assertEquals(1, dosesOn(monday, listOf(med), emptyList()).size)
        assertEquals(1, dosesOn(monday.plusDays(1), listOf(med), emptyList()).size)
    }

    @Test
    fun `day numbers count Sunday as zero`() {
        // 2026-08-16 is a Sunday. A schedule listing day 0 fires on it and not
        // on the Monday after — the opposite of what java.time's numbering
        // would produce if it were used directly.
        val sundayOnly = medication(schedules = listOf(schedule(1, days = listOf(0))))
        val sunday = LocalDate.of(2026, 8, 16)
        assertEquals(java.time.DayOfWeek.SUNDAY, sunday.dayOfWeek)

        assertEquals(1, dosesOn(sunday, listOf(sundayOnly), emptyList()).size)
        assertEquals(0, dosesOn(sunday.plusDays(1), listOf(sundayOnly), emptyList()).size)
    }

    @Test
    fun `weekdays are Monday through Friday as one through five`() {
        val weekdays = medication(schedules = listOf(schedule(1, days = listOf(1, 2, 3, 4, 5))))
        val saturday = LocalDate.of(2026, 8, 15)
        assertEquals(java.time.DayOfWeek.SATURDAY, saturday.dayOfWeek)

        assertEquals(0, dosesOn(saturday, listOf(weekdays), emptyList()).size)
        assertEquals(0, dosesOn(saturday.plusDays(1), listOf(weekdays), emptyList()).size)
        assertEquals(1, dosesOn(saturday.plusDays(2), listOf(weekdays), emptyList()).size)
    }

    @Test
    fun `a schedule outside its dates is not due`() {
        val med = medication(
            schedules = listOf(schedule(1, start = "2026-08-10", end = "2026-08-14")),
        )
        assertEquals(0, dosesOn(LocalDate.of(2026, 8, 9), listOf(med), emptyList()).size)
        assertEquals(1, dosesOn(LocalDate.of(2026, 8, 10), listOf(med), emptyList()).size)
        assertEquals(1, dosesOn(LocalDate.of(2026, 8, 14), listOf(med), emptyList()).size)
        assertEquals(0, dosesOn(LocalDate.of(2026, 8, 15), listOf(med), emptyList()).size)
    }

    @Test
    fun `as-needed and inactive medications are never due`() {
        val asNeeded = medication(id = 1, schedules = listOf(schedule(1, asNeeded = true)))
        val stopped = medication(id = 2, active = false, schedules = listOf(schedule(2)))
        val day = LocalDate.of(2026, 8, 17)

        assertTrue(dosesOn(day, listOf(asNeeded, stopped), emptyList()).isEmpty())
        // But it is still offered as an as-needed option.
        assertEquals(listOf(asNeeded), asNeededMedications(listOf(asNeeded, stopped)))
    }

    @Test
    fun `a logged dose marks its own slot and no other`() {
        val med = medication(
            schedules = listOf(schedule(1, time = "08:00"), schedule(2, time = "20:00")),
        )
        val day = LocalDate.of(2026, 8, 17)
        val log = listOf(
            MedicationLog(
                id = 1,
                medicationId = 1,
                scheduleId = 1,
                status = MedicationLogCreate.STATUS_TAKEN,
                scheduledFor = "2026-08-17T08:00",
                loggedAt = "2026-08-17T08:04",
            ),
        )

        val doses = dosesOn(day, listOf(med), log)
        assertEquals(2, doses.size)
        assertTrue(doses[0].taken)
        assertNull(doses[1].status)
    }

    @Test
    fun `an evening dose logged in Edmonton counts for that evening`() {
        // 21:30 in Edmonton is 03:30 UTC the next day. Prefix-matching the ISO
        // string would file this under the 18th and leave the 17th's dose
        // looking untaken.
        val med = medication(schedules = listOf(schedule(1, time = "21:00")))
        val log = listOf(
            MedicationLog(
                id = 1,
                medicationId = 1,
                scheduleId = 1,
                status = MedicationLogCreate.STATUS_TAKEN,
                scheduledFor = null,
                loggedAt = "2026-08-18T03:30+00:00",
            ),
        )

        val doses = dosesOn(LocalDate.of(2026, 8, 17), listOf(med), log, zone = edmonton)
        assertEquals(1, doses.size)
        assertTrue(doses[0].taken)
    }

    @Test
    fun `overdue is only ever true for an unlogged dose`() {
        val med = medication(schedules = listOf(schedule(1, time = "08:00")))
        val day = LocalDate.of(2026, 8, 17)
        val slot = dosesOn(day, listOf(med), emptyList()).single()

        assertFalse(slot.overdue(LocalDateTime.of(day, java.time.LocalTime.of(7, 0))))
        assertTrue(slot.overdue(LocalDateTime.of(day, java.time.LocalTime.of(9, 0))))

        val taken = dosesOn(
            day,
            listOf(med),
            listOf(
                MedicationLog(
                    id = 1,
                    medicationId = 1,
                    scheduleId = 1,
                    status = MedicationLogCreate.STATUS_TAKEN,
                    scheduledFor = "2026-08-17T08:00",
                    loggedAt = "2026-08-17T08:00",
                ),
            ),
        ).single()
        assertFalse(taken.overdue(LocalDateTime.of(day, java.time.LocalTime.of(23, 0))))
    }

    @Test
    fun `doses come back in the order they fall due`() {
        val med = medication(
            schedules = listOf(
                schedule(1, time = "20:00"),
                schedule(2, time = "08:00"),
                schedule(3, time = "13:00"),
            ),
        )
        val times = dosesOn(LocalDate.of(2026, 8, 17), listOf(med), emptyList())
            .map { it.timeOfDay }
        assertEquals(listOf("08:00", "13:00", "20:00"), times)
    }
}
