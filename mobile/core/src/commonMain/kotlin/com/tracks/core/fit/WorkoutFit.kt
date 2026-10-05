// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.api.WorkoutStep
import com.tracks.core.parse.PyMath
import kotlin.math.floor

/**
 * A workout FIT file, built on the phone with no server to ask.
 *
 * A port of `app/calculators/fit_workout.py`'s two workout encoders, checked
 * against that module's own output byte for byte — see `WorkoutFitTest`. The
 * bar is deliberately that high: nothing about a rejected FIT file says why it
 * was rejected, so "the same bytes the watch already accepts" is the only
 * cheap statement of correctness available.
 *
 * ## The identity profile
 *
 * Every file Garmin Connect emits shares a set of invariants, and a file that
 * breaks one is filtered out of the calendar and the today's-workout widget
 * silently rather than rejected loudly:
 *
 * - `file_id` says manufacturer GARMIN with product 65534, Connect's own
 *   sentinel, and a non-zero serial.
 * - `workout` carries `capabilities = 32` (the TCX leniency flag), a real
 *   `sub_sport`, and a step count.
 * - **every** step carries `weight_display_unit = 2` (pound) and
 *   `secondary_target_value = 0`, whether or not it is a strength step and
 *   whatever the user's units are.
 *
 * ## Three shapes, not one
 *
 * Endurance steps, strength sets and yoga holds are emitted differently, and
 * the differences are empirical rather than principled:
 *
 * - Strength repeats a set with a `repeat_until_steps_cmplt` loop, rest
 *   included; yoga loops a single per-side hold with no rest. Yoga used to
 *   duplicate the step instead, on a note that the Yoga app skips loop steps
 *   in its pose UI — the loop is unverified on a watch (see [emitYogaSteps]).
 * - A yoga step **omits `target_type` entirely**. Sending `open` there keeps
 *   the file valid and stops the pose animation playing.
 * - Yoga files declare a newer creator version and five extra `workout`
 *   fields that no public profile describes; without them the animation
 *   engine does not fire.
 */
object WorkoutFit {

    // ── Identity ─────────────────────────────────────────────────────────────

    private const val MANUFACTURER_GARMIN = 1
    private const val PRODUCT_CONNECT = 65534
    private const val FILE_TYPE_WORKOUT = 5
    private const val CAPABILITIES_TCX = 32L

    /** Matches the creator version on the reference set that is known to work. */
    private const val CREATOR_VERSION_LEGACY = 2609

    /** Newer Connect. Yoga needs it; strength is left on the proven one. */
    private const val CREATOR_VERSION_MODERN = 2610

    private const val PROTOCOL_VERSION = 0x20
    private const val PROFILE_VERSION = 21208

    private const val FIT_EPOCH_OFFSET = 631_065_600L

    /** Connect emits pound on every step regardless of locale. So do we. */
    private const val WEIGHT_UNIT_POUND = 2

    private const val MESSAGE_FILE_ID = 0
    private const val MESSAGE_FILE_CREATOR = 49
    private const val MESSAGE_WORKOUT = 26
    private const val MESSAGE_WORKOUT_STEP = 27
    private const val MESSAGE_EXERCISE_TITLE = 264

    // Enum values, every one of them read off reference output rather than
    // guessed from the FIT specification — `hiit` in particular is 70, which
    // is not where a reading of the enum order would put it.
    private const val INTENSITY_ACTIVE = 0
    private const val INTENSITY_REST = 1
    private const val INTENSITY_WARMUP = 2
    private const val INTENSITY_COOLDOWN = 3

    private const val DURATION_TIME = 0
    private const val DURATION_DISTANCE = 1
    private const val DURATION_OPEN = 5
    private const val DURATION_REPEAT_UNTIL_STEPS = 6
    private const val DURATION_REPS = 29

    private const val TARGET_SPEED = 0
    private const val TARGET_HEART_RATE = 1
    private const val TARGET_OPEN = 2
    private const val TARGET_CADENCE = 3
    private const val TARGET_POWER = 4
    private const val TARGET_SWIM_STROKE = 11

    /** Field sizes for the two strings a workout carries, in bytes. */
    private const val NAME_BYTES = 50
    private const val DESCRIPTION_BYTES = 250

    // ── Sport routing ────────────────────────────────────────────────────────

    private data class Routing(val sport: Int, val subSport: Int)

    private val ENDURANCE_ROUTING = mapOf(
        "running" to Routing(1, 0),
        "cycling" to Routing(2, 0),
        "road_biking" to Routing(2, 7),
        "gravel_cycling" to Routing(2, 46),
        "mountain_biking" to Routing(2, 8),
        "trail_biking" to Routing(2, 8),
        "swimming" to Routing(5, 17),
        "lap_swimming" to Routing(5, 17),
        "pool_swimming" to Routing(5, 17),
        "open_water_swimming" to Routing(5, 18),
        "hiking" to Routing(17, 0),
        "walking" to Routing(11, 0),
        // fit_workout.py explains each of these: an erg-first rowing plan
        // opens Indoor Row, ski touring is alpine/backcountry, and an alpine
        // plan is dry-land work for the Cardio app.
        "rowing" to Routing(15, 14),
        "indoor_rowing" to Routing(15, 14),
        "skiing" to Routing(12, 0),
        "cross_country_skiing" to Routing(12, 0),
        "nordic_skiing" to Routing(12, 0),
        "classic_skiing" to Routing(12, 0),
        "roller_skiing" to Routing(12, 0),
        "skate_skiing" to Routing(12, 42),
        "backcountry_skiing" to Routing(13, 37),
        "ski_mountaineering" to Routing(13, 37),
        "skimo" to Routing(13, 37),
        "alpine_skiing" to Routing(10, 26),
        "downhill_skiing" to Routing(10, 26),
        "resort_skiing" to Routing(10, 26),
        "snowboarding" to Routing(10, 26),
        "climbing" to Routing(31, 68),
        "rock_climbing" to Routing(31, 68),
        "indoor_climbing" to Routing(31, 68),
        "sport_climbing" to Routing(31, 68),
        "bouldering" to Routing(31, 69),
    )

