// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.plan.PlanPhases
import com.tracks.core.plan.PlanPhases.Phase
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plan built on the phone, with no server: the generator's output lands in
 * the replica as one generation of one plan, and rebuilding replaces it.
 */
class LocalPlanningTest {

    private val now = 1_790_000_000_000L          // 2026-09-21
    private val today = CivilDate(2026, 9, 21)

    private fun setup(): Pair<LocalSources, LocalPlanning> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val library = LocalLibrary(driver)
        val sources = LocalSources(ReplicaStore(driver, { now }), library)
        return sources to LocalPlanning(sources, library)
    }

    private suspend fun LocalSources.goal(date: String = "2026-11-15"): Int = createValues(
        "goal",
        mapOf(
            "goal_type" to "event", "is_active" to true, "event_name" to "Half",
            "event_sport" to "running", "event_date" to date,
            "event_distance_meters" to 21097.0, "days_per_week" to 4,
        ),
    )

    @Test
    fun a_built_plan_carries_one_generation_under_the_goals_plan() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        val built = planning.regenerate(goal, today, now).getOrThrow()
        val rows = sources.replica.rows("planned_workout")
        assertEquals(built.workouts, rows.size)
        assertTrue(rows.isNotEmpty())
        val plan = sources.replica.rows("plan").single()
        assertTrue(rows.all { it.str("plan_uid") == plan.uid })
        assertEquals(setOf(plan.str("generation")), rows.map { it.str("generation") }.toSet())
    }

    /**
     * A goal that asks for strength gets strength sessions and stretch flows
     * on the phone too — before, a phone-built plan was endurance only until a
     * server regenerated it. Every injected session is part of the one
     * generation, so a later rebuild replaces them with the rest.
     */
    @Test
    fun a_strength_goal_built_on_the_phone_carries_strength_and_stretch_sessions() = runBlocking {
        val (sources, planning) = setup()
        val id = sources.goal()
        sources.setValues("goal", id, mapOf("include_strength" to true, "strength_tier" to 3))
        planning.regenerate(sources.goals().single(), today, now).getOrThrow()
        val rows = sources.replica.rows("planned_workout")
        val types = rows.map { it.str("workout_type") }.toSet()
        assertTrue("strength" in types, "types: $types")
        assertTrue("flexibility" in types, "types: $types")
        assertEquals(1, rows.map { it.str("generation") }.toSet().size)
    }

    /** Without include_strength the plan still gets its post-workout stretch flows, as on the server. */
    @Test
    fun a_goal_without_strength_still_gets_stretch_flows_but_no_strength() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        planning.regenerate(sources.goals().single(), today, now).getOrThrow()
        val types = sources.replica.rows("planned_workout").map { it.str("workout_type") }.toSet()
        assertTrue("flexibility" in types, "types: $types")
        assertTrue("strength" !in types, "types: $types")
    }

    @Test
    fun rebuilding_replaces_the_generated_workouts_and_keeps_hand_added_ones() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        planning.regenerate(goal, today, now).getOrThrow()
        sources.createValues("planned_workout", mapOf("scheduled_date" to "2026-10-01", "title" to "Mine", "sport" to "running", "workout_type" to "easy"))
        val second = planning.regenerate(goal, today, now + 1_000).getOrThrow()

        val live = sources.plannedWorkouts()
        assertEquals(second.workouts + 1, live.size)
        assertTrue(live.any { it.title == "Mine" })
        val generations = sources.replica.rows("planned_workout").mapNotNull { it.str("generation") }.toSet()
        assertEquals(setOf(sources.replica.rows("plan").single().str("generation")), generations)
    }

    /**
     * Editing a plan after its first day used to wipe the days already behind
     * it: every workout of the old generation was deleted. A rebuild two days
     * in leaves those two days exactly as they were (in the new generation,
     * so no replica reaps them) and replaces today onwards.
     */
    @Test
    fun a_rebuild_days_later_leaves_the_days_before_today_as_they_were() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        planning.regenerate(goal, today, now).getOrThrow()
        val later = CivilDate.fromEpochDay(today.epochDay + 2)
        val before = sources.replica.rows("planned_workout").associateBy { it.uid }
        val past = before.values.filter { civilOrNull(it.str("scheduled_date")!!)!! < later }
        val ahead = before.values.filter { civilOrNull(it.str("scheduled_date")!!)!! >= later }
        assertTrue(past.isNotEmpty() && ahead.isNotEmpty())

        planning.regenerate(goal, later, now + 2 * 86_400_000L).getOrThrow()

        val generation = sources.replica.rows("plan").single().str("generation")
        for (r in past) {
            val row = sources.replica.row("planned_workout", r.uid)!!
            assertTrue(!row.isTombstone, "kept: ${r.str("scheduled_date")}")
            assertEquals(generation, row.str("generation"))
            for (f in listOf("scheduled_date", "title", "workout_type", "duration_minutes", "steps")) {
                assertEquals(r.fields[f], row.fields[f], f)
            }
        }
        assertTrue(ahead.all { sources.replica.row("planned_workout", it.uid)!!.isTombstone })
    }

    /**
     * A session finished today survives a rebuild today, and the day does not
     * get it a second time; the unfinished ones that day are rebuilt — the
     * same rule as the server's `_replace_workouts`.
     */
    @Test
    fun a_rebuild_keeps_what_is_done_today_and_replaces_the_rest() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        planning.regenerate(goal, today, now).getOrThrow()
        val rows = sources.replica.rows("planned_workout").sortedBy { it.str("scheduled_date") }
        val done = rows[0]
        val open = rows[1]
        sources.setValues("planned_workout", sources.idOf(done.uid),
            mapOf("scheduled_date" to today.isoformat(), "is_complete" to true, "title" to "Done this morning"))
        sources.setValues("planned_workout", sources.idOf(open.uid), mapOf("scheduled_date" to today.isoformat()))

        planning.regenerate(goal, today, now + 1_000).getOrThrow()

        val kept = sources.replica.row("planned_workout", done.uid)!!
        assertTrue(!kept.isTombstone)
        assertEquals("Done this morning", kept.str("title"))
        assertEquals(sources.replica.rows("plan").single().str("generation"), kept.str("generation"))
        assertTrue(sources.replica.row("planned_workout", open.uid)!!.isTombstone)
        val doneRole = com.tracks.core.plan.MovedWorkouts.role(done.str("workout_type"))
        val sameRoleToday = sources.replica.rows("planned_workout").filter {
            !it.isTombstone && it.str("scheduled_date") == today.isoformat() &&
                com.tracks.core.plan.MovedWorkouts.role(it.str("workout_type")) == doneRole
        }
        assertEquals(listOf(done.uid), sameRoleToday.map { it.uid })
    }

    @Test
    fun a_goal_whose_event_has_passed_is_refused_rather_than_planned() = runBlocking {
        val (sources, planning) = setup()
        sources.goal(date = "2026-09-01")
        val result = planning.regenerate(sources.goals().single(), today, now)
        assertEquals(LocalPlanning.Refusal.Past, (result.exceptionOrNull() as LocalPlanning.Refused).refusal)
        assertTrue(sources.replica.rows("planned_workout").isEmpty())
    }

    /**
     * A fitness goal has no date, and was refused like a volume goal until it
     * got a plan of its own: four weeks from this Monday, strength included,
     * nothing past the fourth Sunday even though strength alone would run on.
     */
    @Test
    fun a_fitness_goal_is_planned_four_weeks_ahead_on_the_phone() = runBlocking {
        val (sources, planning) = setup()
        sources.createValues(
            "goal",
            mapOf(
                "goal_type" to "fitness", "is_active" to true, "event_sport" to "cycling",
                "ctl_ramp_per_week" to 3.0, "days_per_week" to 4, "include_strength" to true,
            ),
        )
        planning.regenerate(sources.goals().single(), today, now).getOrThrow()
        val rows = sources.replica.rows("planned_workout")
        val dates = rows.mapNotNull { it.str("scheduled_date") }
        assertTrue(rows.any { it.str("sport") == "cycling" })
        assertTrue(rows.any { it.str("workout_type") == "strength" })
        assertTrue(dates.min() >= today.isoformat())
        assertTrue(dates.max() <= "2026-10-18", "last day ${dates.max()}")  // the fourth Sunday
    }

    @Test
    fun a_volume_goal_is_still_not_planned() = runBlocking {
        val (sources, planning) = setup()
        sources.createValues("goal", mapOf("goal_type" to "volume_target", "is_active" to true, "target_weekly_km" to 30.0))
        val result = planning.regenerate(sources.goals().single(), today, now)
        assertEquals(LocalPlanning.Refusal.NotAnEvent, (result.exceptionOrNull() as LocalPlanning.Refused).refusal)
    }

    @Test
    fun activating_a_goal_leaves_it_the_only_active_one() = runBlocking {
        val (sources, planning) = setup()
        val a = sources.goal()
        val b = sources.goal(date = "2027-03-01")
        planning.activate(a, sources.goals())
        assertEquals(listOf(a), sources.goals().filter { it.isActive }.map { it.id })
        planning.activate(b, sources.goals())
        assertEquals(listOf(b), sources.goals().filter { it.isActive }.map { it.id })
    }

    @Test
    fun moving_a_workout_writes_only_its_date_and_the_moved_mark() = runBlocking {
        val (sources, planning) = setup()
        val id = sources.createValues(
            "planned_workout",
            mapOf("scheduled_date" to "2026-10-01", "title" to "Tempo", "sport" to "running", "workout_type" to "tempo"),
        )
        val before = sources.row("planned_workout", id)!!.clock
        planning.reschedule(id, CivilDate(2026, 10, 3))
        val after = sources.row("planned_workout", id)!!
        assertEquals("2026-10-03", after.str("scheduled_date"))
        // Only the date was re-stamped: a title edited on another device
        // while this phone was offline must still be able to win.
        assertEquals(before - "scheduled_date", after.clock - "scheduled_date" - "moved_by_user")
        assertTrue(after.clock.getValue("scheduled_date") > before.getValue("scheduled_date"))
        assertEquals("true", after.fields["moved_by_user"].toString())
    }

    /**
     * A generated long run dragged two days later stays there through a
     * rebuild, takes the rebuilt plan's long-run content, and its week does
     * not get the rebuilt long run a second time on the generator's day.
     */
    @Test
    fun a_moved_workout_keeps_its_day_through_a_rebuild_and_the_week_gets_it_once() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        planning.regenerate(goal, today, now).getOrThrow()
        val long = sources.plannedWorkouts().filter { it.workoutType == "long" }.minBy { it.scheduledDate }
        val day = civilOrNull(long.scheduledDate)!!
        val newDay = CivilDate.fromEpochDay(day.epochDay + 1)
        planning.reschedule(long.id, newDay)
        sources.setValues("planned_workout", long.id, mapOf("duration_minutes" to 1))

        planning.regenerate(goal, today, now + 1_000).getOrThrow()

        val row = sources.row("planned_workout", long.id)!!
        assertTrue(!row.isTombstone)
        assertEquals(newDay.isoformat(), row.str("scheduled_date"))
        assertTrue((row.int("duration_minutes") ?: 0) > 1, "content refreshed: ${row.fields["duration_minutes"]}")
        assertEquals(sources.replica.rows("plan").single().str("generation"), row.str("generation"))
        val monday = newDay.epochDay - (newDay.epochDay + 3).mod(7L)
        val longsThatWeek = sources.plannedWorkouts().filter {
            it.workoutType == "long" && civilOrNull(it.scheduledDate)!!.epochDay in monday until monday + 7
        }
        assertEquals(listOf(long.id), longsThatWeek.map { it.id })
    }

    @Test
    fun deleting_a_goal_takes_its_plan_but_not_hand_added_workouts() = runBlocking {
        val (sources, planning) = setup()
        sources.goal()
        val goal = sources.goals().single()
        planning.regenerate(goal, today, now).getOrThrow()
        sources.createValues("planned_workout", mapOf("scheduled_date" to "2026-10-01", "title" to "Mine", "sport" to "running", "workout_type" to "easy"))
        planning.deleteGoal(goal, now)
        assertTrue(sources.goals().isEmpty())
        assertEquals(listOf("Mine"), sources.plannedWorkouts().map { it.title })
    }

    @Test
    fun a_twelve_week_plan_opens_in_base_and_splits_like_the_web() {
        val start = CivilDate(2026, 9, 1)
        val event = CivilDate.fromEpochDay(start.epochDay + 84)
        val first = PlanPhases.info(event, start, start)
        assertEquals(Phase.BASE, first.current)
        // The web's split for 12 weeks: taper 2, peak 2, build 2, base 6.
        assertEquals(mapOf(Phase.BASE to 6, Phase.BUILD to 2, Phase.PEAK to 2, Phase.TAPER to 2), first.weeks)
        assertEquals(Phase.TAPER, PlanPhases.info(event, start, CivilDate.fromEpochDay(event.epochDay - 3)).current)
    }

    @Test
    fun a_one_week_plan_is_all_taper() {
        val start = CivilDate(2026, 9, 1)
        assertEquals(Phase.TAPER, PlanPhases.info(CivilDate(2026, 9, 5), start, start).current)
    }
}
