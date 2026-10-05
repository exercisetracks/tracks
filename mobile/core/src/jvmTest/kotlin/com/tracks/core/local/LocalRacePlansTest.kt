// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.race.RacePredictor
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A race plan's strategy is one synced row per goal; its paces are derived from it. */
class LocalRacePlansTest {

    private fun setup(): Pair<LocalSources, LocalRacePlans> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val sources = LocalSources(ReplicaStore(driver, { 1_790_000_000_000L }), LocalLibrary(driver))
        return sources to LocalRacePlans(sources)
    }

    @Test
    fun setting_strategy_twice_writes_one_race_plan_for_the_goal() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val goal = sources.goals().single()
        plans.set(goal, "course_type", "hilly")
        plans.set(goal, "split_spread", -0.2)
        assertEquals(1, sources.replica.rows("race_plan").size)
        val s = plans.strategy(goal)
        assertEquals("hilly", s.courseType)
        assertEquals(-0.2, s.splitSpread)
    }

    /** A climbing course: 60 points ~45 m apart, rising 3 m each. */
    private fun climbGpx(): String {
        val pts = (0 until 60).joinToString("") { i ->
            """<trkpt lat="${39.75 + i * 0.0004}" lon="-150.22"><ele>${1800 + 3 * i}</ele></trkpt>"""
        }
        return """<gpx xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>$pts</trkseg></trk></gpx>"""
    }

    /**
     * A course imported on the phone is stored as the server stores one and
     * then drives the prediction: before, a GPX file could only be uploaded on
     * the web, so an offline phone always predicted on the terrain setting.
     */
    @Test
    fun an_imported_course_is_stored_and_shapes_the_prediction() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val goal = sources.goals().single()
        assertTrue(plans.importCourse(goal, climbGpx()))
        val s = plans.strategy(goal)
        assertTrue(s.hasCourse)
        assertTrue(s.segments.isNotEmpty())
        val row = sources.replica.rows("race_plan").single()
        assertTrue(row.fields.containsKey("course_path") && row.fields.containsKey("technicality_factor"))

        val flat = LocalRacePlans.running(50.0, 10_000.0, "flat", 0.0, imperial = false, maxHr = null)
        val onCourse = LocalRacePlans.running(50.0, 10_000.0, "flat", 0.0, imperial = false, maxHr = null,
            segments = s.segments, useGpxDistance = true)
        assertTrue(onCourse.seconds < flat.seconds, "a ~2.6 km course is quicker than 10 km")

        plans.removeCourse(goal)
        assertTrue(!plans.strategy(goal).hasCourse)
    }

    /** A file with no track is refused rather than stored as an empty course. */
    @Test
    fun a_file_with_no_track_is_not_a_course() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val goal = sources.goals().single()
        assertTrue(!plans.importCourse(goal, "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"></gpx>"))
        assertTrue(sources.replica.rows("race_plan").isEmpty())
    }

    @Test
    fun terrain_slows_the_prediction_and_laps_add_up_to_it() {
        val flat = LocalRacePlans.running(50.0, 10_000.0, "flat", 0.0, imperial = false, maxHr = null)
        val hilly = LocalRacePlans.running(50.0, 10_000.0, "hilly", 0.0, imperial = false, maxHr = null)
        assertEquals(RacePredictor.predictRaceTimeSec(50.0, 10_000.0), flat.seconds, 0.2)
        assertTrue(hilly.seconds > flat.seconds)
        assertEquals(10, flat.laps.size)
    }

    @Test
    fun an_imperial_plan_laps_by_the_mile_with_hr_ceilings_when_max_hr_is_known() {
        val p = LocalRacePlans.running(50.0, 10_000.0, "flat", 0.1, imperial = true, maxHr = 190)
        assertEquals(7, p.laps.size)                    // 6 whole miles and the remainder
        assertTrue(p.laps.all { it.hrCeiling != null })
        assertTrue(p.laps.first().targetSecPerKm != p.laps.last().targetSecPerKm)
    }

    /**
     * A course drawn on the map becomes the race's course with no server:
     * before, "choose saved course" needed the web, and the phone could only
     * take a GPX file.
     */
    @Test
    fun a_saved_track_becomes_the_race_course() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val geometry = (0 until 60).map { i -> listOf(-150.22, 39.75 + i * 0.0004, 1800.0 + 3 * i) }
        sources.createValues("track", mapOf("name" to "Race", "geometry" to geometry))
        val goal = sources.goals().single()
        val track = sources.replica.rows("track").single()
        assertTrue(plans.importTrack(goal, track.uid))
        val fromTrack = plans.strategy(goal).segments
        plans.removeCourse(goal)
        assertTrue(plans.importCourse(goal, climbGpx()))
        assertEquals(plans.strategy(goal).segments, fromTrack, "a track and the same points as GPX are the same course")
    }

    /**
     * The phone's fuel plan follows the user's overrides and products, and a
     * bad gut day holds the next session's target back — the same plan the
     * web shows, from synced rows alone.
     */
    @Test
    fun the_fuel_plan_uses_overrides_products_and_gut_logs() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val goal = sources.goals().single()
        plans.addProduct("Gel", "gel", 30.0, 50, 0, 0)
        val gel = plans.products().single().uid!!
        plans.set(goal, "fuel_carbs_per_hour", 60)
        plans.set(goal, "fuel_product_uids", listOf(gel))
        for ((i, d) in listOf("2026-09-01", "2026-09-08").withIndex()) {
            sources.createValues("planned_workout", mapOf("scheduled_date" to d, "sport" to "running",
                "workout_type" to "long", "duration_minutes" to 100, "title" to "Long $i"))
        }
        val first = sources.replica.rows("planned_workout").minBy { it.str("scheduled_date")!! }
        val prediction = LocalRacePlans.Prediction(7200.0, emptyList())

        val before = plans.fuel(goal, prediction)
        assertEquals(60, before.targets.carbsGPerH)
        assertTrue(before.timeline.items.all { it.name == "Gel" } && before.timeline.items.isNotEmpty())
        assertEquals(listOf(40, 50), before.gut.values.toList())

        plans.logGut(first.uid, "2026-09-01", 100, 20.0, comfort = 1, notes = "cramps")
        assertEquals(listOf(40, 30), plans.fuel(goal, prediction).gut.values.toList())
    }
}