    private val DEFAULT_ENDURANCE_ROUTING = ENDURANCE_ROUTING.getValue("running")

    /**
     * Where a non-endurance workout goes.
     *
     * Flexibility and mobility route to **yoga**, not to the Training app's
     * `flexibility_training`: on this firmware only the yoga routing plays
     * stretch animations, and the other is accepted with no animation pack at
     * all.
     */
    private val TYPE_ROUTING = mapOf(
        "strength" to Routing(10, 20),
        "mobility" to Routing(10, 43),
        "flexibility" to Routing(10, 43),
        "yoga" to Routing(10, 43),
        "pilates" to Routing(10, 44),
        "hiit" to Routing(10, 70),
        "cardio" to Routing(10, 26),
    )

    private val DEFAULT_TYPE_ROUTING = TYPE_ROUTING.getValue("strength")

    /** The types that get the yoga treatment: modern file format, linear holds. */
    private val YOGA_ROUTED = setOf("flexibility", "mobility", "yoga", "pilates")

    // ── Coaching zones ───────────────────────────────────────────────────────

    /** Intensity tag to a fraction of threshold heart rate. Null: no HR target. */
    private val HR_ZONE = mapOf(
        "recovery" to (0.50 to 0.81),
        "easy" to (0.65 to 0.81),
        "endurance" to (0.81 to 0.88),
        "tempo" to (0.89 to 0.93),
        "sweet_spot" to (0.94 to 0.99),
        "threshold" to (1.00 to 1.02),
        "vo2" to (1.03 to 1.06),
        "race_pace" to (0.92 to 1.04),
        "skills" to (0.65 to 0.85),
        "descent_repeats" to (0.85 to 1.00),
    )

    /** Intensity tag to a fraction of FTP. Null: no power target. */
    private val POWER_ZONE = mapOf(
        "recovery" to (0.40 to 0.55),
        "easy" to (0.50 to 0.65),
        "endurance" to (0.65 to 0.75),
        "tempo" to (0.76 to 0.88),
        "sweet_spot" to (0.88 to 0.93),
        "threshold" to (0.95 to 1.00),
        "vo2" to (1.05 to 1.20),
        "over_under" to (0.88 to 1.08),
        "matchbook" to (1.20 to 1.50),
        "anaerobic" to (1.20 to 1.50),
        "neuromuscular" to (1.50 to 3.00),
        "micro_bursts" to (1.05 to 1.30),
        "race_pace" to (0.85 to 1.00),
    )

    /**
     * `_ENDURANCE_HR_PCT`: heart-rate bands for rowing, hiking and skiing.
     * Short hard efforts are absent on purpose — see fit_workout.py.
     */
    private val ENDURANCE_HR = mapOf(
        "easy" to (0.65 to 0.81),
        "endurance" to (0.75 to 0.85),
        "ut2" to (0.75 to 0.85),
        "ut1" to (0.85 to 0.90),
        "threshold" to (0.94 to 1.00),
    )

    private val SWIM_STROKE = mapOf(
        "freestyle" to 0, "backstroke" to 1, "breaststroke" to 2, "butterfly" to 3,
        "drill" to 4, "mixed" to 5, "im" to 6,
    )
    private val SWIM_EQUIPMENT = mapOf(
        "swim_fins" to 1, "swim_kickboard" to 2, "swim_paddles" to 3, "swim_pull_buoy" to 4, "swim_snorkel" to 5,
    )

    private val PACE_STEP_NAMES = mapOf(
        "recovery" to "Recovery",
        "easy" to "Easy Run",
        "marathon" to "Marathon",
        "threshold" to "Threshold",
        "interval" to "Interval",
        "repetition" to "Rep Pace",
    )

    /**
     * Pace zones in seconds per kilometre, as a plan derives them from VDOT.
     *
     * A map rather than a type because the server sends whatever zones it
     * knows about and a zone this does not recognise simply gets no target —
     * the same outcome as coaching being off for that step.
     */
    private fun paceTargets(zone: String, paces: Map<String, Double>, margin: Int = 10): Pair<Int, Int> {
        val pace = paces[zone] ?: return 0 to 0
        val low = (1_000_000.0 / (pace + margin)).toInt()
        val high = (1_000_000.0 / maxOf(1.0, pace - margin)).toInt()
        return low to high
    }

    private class Targets(
        val speedLow: Int = 0, val speedHigh: Int = 0,
        val hrLow: Int = 0, val hrHigh: Int = 0,
        val powerLow: Int = 0, val powerHigh: Int = 0,
        val cadenceLow: Int = 0, val cadenceHigh: Int = 0,
        val swimStroke: Int? = null,
        val equipment: Int? = null,
    ) {
        /** These targets plus a step's stroke rate, stroke and equipment (`_step_extras`). */
        fun with(extras: Targets) = Targets(
            speedLow, speedHigh, hrLow, hrHigh, powerLow, powerHigh,
            extras.cadenceLow, extras.cadenceHigh, extras.swimStroke, extras.equipment,
        )
    }

    private val NO_TARGETS = Targets()

