// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyRandom
import com.tracks.core.parse.Sha512
import com.tracks.core.spec.SpecFixtures
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays `spec/fixtures/strength_plan.json` — the server's own strength and
 * mobility planners run over synthetic libraries — and requires the port to
 * agree exactly: the same lifts on the same days, the same weights to the
 * gram, the same stretches in the same order.
 *
 * Exactness is the point rather than a nicety. A plan is regenerated on
 * whichever device the user happens to be holding, and a phone that put a
 * different lift on Tuesday than the server did would make two replicas of one
 * account disagree about what the athlete is supposed to do.
 *
 * Values are compared with their Python types intact (see [Dict]): `6` and
 * `6.0` are different, because the server's JSON writes them differently.
 */
class StrengthPlanFixtureTest {
    private val corpus = SpecFixtures.load("strength_plan")

    @Suppress("UNCHECKED_CAST")
    private fun d(e: JsonElement?): Any? = e?.let { dyn(it) }
    private fun obj(e: JsonElement?): Map<String, Any?> = d(e) as Map<String, Any?>
    private fun list(key: String, o: JsonObject = corpus): List<JsonObject> = o[key]!!.jsonArray.map { it.jsonObject }

    /** The port's output in the corpus's vocabulary: dates as ISO, ints as Long. */
    private fun plain(v: Any?): Any? = when (v) {
        is CivilDate -> v.isoformat()
        is Int -> v.toLong()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to plain(x) }
        is List<*> -> v.map(::plain)
        is Set<*> -> v.map(::plain)
        else -> v
    }

    @Suppress("UNCHECKED_CAST")
    private val library: LinkedHashMap<String, Dict> by lazy {
        (d(corpus["library"]) as List<Dict>).associateByTo(LinkedHashMap()) { it["name"] as String }
    }
    private fun libraryFor(custom: Boolean): Map<String, Dict> =
        if (!custom) library else LinkedHashMap(library).apply {
            val c = obj(corpus["custom_exercise"]); put(c["name"] as String, c)
        }

    @Suppress("UNCHECKED_CAST")
    private val stretches: List<StretchCandidate> by lazy {
        (d(corpus["stretches"]) as List<Dict>).map {
            StretchCandidate(
                name = it["name"] as String,
                primaryMuscles = strList(it["primary_muscles"]),
                movementPattern = it["movement_pattern"] as String,
                difficulty = num(it["difficulty"]).toInt(),
                durationPerSideSec = num(it["duration_per_side_sec"]).toInt(),
                sets = num(it["sets"]).toInt(),
                eachSide = it["each_side"] as Boolean,
                description = it["description"] as String,
                garminCategory = it["garmin_category"] as String?,
                garminSubtype = it["garmin_subtype"] as Long?,
                isCustom = it["is_custom"] as Boolean,
                preferred = it["preferred"] as Boolean,
                secondaryMuscles = strList(it["secondary_muscles"]),
                equipment = strList(it["equipment"]),
                cues = strList(it["cues"]),
                breathCue = it["breath_cue"] as String?,
                position = it["position"] as String?,
            )
        }
    }
    private fun stretch(name: String) = stretches.first { it.name == name }

    @Suppress("UNCHECKED_CAST")
    private fun injuries(set: String): List<ActiveInjury> =
        (obj(corpus["injury_sets"])[set] as List<List<Any?>>).map { ActiveInjury(it[0] as String?, num(it[1]).toInt()) }

    private fun date(v: Any?): CivilDate? = (v as String?)?.split("-")?.let { CivilDate(it[0].toInt(), it[1].toInt(), it[2].toInt()) }

    @Test
    fun sha512_matches_the_fips_vector() {
        // FIPS 180-4's "abc" example; PyRandom's string seeding rests on it.
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Sha512.digest("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
    }

    @Test
    fun a_shuffle_leaves_a_list_in_the_order_cpython_does() {
        for (c in list("random")) {
            val xs = (0L until num(d(c["n"])).toLong()).toMutableList()
            PyRandom(d(c["seed"]) as String).shuffle(xs)
            assertEquals(d(c["expect"]), xs, "seed=${d(c["seed"])} n=${d(c["n"])}")
        }
    }

    @Test
    fun load_math_matches() {
        val loads = corpus["loads"]!!.jsonObject
        for (c in list("estimate_1rm", loads)) {
            val got = Loads.estimate1rm(num(d(c["weight"])), num(d(c["reps"])).toInt())
            // Python returns the input unchanged for one rep, int or float; compare as numbers.
            assertEquals(d(c["expect"])?.let(::num), got, "estimate_1rm ${c}")
        }
        for (c in list("round_weight", loads)) {
            @Suppress("UNCHECKED_CAST")
            val got = Loads.roundWeightForEquipment(num(d(c["weight"])), d(c["equipment"]) as List<String>?, d(c["units"]) as String?)
            assertEquals(d(c["expect"]), got, "round_weight $c")
        }
    }

    @Test
    fun archetypes_load_and_rotate_like_the_server() {
        val a = corpus["archetypes"]!!.jsonObject
        assertEquals(d(a["strength_keys"]), Archetypes.STRENGTH.map { it.key })
        assertEquals(d(a["flow_keys"]), Archetypes.FLOWS.map { it.key })
        for (row in a["select"]!!.jsonArray) {
            val r = d(row) as List<*>
            val got = Archetypes.select(r[0] as String, r[1] as String, num(r[2]).toInt(), r[3] as String, num(r[4]).toInt())
            assertEquals(r[5], got?.key, "select $r")
        }
        for (row in a["select_flow"]!!.jsonArray) {
            val r = d(row) as List<*>
            val got = Archetypes.selectFlow(r[0] as String, r[1] as String, r[2] as String?, num(r[3]).toInt())
            assertEquals(r[4], got?.key, "select_flow $r")
        }
    }

    @Test
    fun experience_suggestions_match() {
        for (c in list("leveling")) {
            val s = obj(c["signals"])
            val got = Leveling.inferSuggestion(
                d(c["current"]) as String?,
                sessions12wk = (s["sessions_12wk"] as Long?) ?: 0,
                e1rmTrend = (s["e1rm_trend"] as String?) ?: "flat",
                weeksSinceLast = (s["weeks_since_last"] as Long?) ?: 0,
            )
            val want = d(c["expect"]) as Map<*, *>?
            assertEquals(want?.let { ExperienceSuggestion(it["suggested"] as String, it["reason"] as String) }, got, "$c")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun stretch_selection_matches() {
        val f = corpus["flexibility"]!!.jsonObject
        for (c in list("sport_targets", f)) {
            assertEquals(d(c["expect"]), Flexibility.sportTargetMuscles(d(c["sport"]) as String?, d(c["fallback"]) as List<String>?), "$c")
        }
        for (c in list("strength_targets", f)) {
            assertEquals(d(c["expect"]), Flexibility.strengthTargetMuscles(strList(d(c["trained"]))), "$c")
        }
        for (c in list("mobility_targets", f)) {
            assertEquals(d(c["expect"]), Mobility.targetMuscles(d(c["sport"]) as String), "$c")
        }
        for (c in list("select", f)) {
            val v = obj(c)
            val picked = Flexibility.selectStretchFlow(
                stretches, strList(v["targets"]),
                count = num(v["count"]).toInt(),
                seed = v["seed"] as String,
                patternRank = (v["pattern_rank"] as Map<String, Any?>?)?.mapValues { num(it.value).toInt() },
                excludePatterns = (v["exclude_patterns"] as List<String>?)?.toSet() ?: Flexibility.EXCLUDE_POSTWORKOUT_PATTERNS,
                equipmentFree = v["equipment_free"] as Boolean? ?: false,
                minCount = (v["min_count"] as Long?)?.toInt() ?: 3,
                orderByPosition = v["order_by_position"] as Boolean? ?: false,
                closerMuscles = v["closer_muscles"] as List<String>?,
            )
            assertEquals(v["expect"], picked.map { it.name }, "select ${v["seed"]}")
            assertEquals(v["steps"], plain(picked.map(Flexibility::toStep)), "steps ${v["seed"]}")
        }
        for (c in list("order", f)) {
            val v = obj(c)
            val got = Flexibility.orderFlowByPosition(strList(v["selected"]).map(::stretch), v["closer"] as List<String>?)
            assertEquals(v["expect"], got.map { it.name }, "order $v")
        }
    }

    @Test
    fun each_session_picks_the_same_lifts_for_the_same_slots() {
        for (c in list("selection")) {
            val v = obj(c)
            val got = Exercises.select(
                sportFamily = v["sport"] as String,
                tier = num(v["tier"]).toInt(),
                splitType = v["split"] as String,
                equipment = strList(v["equipment"]),
                library = libraryFor(v["custom"] as Boolean),
                injuries = injuries(v["injuries"] as String),
                maxExercises = num(v["max_exercises"]).toInt(),
                preferred = strList(v["preferred"]).toSet(),
                excluded = strList(v["excluded"]).toSet(),
                weekNum = num(v["week"]).toInt(),
                regenSalt = v["salt"] as String,
                confirmedNo = strList(v["confirmed_no"]).toSet(),
                usedThisWeek = strList(v["used"]).toSet(),
                maxDifficulty = (v["max_difficulty"] as Long?)?.toInt(),
                blockNum = num(v["week"]).toInt().floorDiv(4),
            )
            assertEquals(v["expect"], got.map { listOf(it.first, it.second) }, "selection ${v["sport"]} ${v["split"]}")
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun whole_plans_match_the_server_workout_for_workout() {
        val g = corpus["generator"]!!.jsonObject
        val records = obj(g["records"]) as Map<String, Dict>
        for (c in list("cases", g)) {
            val v = obj(c)
            val goal = v["goal"] as Dict
            val got = StrengthPlan.generate(
                goal = StrengthGoal(
                    strengthTier = num(goal["strength_tier"]).toInt(),
                    strengthDaysPerWeek = (goal["strength_days_per_week"] as Long?)?.toInt(),
                    eventDate = date(goal["event_date"]),
                ),
                sportFamily = v["sport"] as String,
                today = date(v["today"])!!,
                existingWorkouts = (v["existing"] as List<Dict>).map { ExistingWorkout(date(it["scheduled_date"]), it["workout_type"] as String?) },
                equipment = strList(v["equipment"]),
                library = libraryFor(v["custom"] as Boolean),
                strengthRecords = if (v["records"] as Boolean) records else emptyMap(),
                injuries = injuries(v["injuries"] as String),
                sessionsCount = num(v["sessions"]).toInt(),
                units = v["units"] as String,
                preferred = strList(v["preferred"]).toSet().ifEmpty { null },
                excluded = strList(v["excluded"]).toSet().ifEmpty { null },
                sessionMaxMinutes = (v["max_minutes"] as Long?)?.toInt(),
                regenSalt = v["salt"] as String,
                confirmedNo = strList(v["confirmed_no"]).toSet().ifEmpty { null },
                stretchCandidates = if (v["no_stretches"] as Boolean) null else stretches,
                experience = v["experience"] as String?,
                anchorDate = date(v["anchor"]),
            )
            val want = v["expect"] as List<*>
            val have = plain(got) as List<*>
            assertEquals(want.size, have.size, "${v["name"]}: workout count")
            want.zip(have).forEachIndexed { i, (w, h) -> assertEquals(w, h, "${v["name"]} workout $i") }
        }
    }
}
