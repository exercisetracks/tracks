// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tracks.core.race.RacePredictor
import com.tracks.core.replica.ReplicaStore
import com.tracks.core.replica.TracksSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fuel.FuelPlan
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

    /**
     * A run in the library with a 5 km pace best — 20:00 by default, a VDOT
     * ~50 floor — so today's running fitness is measured, as the race plan
     * now requires (it reads the live estimate, as the server does, not the
     * VDOT the training plan was last built at).
     */
    private fun LocalLibrary.run(uid: String, startedAt: String, seconds: Long = 1200) {
        val detail = buildJsonObject { put("pace_curve", buildJsonObject { put("5000", 5000.0 / seconds) }) }
        val summary = buildJsonObject {
            put("started_at", startedAt); put("sport", "running"); put("duration_seconds", seconds)
        }
        q.upsertActivity(uid, "sha-$uid", "serial", startedAt, "running", null, null, seconds, 5000.0,
            null, null, null, null, 5000.0 / seconds, null, null, null, null, null, summary.toString(), detail.toString())
        q.insertAlias(uid)
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

    /**
     * The race-intensity slider is a synced field the phone's plan follows —
     * the screen's and the guided race's alike, or the watch would coach the
     * predicted pace while the screen showed the chosen one. Unset is as
     * predicted, and a value past the slider's end (from an older or newer
     * client) is held to it rather than trusted.
     */
    @Test
    fun a_harder_race_intensity_is_a_faster_plan_everywhere_it_is_used() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2099-11-01",
            "event_sport" to "running", "event_distance_meters" to 10_000.0))
        val goal = sources.goals().single()
        sources.createValues("plan", mapOf("goal_uid" to goal.uid, "vdot" to 50.0), mapOf("goal_uid" to goal.uid))
        sources.library.run("r1", "2025-12-30T08:00:00Z")
        val today = com.tracks.core.fit.decode.CivilDate(2026, 1, 1)
        assertEquals(1.0, plans.strategy(goal).intensity)
        val asPredicted = plans.plan(goal, plans.strategy(goal), imperial = false, today = today)

        plans.set(goal, "intensity", 1.05)
        val s = plans.strategy(goal)
        assertEquals(1.05, s.intensity)
        val harder = plans.plan(goal, s, imperial = false, today = today)
        assertEquals(asPredicted.seconds / 1.05, harder.seconds, 2.0)
        assertTrue(harder.laps.first().targetSecPerKm < asPredicted.laps.first().targetSecPerKm)
        assertEquals(harder.seconds, plans.runningPlan(goal, imperial = false, today = today)!!.first.seconds, 1e-9)

        plans.set(goal, "intensity", 1.2)
        assertEquals(1.2, plans.strategy(goal).intensity, "the slider reaches a fifth either way")
        plans.set(goal, "intensity", 1.4)
        assertEquals(1.2, plans.strategy(goal).intensity)
    }

    /**
     * The race plan predicts race day, not today: today's fitness plus the
     * plan's weeks still to come, and it says what today would be — the
     * server's projection, on the phone (SportPlan.projection).
     */
    @Test
    fun the_race_plan_predicts_race_day_and_says_what_today_would_be() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-03-01",
            "event_sport" to "running", "event_distance_meters" to 10_000.0))
        val goal = sources.goals().single()
        sources.createValues("plan", mapOf("goal_uid" to goal.uid, "vdot" to 49.8), mapOf("goal_uid" to goal.uid))
        sources.library.run("r1", "2025-12-30T08:00:00Z")
        val p = plans.plan(goal, plans.strategy(goal), imperial = false, today = CivilDate(2026, 1, 1))
        val projection = p.projection!!
        // A plan with no history yet is the plan as written: nine weeks to the
        // race week from this Monday, +0.1 VDOT for each of the eight before it.
        assertEquals(1.0, projection.adherence)
        assertEquals(projection.vdotToday + 0.8, projection.vdotRaceDay, 1e-9)
        assertTrue(projection.todaySeconds!! > p.seconds, "today is slower than race day")
        assertEquals(p.seconds, plans.predictedSeconds(goal, CivilDate(2026, 1, 1))!!, p.seconds * 0.03,
            "the goal card is the same race on a neutral day")
    }

    private suspend fun raceSession(sources: LocalSources, goal: com.tracks.core.api.TrainingGoal): Int {
        sources.createValues("plan", mapOf("goal_uid" to goal.uid, "vdot" to 49.8), mapOf("goal_uid" to goal.uid))
        val planUid = sources.replica.rows("plan").single().uid
        sources.createValues("planned_workout", mapOf(
            "plan_uid" to planUid, "scheduled_date" to goal.eventDate, "sport" to "running", "workout_type" to "race",
            "title" to "Race: 10K", "steps" to emptyList<Any?>(), "is_complete" to false,
        ))
        return sources.idOf(sources.replica.rows("planned_workout").single().uid)
    }

    /**
     * The calendar's race day is planned from the race plan, as the server's
     * `_race_day_fit` plans it: the screen's laps, its pace-or-HR choice and
     * fuelling — so the schedule bundle can send the race as one more
     * calendar workout. The race used to reach the watch only as a file the
     * server built, on a route of its own.
     */
    @Test
    fun the_calendars_race_day_is_planned_from_the_race_plan() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-01-03",
            "event_sport" to "running", "event_distance_meters" to 10_000.0))
        val goal = sources.goals().single()
        val id = raceSession(sources, goal)
        sources.library.run("r1", "2025-12-30T08:00:00Z")
        val today = CivilDate(2026, 1, 1)
        val raceDay = com.tracks.core.api.PlannedWorkout(id = id, scheduledDate = "2026-01-03", sport = "running",
            workoutType = "race", title = "Race: 10K")
        val races = plans.raceDayWorkouts(listOf(raceDay), today)
        val shown = plans.plan(goal, plans.strategy(goal), imperial = false, today = today)
        val r = races.getValue(id)
        assertEquals(shown.laps.map { it.targetSecPerKm }, r.laps.map { it.targetSecPerKm })
        assertEquals("running", r.family)
        assertEquals(id, plans.raceWorkoutId(goal))
    }

    @Test
    fun the_race_is_on_the_watch_when_its_race_day_session_is() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-01-03",
            "event_sport" to "running", "event_distance_meters" to 10_000.0))
        val goal = sources.goals().single()
        val id = raceSession(sources, goal)
        assertEquals(null, plans.strategy(goal).watchUploadedAt)
        sources.setValues("planned_workout", id, mapOf("watch_uploaded_at" to "2026-01-02T07:00:00Z"))
        assertEquals("2026-01-02T07:00:00Z", plans.strategy(goal).watchUploadedAt)
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

    /**
     * A race's course goes to the watch's course list for the days its race
     * day is on the calendar, beside it — a workout file cannot carry one —
     * under the race's name, keyed where no track's key can be; and off again
     * once the race is past. The server's `_race_course_items` is its twin.
     */
    @Test
    fun a_race_this_week_puts_its_course_on_the_watch() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-05",
            "event_sport" to "running", "event_name" to "Bern 10K"))
        val goal = sources.goals().single()
        assertTrue(plans.importCourse(goal, climbGpx()))
        val s = plans.strategy(goal)

        val week = plans.raceCourses(com.tracks.core.fit.decode.CivilDate(2026, 11, 1))
        val race = week.single()
        assertEquals(LocalRacePlans.raceCourseRef(goal.id, 0), race.ref)
        assertTrue(race.ref < 0 && LocalRacePlans.isRaceCourseRef(race.ref))
        assertEquals("Bern 10K", race.name)
        assertEquals(s.path.map { it.first }, race.points.map { it.first })
        assertEquals(s.elevations, race.points.map { it.third })

        assertTrue(plans.raceCourses(com.tracks.core.fit.decode.CivilDate(2026, 10, 31)).isEmpty(), "before race week")
        assertEquals(1, plans.raceCourses(com.tracks.core.fit.decode.CivilDate(2026, 11, 5)).size, "race day")
        assertTrue(plans.raceCourses(com.tracks.core.fit.decode.CivilDate(2026, 11, 6)).isEmpty(), "after the race")
    }

    /** A triathlon with no whole course sends each leg file of its own, named by the leg. */
    @Test
    fun a_triathlons_leg_files_go_to_the_watch_one_by_one() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-12-01",
            "event_sport" to "triathlon", "event_distance_meters" to 51500.0, "event_name" to "Lake Tri"))
        val goal = sources.goals().single()
        assertTrue(plans.importCourse(goal, climbGpx(), leg = "run"))
        val race = plans.raceCourses(com.tracks.core.fit.decode.CivilDate(2026, 11, 29)).single()
        assertEquals(LocalRacePlans.raceCourseRef(goal.id, 3), race.ref)
        assertEquals("Lake Tri · Run", race.name)
        assertEquals("running", race.sport)
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
        plans.addProduct(LocalRacePlans.ProductDraft("Gel", "gel", carbsG = 30.0, sodiumMg = 50))
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

    /**
     * Carrying the homemade mix — the reserved uid among the products — puts
     * its sips on the timeline and its recipe in the plan, made to the race's
     * own recipe and bottle.
     */
    @Test
    fun the_homemade_mix_is_sipped_and_its_recipe_follows_the_race() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-11-01", "event_sport" to "running"))
        val goal = sources.goals().single()
        plans.set(goal, "fuel_product_uids", listOf(FuelPlan.HOMEMADE_UID))
        plans.set(goal, "fuel_mix_preset", "natural")
        plans.set(goal, "fuel_mix_bottle_ml", 500)
        val fuel = plans.fuel(goal, LocalRacePlans.Prediction(10800.0, emptyList()))
        assertTrue(fuel.timeline.items.isNotEmpty() && fuel.timeline.items.all { it.productUid == FuelPlan.HOMEMADE_UID })
        val recipe = fuel.homemade!!
        assertEquals("natural", recipe.preset)
        assertEquals(500, recipe.bottleMl)
        assertTrue(recipe.ingredients.any { it.key == "honey" })
    }

    /**
     * A triathlon's leg can take its own file, which wins over its stretch of
     * the whole course and sets the leg's length; removing it puts the leg
     * back on the whole course. Organisers publish either.
     */
    @Test
    fun a_triathlon_legs_own_file_wins_and_can_be_removed() = runBlocking {
        val (sources, plans) = setup()
        sources.createValues("goal", mapOf("goal_type" to "event", "event_date" to "2026-12-01",
            "event_sport" to "triathlon", "event_distance_meters" to 51500.0))
        sources.createValues("settings", mapOf("ftp_mode" to "manual", "ftp_manual" to 250))
        val goal = sources.goals().single()
        val today = com.tracks.core.fit.decode.CivilDate(2026, 11, 2)
        val flat = plans.plan(goal, plans.strategy(goal), false, today)
        assertEquals(40000.0, flat.legs.single { it.leg == "bike" }.distanceM)

        assertTrue(plans.importCourse(goal, climbGpx(), leg = "bike"))
        val s = plans.strategy(goal)
        assertEquals(setOf("bike"), s.triLegFiles.keys)
        val hilly = plans.plan(goal, s, false, today).legs.single { it.leg == "bike" }
        assertTrue(hilly.distanceM < 3000, "the leg is raced at its file's length")

        plans.removeCourse(goal, leg = "bike")
        assertTrue(plans.strategy(goal).triLegFiles.isEmpty())
    }
}