    private fun cyclingTargets(intensity: String, lthr: Int?, ftp: Int?): Targets {
        var hrLow = 0
        var hrHigh = 0
        var powerLow = 0
        var powerHigh = 0
        val hr = HR_ZONE[intensity]
        if (hr != null && lthr != null && lthr > 0) {
            hrLow = (lthr * hr.first).toInt()
            hrHigh = (lthr * hr.second).toInt()
        }
        val power = POWER_ZONE[intensity]
        if (power != null && ftp != null && ftp > 0) {
            powerLow = (ftp * power.first).toInt()
            powerHigh = (ftp * power.second).toInt()
        }
        return Targets(hrLow = hrLow, hrHigh = hrHigh, powerLow = powerLow, powerHigh = powerHigh)
    }

    private fun sportFamily(sport: String): String = when (sport) {
        "mountain_biking", "trail_biking" -> "mountain_biking"
        "running" -> "running"
        "cycling", "road_biking", "gravel_cycling" -> "cycling"
        "swimming", "lap_swimming", "pool_swimming", "open_water_swimming" -> "swimming"
        "rowing", "indoor_rowing" -> "rowing"
        "hiking", "walking" -> "hiking"
        "skiing", "cross_country_skiing", "nordic_skiing", "classic_skiing", "roller_skiing", "skate_skiing",
        "backcountry_skiing", "ski_mountaineering", "skimo",
        "alpine_skiing", "downhill_skiing", "resort_skiing", "snowboarding" -> "skiing"
        else -> "generic"
    }

    /** `_step_extras`: stroke rate (coaching only), swim stroke and equipment (always). */
    private fun stepExtras(step: WorkoutStep, family: String, paceCoaching: Boolean): Targets {
        var cLow = 0
        var cHigh = 0
        if (paceCoaching && family == "rowing" && (step.spmLow ?: 0) != 0 && (step.spmHigh ?: 0) != 0) {
            cLow = step.spmLow!!
            cHigh = step.spmHigh!!
        }
        var stroke: Int? = null
        var equipment: Int? = null
        if (family == "swimming") {
            stroke = SWIM_STROKE[step.stroke.orEmpty()]
            equipment = SWIM_EQUIPMENT[step.equipment.orEmpty()]
        }
        return Targets(cadenceLow = cLow, cadenceHigh = cHigh, swimStroke = stroke, equipment = equipment)
    }

    private fun enduranceTargets(
        step: WorkoutStep,
        family: String,
        paces: Map<String, Double>?,
        lthr: Int?,
        ftp: Int?,
        paceCoaching: Boolean,
    ): Targets {
        if (!paceCoaching) return NO_TARGETS
        val zone = step.pace.orEmpty()
        val intensity = step.intensity.orEmpty()
        if (family == "running" && !paces.isNullOrEmpty() && zone.isNotEmpty()) {
            val (low, high) = paceTargets(zone, paces)
            return Targets(speedLow = low, speedHigh = high)
        }
        if ((family == "mountain_biking" || family == "cycling") && intensity.isNotEmpty()) {
            return cyclingTargets(intensity, lthr, ftp)
        }
        if ((family == "rowing" || family == "hiking" || family == "skiing") && intensity.isNotEmpty() &&
            lthr != null && lthr > 0
        ) {
            // A rowing piece above UT1 steers by stroke rate alone.
            if (family == "rowing" && (step.spmLow ?: 0) != 0 && intensity !in setOf("ut2", "ut1", "easy")) {
                return NO_TARGETS
            }
            val pct = ENDURANCE_HR[intensity] ?: return NO_TARGETS
            return Targets(hrLow = (lthr * pct.first).toInt(), hrHigh = (lthr * pct.second).toInt())
        }
        return NO_TARGETS
    }

    // ── Step construction ────────────────────────────────────────────────────

    /**
     * One emitted step, kept as its own fields plus the two numbers the
     * workout message needs to summarise them.
     *
     * Field order within a step is the reference encoder's, which is the order
     * its own builders happen to set them in. It carries no meaning to a
     * reader and is reproduced only so the output can be compared byte for
     * byte.
     */
    private class Step(
        val fields: List<FitField>,
        val durationType: Int,
        val durationValue: Long,
        /** How many times a repeat step runs its block; 0 on any other step. */
        val repeatCount: Long = 0,
    )

    private fun buildStep(
        index: Int,
        name: String?,
        intensity: Int,
        durationType: Int,
        durationValue: Long,
        targets: Targets = NO_TARGETS,
        exerciseCategory: Int? = null,
        exerciseName: Int? = null,
        exerciseWeightKg: Double? = null,
    ): Step {
        val fields = mutableListOf<FitField>()
        fields += fitUint16(254, index)
        fields += fitEnum(7, intensity)
        fields += fitEnum(1, durationType)
        fields += fitUint32(2, durationValue)
        fields += fitUint32(20, 0)
        fields += fitUint16(13, WEIGHT_UNIT_POUND)
        if (name != null) fields += fitString(0, name, NAME_BYTES)

        // Priority: power, then heart rate, then speed, then no target at all.
        // A step never carries two kinds — the watch shows one.
        if (targets.powerLow > 0 && targets.powerHigh > 0) {
            fields += fitEnum(3, TARGET_POWER)
            fields += fitUint32(4, 0)
            // Watts are offset by 1000; 1-1000 in this field means a percentage
            // of FTP instead. Heart rate has the same trick at 100.
            fields += fitUint32(5, targets.powerLow + 1000L)
            fields += fitUint32(6, targets.powerHigh + 1000L)
        } else if (targets.hrLow > 0 && targets.hrHigh > 0) {
            fields += fitEnum(3, TARGET_HEART_RATE)
            fields += fitUint32(4, 0)
            fields += fitUint32(5, targets.hrLow + 100L)
            fields += fitUint32(6, targets.hrHigh + 100L)
        } else if (targets.speedLow > 0 && targets.speedHigh > 0) {
            fields += fitEnum(3, TARGET_SPEED)
            fields += fitUint32(4, 0)
            fields += fitUint32(5, targets.speedLow.toLong())
            fields += fitUint32(6, targets.speedHigh.toLong())
        } else if (targets.cadenceLow > 0 && targets.cadenceHigh > 0) {
            // Strokes per minute on the erg, carried without an offset.
            fields += fitEnum(3, TARGET_CADENCE)
            fields += fitUint32(4, 0)
            fields += fitUint32(5, targets.cadenceLow.toLong())
            fields += fitUint32(6, targets.cadenceHigh.toLong())
        } else if (targets.swimStroke != null) {
            fields += fitEnum(3, TARGET_SWIM_STROKE)
            fields += fitUint32(4, targets.swimStroke.toLong())
        } else {
            fields += fitEnum(3, TARGET_OPEN)
            fields += fitUint32(4, 0)
        }

        if (exerciseCategory != null) fields += fitUint16(10, exerciseCategory)
        if (exerciseName != null) fields += fitUint16(11, exerciseName)
        if (exerciseWeightKg != null && exerciseWeightKg > 0) {
            // scale 100: the field is hundredths of a kilogram.
            fields += fitUint16(12, (exerciseWeightKg * 100).toInt())
        }
        if (targets.equipment != null) fields += fitEnum(9, targets.equipment)
        return Step(fields, durationType, durationValue)
    }

    /** Loop back to [fromIndex] and run the block [reps] times in total. */
    private fun buildRepeatStep(index: Int, fromIndex: Int, reps: Int): Step {
        val fields = listOf(
            fitUint16(254, index),
            fitEnum(7, INTENSITY_ACTIVE),
            fitEnum(1, DURATION_REPEAT_UNTIL_STEPS),
            fitUint32(2, fromIndex.toLong()),
            fitEnum(3, TARGET_OPEN),
            fitUint32(4, reps.toLong()),
            fitUint32(20, 0),
        )
        return Step(fields, DURATION_REPEAT_UNTIL_STEPS, fromIndex.toLong(), reps.toLong())
    }

    /**
     * A pose hold.
     *
     * Deliberately not [buildStep]: this omits `target_type` altogether, which
     * is what the Yoga app's animation engine wants. An `open` target here
     * leaves the file valid and the animation silent.
     */
    private fun buildYogaStep(index: Int, durationMillis: Long, category: Int, exercise: Int): Step {
        val fields = listOf(
            fitUint16(254, index),
            fitEnum(7, INTENSITY_ACTIVE),
            fitEnum(1, DURATION_TIME),
            fitUint32(2, durationMillis),
            fitUint32(4, 0),
            fitUint32(20, 0),
            fitUint16(10, category),
            fitUint16(11, exercise),
            fitUint16(13, WEIGHT_UNIT_POUND),
        )
        return Step(fields, DURATION_TIME, durationMillis)
    }

    private fun millisForMinutes(minutes: Double): Long = (minutes * 60_000).toLong()

    private fun millisForSeconds(seconds: Double): Long = (seconds * 1000).toLong()

    /** Title Case, as Python's `str.title()` produces it for these labels. */
    private fun titleCase(value: String): String =
        value.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }

    private fun labelFor(intensity: String): String = titleCase(intensity.replace('_', ' '))

    // ── Endurance steps ──────────────────────────────────────────────────────

    private fun emitEnduranceSteps(
        planSteps: List<WorkoutStep>,
        family: String,
        paces: Map<String, Double>?,
        lthr: Int?,
        ftp: Int?,
        paceCoaching: Boolean,
    ): List<Step> {
        // A `walk` is a warm-up, a rest, or a cool-down depending only on where
        // it falls in the list — the generator writes the same step type for
        // all three.
        val walkPositions = planSteps.indices.filter { planSteps[it].type == "walk" }
        val firstWalk = walkPositions.firstOrNull() ?: -1
        val lastWalk = walkPositions.lastOrNull() ?: -1

        val out = mutableListOf<Step>()

        for ((position, step) in planSteps.withIndex()) {
            val targets = enduranceTargets(step, family, paces, lthr, ftp, paceCoaching)
                .with(stepExtras(step, family, paceCoaching))
            val label = step.label?.takeIf { it.isNotEmpty() }
            when (step.type) {
                "walk" -> {
                    val minutes = step.durationMin ?: 5.0
                    val (name, intensity) = when (position) {
                        firstWalk -> "Warm Up" to INTENSITY_WARMUP
                        lastWalk -> "Cool Down" to INTENSITY_COOLDOWN
                        else -> "Rest" to INTENSITY_REST
                    }
                    out += buildStep(out.size, name, intensity, DURATION_TIME, millisForMinutes(minutes))
                }

                "run" -> {
                    val name = PACE_STEP_NAMES[step.pace.orEmpty()] ?: "Run"
                    out += buildStep(
                        out.size, name, INTENSITY_ACTIVE, DURATION_TIME,
                        millisForMinutes(step.durationMin ?: 30.0), targets,
                    )
                }

                "fartlek" -> {
                    val hardMinutes = step.hardMin ?: 3.0
                    val easyMinutes = step.easyMin ?: 2.0
                    val reps = maxOf(1, step.reps ?: 1)
                    val coached = paceCoaching && !paces.isNullOrEmpty()
                    val hard = if (coached) paceTargets(step.pace ?: "threshold", paces!!) else 0 to 0
                    val easy = if (coached) paceTargets("easy", paces!!) else 0 to 0
                    val loopStart = out.size
                    out += buildStep(
                        out.size, "Hard", INTENSITY_ACTIVE, DURATION_TIME,
                        millisForMinutes(hardMinutes),
                        Targets(speedLow = hard.first, speedHigh = hard.second),
                    )
                    out += buildStep(
                        out.size, "Easy", INTENSITY_ACTIVE, DURATION_TIME,
                        millisForMinutes(easyMinutes),
                        Targets(speedLow = easy.first, speedHigh = easy.second),
                    )
                    if (reps > 1) out += buildRepeatStep(out.size, loopStart, reps)
                }

                // A pool warm-up is a distance, as the watch counts lengths.
                "warmup" -> out += if (step.distanceM != null && step.distanceM != 0.0) buildStep(
                    out.size, "Warm Up", INTENSITY_WARMUP, DURATION_DISTANCE,
                    step.distanceM.toInt() * 100L, targets,
                ) else buildStep(
                    out.size, "Warm Up", INTENSITY_WARMUP, DURATION_TIME,
                    millisForMinutes(step.durationMin ?: 15.0), targets,
                )

                "cooldown" -> out += if (step.distanceM != null && step.distanceM != 0.0) buildStep(
                    out.size, "Cool Down", INTENSITY_COOLDOWN, DURATION_DISTANCE,
                    step.distanceM.toInt() * 100L, targets,
                ) else buildStep(
                    out.size, "Cool Down", INTENSITY_COOLDOWN, DURATION_TIME,
                    millisForMinutes(step.durationMin ?: 10.0), targets,
                )

                "interval_set" -> {
                    val reps = maxOf(1, step.reps ?: 1)
                    val restSeconds = step.restSec ?: ((step.restMin ?: 0.0) * 60).toInt()
                    val loopStart = out.size
                    val distance = step.distanceM
                    val minutesEach = step.durationMinEach
                    val secondsEach = step.durationSecEach
                    val iname = label ?: "Interval"
                    out += when {
                        // FIT distance is centimetres, and the generator's
                        // metres are truncated to whole ones first.
                        distance != null && distance != 0.0 -> buildStep(
                            out.size, iname, INTENSITY_ACTIVE, DURATION_DISTANCE,
                            distance.toInt() * 100L, targets,
                        )
                        minutesEach != null && minutesEach != 0.0 -> buildStep(
                            out.size, iname, INTENSITY_ACTIVE, DURATION_TIME,
                            millisForMinutes(minutesEach.toInt().toDouble()), targets,
                        )
                        secondsEach != null && secondsEach != 0.0 -> buildStep(
                            out.size, iname, INTENSITY_ACTIVE, DURATION_TIME,
                            millisForSeconds(secondsEach.toInt().toDouble()), targets,
                        )
                        else -> buildStep(
                            out.size, iname, INTENSITY_ACTIVE, DURATION_TIME,
                            millisForMinutes(5.0), targets,
                        )
                    }
                    if (reps > 1) {
                        if (restSeconds > 0) {
                            out += buildStep(
                                out.size, "Rest", INTENSITY_REST, DURATION_TIME,
                                millisForSeconds(restSeconds.toDouble()),
                            )
                        }
                        out += buildRepeatStep(out.size, loopStart, reps)
                    }
                }

                "effort_set" -> {
                    val reps = maxOf(1, step.reps ?: 1)
                    // Minutes first and seconds only if that came to zero —
                    // the generator's own precedence, which is the opposite way
                    // round from interval_set's.
                    val restSeconds = ((step.restMin ?: 0.0) * 60).toInt()
                        .takeIf { it != 0 } ?: (step.restSec ?: 0)
                    val intensity = step.intensity.orEmpty()
                    val effortName = label ?: if (intensity.isNotEmpty()) labelFor(intensity) else "Effort"
                    val minutesEach = step.durationMinEach
                    val secondsEach = step.durationSecEach
                    val duration = when {
                        minutesEach != null && minutesEach != 0.0 ->
                            millisForMinutes(minutesEach.toInt().toDouble())
                        secondsEach != null && secondsEach != 0.0 ->
                            millisForSeconds(secondsEach.toInt().toDouble())
                        else -> millisForMinutes(5.0)
                    }
                    val loopStart = out.size
                    out += buildStep(out.size, effortName, INTENSITY_ACTIVE, DURATION_TIME, duration, targets)
                    if (reps > 1) {
                        if (restSeconds > 0) {
                            out += buildStep(
                                out.size, "Rest", INTENSITY_REST, DURATION_TIME,
                                millisForSeconds(restSeconds.toDouble()),
                            )
                        }
                        out += buildRepeatStep(out.size, loopStart, reps)
                    }
                }

                "ride", "swim", "activity" -> {
                    val intensity = step.intensity.orEmpty()
                    val name = label ?: if (intensity.isNotEmpty()) labelFor(intensity) else titleCase(step.type)
                    out += buildStep(
                        out.size, name, INTENSITY_ACTIVE, DURATION_TIME,
                        millisForMinutes(step.durationMin ?: 30.0), targets,
                    )
                }

                // Anything else is a step the generator described in prose. It
                // is skipped rather than guessed at: a workout missing one step
                // still runs, and one with an invented step does not say what
                // the plan meant.
                else -> Unit
            }
        }
        return out
    }

    // ── Strength and yoga steps ──────────────────────────────────────────────

    private class EmittedSteps(
        val steps: List<Step>,
        /** Unique (category, exercise) pairs, in first-seen order, to their names. */
        val titles: Map<Long, String>,
    )

    private fun emitStrengthSteps(exercises: List<WorkoutStep>): EmittedSteps {
        val out = mutableListOf<Step>()
        val titles = LinkedHashMap<Long, String>()

        fun rest(seconds: Int) {
            if (seconds <= 0) return
            out += buildStep(out.size, null, INTENSITY_REST, DURATION_TIME, seconds * 1000L)
        }

        fun work(exercise: WorkoutStep) {
            val resolved = resolveExercise(exercise.garminCategory, exercise.garminSubtype, exercise.name)
            if (resolved.category != UNKNOWN_CATEGORY && resolved.exercise != UNKNOWN_EXERCISE) {
                titles.putIfAbsent(titleKey(resolved), resolved.displayName)
            }
            val weightKg = exercise.weightKg ?: 0.0
            out += buildStep(
                out.size, null, INTENSITY_ACTIVE, DURATION_REPS, maxOf(1, exercise.reps ?: 8).toLong(),
                exerciseCategory = resolved.category,
                exerciseName = resolved.exercise,
                exerciseWeightKg = weightKg.takeIf { it > 0 },
            )
        }

        for ((group, members) in WorkoutBlocks.runs(exercises)) {
            if (group != null) {
                // A circuit or superset: each member once per round, the
                // group's rest after the last, one loop around the lot. The
                // members' own sets do not apply — rounds are the sets.
                if (members.none { it.type == "strength_exercise" }) continue
                val loopStart = out.size
                for (m in members) {
                    when (m.type) {
                        "strength_exercise" -> work(m)
                        "rest" -> rest(m.durationSeconds ?: 0)
                    }
                }
                rest(group.restSeconds)
                val rounds = maxOf(1, group.rounds)
                if (rounds > 1) out += buildRepeatStep(out.size, loopStart, rounds)
                continue
            }
            val exercise = members.single()
            if (exercise.type == "rest") {
                rest(exercise.durationSeconds ?: 0)
                continue
            }
            if (exercise.type != "strength_exercise") continue
            val sets = maxOf(1, exercise.sets ?: 3)
            val reps = maxOf(1, exercise.reps ?: 8)
            val weightKg = exercise.weightKg ?: 0.0
            val restSeconds = exercise.restSeconds ?: 90
            val resolved = resolveExercise(
                exercise.garminCategory, exercise.garminSubtype, exercise.name,
            )
            if (resolved.category != UNKNOWN_CATEGORY && resolved.exercise != UNKNOWN_EXERCISE) {
                titles.putIfAbsent(titleKey(resolved), resolved.displayName)
            }

            val loopStart = out.size
            // The work step is unnamed: Connect leaves it so, and the watch
            // takes its label from the exercise_title block instead.
            out += buildStep(
                out.size, null, INTENSITY_ACTIVE, DURATION_REPS, reps.toLong(),
                exerciseCategory = resolved.category,
                exerciseName = resolved.exercise,
                exerciseWeightKg = weightKg.takeIf { it > 0 },
            )
            if (sets > 1) {
                out += buildStep(
                    out.size, null, INTENSITY_REST, DURATION_TIME,
                    restSeconds * 1000L,
                )
                out += buildRepeatStep(out.size, loopStart, sets)
            }
        }
        return EmittedSteps(out, titles)
    }

    /**
     * Pose holds for the Yoga app — `_emit_yoga_steps`.
     *
     * A hold is one side for its per-side time, written once and looped
     * sets × sides times, so the step change cues each switch. That loop is
     * unverified on a watch and goes against an earlier note that the Yoga
     * app skips loop steps; if it does, one side is held and the rest of the
     * stretch is missing. See the Python for the whole history.
     *
     * A group is one pass of its members and one loop around them. A
     * one-sided member is written once per side inside the pass, so no loop
     * sits inside another.
     */
    private fun emitYogaSteps(exercises: List<WorkoutStep>): EmittedSteps {
        val out = mutableListOf<Step>()
        val titles = LinkedHashMap<Long, String>()
        val ownNames = LinkedHashMap<String, Int>()

        // A rest is the strength encoder's rest step, not a pose hold: it
        // names no pose, so there is no animation to suppress. Not yet seen
        // on a watch's Yoga app.
        fun rest(seconds: Int) {
            if (seconds <= 0) return
            out += buildStep(out.size, null, INTENSITY_REST, DURATION_TIME, seconds * 1000L)
        }

        fun hold(exercise: WorkoutStep) {
            val resolved = resolveYogaExercise(
                exercise.garminCategory, exercise.garminSubtype, exercise.name, ownNames,
            )
            titles.putIfAbsent(titleKey(resolved), resolved.displayName)
            val seconds = exercise.durationSeconds ?: 30
            out += buildYogaStep(out.size, seconds * 1000L, resolved.category, resolved.exercise)
        }

        fun sides(exercise: WorkoutStep): Int = if (exercise.eachSide) 2 else 1

        fun loop(fromIndex: Int, reps: Int) {
            if (reps > 1) out += buildRepeatStep(out.size, fromIndex, reps)
        }

        for ((group, members) in WorkoutBlocks.runs(exercises)) {
            if (group != null) {
                if (members.none { it.type == "mobility_exercise" }) continue
                val loopStart = out.size
                for (m in members) {
                    when (m.type) {
                        "mobility_exercise" -> repeat(sides(m)) { hold(m) }
                        "rest" -> rest(m.durationSeconds ?: 0)
                    }
                }
                rest(group.restSeconds)
                loop(loopStart, maxOf(1, group.rounds))
                continue
            }
            val exercise = members.single()
            when (exercise.type) {
                "rest" -> rest(exercise.durationSeconds ?: 0)
                "mobility_exercise" -> {
                    val loopStart = out.size
                    hold(exercise)
                    loop(loopStart, maxOf(1, exercise.sets ?: 1) * sides(exercise))
                }
            }
        }
        return EmittedSteps(out, titles)
    }

    /**
     * How long the timed steps take, a looped block counted once per run —
     * `_timed_total_ms`. A repeat's share is its block's time once more for
     * every run past the first; an inner loop's share is already inside the
     * block an outer one counts.
     */
    private fun timedTotalMillis(steps: List<Step>): Long {
        val share = LongArray(steps.size)
        steps.forEachIndexed { i, step ->
            share[i] = when (step.durationType) {
                DURATION_TIME -> step.durationValue
                DURATION_REPEAT_UNTIL_STEPS -> {
                    var block = 0L
                    for (j in step.durationValue.toInt() until i) block += share[j]
                    block * (maxOf(1L, step.repeatCount) - 1)
                }
                else -> 0L
            }
        }
        return share.sum()
    }

    private fun titleKey(resolved: ResolvedExercise): Long =
        (resolved.category.toLong() shl 32) or resolved.exercise.toLong()

    // ── File assembly ────────────────────────────────────────────────────────

    private fun writeFileId(writer: FitWriter, workoutId: Int, timeCreatedMillis: Long) {
        writer.write(
            MESSAGE_FILE_ID,
            listOf(
                fitEnum(0, FILE_TYPE_WORKOUT),
                fitUint16(1, MANUFACTURER_GARMIN),
                fitUint16(2, PRODUCT_CONNECT),
                fitUint32z(3, maxOf(1L, workoutId.toLong())),
                fitUint32(4, timeCreatedMillis / 1000 - FIT_EPOCH_OFFSET),
            ),
        )
    }

    private fun writeFileCreator(writer: FitWriter, softwareVersion: Int) {
        writer.write(
            MESSAGE_FILE_CREATOR,
            listOf(fitUint16(0, softwareVersion), fitUint8(1, 0)),
        )
    }

    private fun writeWorkout(
        writer: FitWriter,
        name: String,
        routing: Routing,
        stepCount: Int,
        description: String?,
        totalTimeMillis: Long,
        modernFormat: Boolean,
    ) {
        val fields = mutableListOf<FitField>()
        fields += fitUint16(254, 0)
        fields += fitString(8, name, NAME_BYTES)
        fields += fitEnum(4, routing.sport)
        fields += fitEnum(11, routing.subSport)
        fields += fitUint32z(5, CAPABILITIES_TCX)
        fields += fitUint16(6, stepCount)
        if (!description.isNullOrEmpty()) fields += fitString(17, description, DESCRIPTION_BYTES)
        if (modernFormat) {
            // Five fields no public profile describes, carried by every
            // Fenix-animating yoga reference in the audit set. Their values are
            // copied, not understood: 9 and 23 were always zero, and 10 and 21
            // were both the workout's total time in milliseconds.
            fields += fitUint32(9, 0)
            fields += fitUint32(10, totalTimeMillis)
            fields += fitUint8Array(16, ByteArray(16))
            fields += fitUint32(21, totalTimeMillis)
            fields += fitUint32(23, 0)
        }
        writer.write(MESSAGE_WORKOUT, fields)
    }

    private fun writeSteps(writer: FitWriter, steps: List<Step>) {
        for (step in steps) writer.write(MESSAGE_WORKOUT_STEP, step.fields)
    }

    private fun writeExerciseTitles(writer: FitWriter, titles: Map<Long, String>) {
        titles.entries.forEachIndexed { index, (key, display) ->
            writer.write(
                MESSAGE_EXERCISE_TITLE,
                listOf(
                    fitUint16(254, index),
                    fitUint16(0, (key ushr 32).toInt()),
                    fitUint16(1, (key and 0xFFFFFFFFL).toInt()),
                    fitString(2, display, NAME_BYTES),
                ),
            )
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * An endurance workout — running, cycling, MTB, swimming, hiking, walking.
     *
     * [timeCreatedMillis] is written into `file_id.time_created` and has to be
     * kept: a schedule entry points back at its workout by exactly this value,
     * so regenerating a workout with a fresh timestamp silently breaks the
     * calendar entry that names it.
     *
     * [paces] are seconds per kilometre by zone name, and [lthr]/[ftp] the
     * threshold heart rate and functional threshold power. All three are only
     * consulted when [paceCoaching] is on; with it off, every step is emitted
     * with an open target, which is what the watch shows when the user has not
     * asked to be paced.
     */
    fun endurance(
        name: String,
        sport: String,
        planSteps: List<WorkoutStep>,
        workoutId: Int,
        timeCreatedMillis: Long,
        paceCoaching: Boolean = false,
        paces: Map<String, Double>? = null,
        lthr: Int? = null,
        ftp: Int? = null,
        description: String? = null,
    ): ByteArray {
        val sportKey = sport.ifBlank { "running" }.lowercase()
        val routing = ENDURANCE_ROUTING[sportKey] ?: DEFAULT_ENDURANCE_ROUTING
        val family = sportFamily(sportKey)

        var steps = emitEnduranceSteps(planSteps, family, paces, lthr, ftp, paceCoaching)
        if (steps.isEmpty()) {
            // A workout the plan described only in prose still has to be
            // runnable: warm up, go, cool down.
            steps = listOf(
                buildStep(0, "Warm Up", INTENSITY_WARMUP, DURATION_TIME, millisForMinutes(5.0)),
                buildStep(1, null, INTENSITY_ACTIVE, DURATION_OPEN, 0),
                buildStep(2, "Cool Down", INTENSITY_COOLDOWN, DURATION_TIME, millisForMinutes(5.0)),
            )
        }

        val writer = FitWriter(PROTOCOL_VERSION, PROFILE_VERSION, bigEndian = false)
        writeFileId(writer, workoutId, timeCreatedMillis)
        writeFileCreator(writer, CREATOR_VERSION_LEGACY)
        writeWorkout(
            writer, name, routing, steps.size, description,
            totalTimeMillis = 0, modernFormat = false,
        )
        writeSteps(writer, steps)
        return writer.finish()
    }

    /** One lap of a race plan, as `compute_lap_paces` emits it. */
    data class RaceLap(
        val lap: Int,
        val distanceM: Double,
        val targetSecPerKm: Double,
        val hrCeiling: Int? = null,
    )

    /** A fuelling reminder: where, and what (null name = just grams). */
    data class RaceFuel(val distanceM: Int?, val name: String?, val carbsG: Double)

    /**
     * A race plan as a workout: open warm-up, one distance step per lap, open
     * cool-down — a byte-for-byte port of
     * backend/app/calculators/race_predictor/fit.py `generate_race_fit`,
     * held to it by spec/fixtures/fuel_plan.json.
     *
     * Fuelling reminders ride in the lap step's name ("Km 5 · Gel"), shown as
     * that step begins; see the Python for why a name and not step notes.
     * Verified by fixtures only, not yet on a watch.
     */
    fun race(
        name: String,
        sport: String,
        laps: List<RaceLap>,
        timeCreatedMillis: Long,
        paceCoaching: Boolean = true,
        hrCoaching: Boolean = false,
        maxHr: Int? = null,
        fuel: List<RaceFuel> = emptyList(),
    ): ByteArray {
        val margin = 15
        val routing = ENDURANCE_ROUTING[sport.ifBlank { "running" }.lowercase()] ?: DEFAULT_ENDURANCE_ROUTING
        val steps = mutableListOf<Step>()
        steps += buildStep(0, "Warm Up", INTENSITY_WARMUP, DURATION_OPEN, 0)
        var start = 0.0
        for (lap in laps) {
            val distCm = PyMath.roundToLong(lap.distanceM * 100)
            val end = start + lap.distanceM
            val names = fuel.filter { it.distanceM != null && start <= it.distanceM && it.distanceM < end }
                .map { it.name ?: "${floor(it.carbsG + 0.5).toLong()}g carbs" }
            val label = "Km ${lap.lap}" + if (names.isEmpty()) "" else " · " + names.joinToString(", ")
            start = end
            var targets = NO_TARGETS
            if (hrCoaching && maxHr != null && maxHr != 0) {
                val ceiling = lap.hrCeiling
                if (ceiling != null && ceiling != 0) targets = Targets(hrLow = maxOf(1, ceiling - 10), hrHigh = ceiling)
            } else if (paceCoaching) {
                val pace = lap.targetSecPerKm
                if (pace > 0) {
                    targets = Targets(
                        speedLow = (1_000_000 / (pace + margin)).toInt(),
                        speedHigh = (1_000_000 / maxOf(1.0, pace - margin)).toInt(),
                    )
                }
            }
            steps += buildStep(steps.size, label, INTENSITY_ACTIVE, DURATION_DISTANCE, distCm, targets)
        }
        steps += buildStep(steps.size, "Cool Down", INTENSITY_COOLDOWN, DURATION_OPEN, 0)

        val writer = FitWriter(PROTOCOL_VERSION, PROFILE_VERSION, bigEndian = false)
        writeFileId(writer, 0, timeCreatedMillis)
        writeFileCreator(writer, CREATOR_VERSION_LEGACY)
        writeWorkout(writer, name, routing, steps.size, null, totalTimeMillis = 0, modernFormat = false)
        writeSteps(writer, steps)
        return writer.finish()
    }

    /**
     * A strength, mobility, yoga, pilates, HIIT or cardio session.
     *
     * [workoutType] picks both the routing and the step shape: `strength`
     * emits rep-based sets with rest-and-repeat loops, and everything else
     * emits timed holds, each looped over its sets and sides, for the Yoga and
     * HIIT apps.
     */
    fun strength(
        name: String,
        exercises: List<WorkoutStep>,
        workoutId: Int,
        timeCreatedMillis: Long,
        workoutType: String = "strength",
        description: String? = null,
    ): ByteArray {
        val type = workoutType.ifBlank { "strength" }.lowercase()
        val routing = TYPE_ROUTING[type] ?: DEFAULT_TYPE_ROUTING
        val yogaRouted = type in YOGA_ROUTED

        val emitted = if (type == "strength") emitStrengthSteps(exercises) else emitYogaSteps(exercises)
        val steps = emitted.steps.ifEmpty {
            listOf(buildStep(0, null, INTENSITY_ACTIVE, DURATION_OPEN, 0))
        }

        // Connect's yoga references hold no loops, so they only show this is
        // the sum of the holds; with a loop, it is taken as the time it runs.
        val totalTimeMillis = timedTotalMillis(steps)

        val writer = FitWriter(PROTOCOL_VERSION, PROFILE_VERSION, bigEndian = false)
        writeFileId(writer, workoutId, timeCreatedMillis)
        writeFileCreator(
            writer,
            if (yogaRouted) CREATOR_VERSION_MODERN else CREATOR_VERSION_LEGACY,
        )
        writeWorkout(
            writer, name, routing, steps.size, description,
            totalTimeMillis = totalTimeMillis, modernFormat = yogaRouted,
        )
        writeSteps(writer, steps)
        writeExerciseTitles(writer, emitted.titles)
        return writer.finish()
    }
}
