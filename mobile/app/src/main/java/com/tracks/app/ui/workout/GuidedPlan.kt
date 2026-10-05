// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import com.tracks.core.format.distance
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.WorkoutStep
import kotlin.math.roundToInt

/** What a step is for, which is all the colour and the wording need to know. */
enum class StepKind { Warmup, Work, Rest, Cooldown }

/**
 * A lift, as the plan prescribed it.
 *
 * Carried through the runner so a finished session can be logged with real
 * numbers rather than a note saying it happened — the server folds each
 * exercise's best set into that lift's estimated max, and a session logged
 * without sets teaches it nothing.
 */
data class StrengthTarget(
    val name: String,
    val reps: Int?,
    val weightKg: Double?,
)

/**
 * One thing the guide will ask for, in order.
 *
 * Flat and already expanded: a four-set exercise is four of these plus its
 * rests, not one with a counter inside it. That is what lets the screen say
 * "step 7 of 24" and show what is coming next, neither of which a stepper that
 * computes as it goes can do — the same reasoning as the mobility runner's
 * holds, and deliberately the same shape.
 */
data class GuidedStep(
    val title: String,
    /** The target, spelled out: "400 m · Threshold", "8 reps · 60 kg". */
    val detail: String? = null,
    val note: String? = null,
    val cues: List<String> = emptyList(),
    /** Countdown target. Null means the step ends when the user says it does. */
    val seconds: Int? = null,
    /** Distance target, for a step a GPS recording can measure itself. */
    val metres: Double? = null,
    val kind: StepKind = StepKind.Work,
    /** "3 of 6", "Set 2 of 4 · Left" — where this sits inside a repeat. */
    val position: String? = null,
    val exercise: StrengthTarget? = null,
    /**
     * Whether a recorded run counts this step's distance. False for the plan's
     * walking warm-up and cool-down: they are not running, and a guided 5 km
     * used to finish reading 5.5 km with its first split 500 m early.
     */
    val countsDistance: Boolean = true,
) {
    /** A step with neither target runs until the user advances it. */
    val isOpen: Boolean get() = seconds == null && metres == null
}

/**
 * Flatten a planned workout into the sequence a watch would walk you through.
 *
 * ## Why this is one function and not a class per sport
 *
 * The generator writes three families of step into one list — cardio blocks,
 * repeats, and exercises — and which family a workout uses is decided by the
 * plan, not by the sport: a running plan carries mobility steps, a strength day
 * carries a warm-up made of stretches. Splitting this by sport would put the
 * same `interval_set` handling in four places and still get the mixed days
 * wrong.
 *
 * ## Unknown types still run
 *
 * A step type nobody here has heard of becomes a timed block named after
 * itself, exactly as the calendar renders it. The generator gains step types
 * faster than this client is rebuilt, and a session that silently skips the
 * middle third of a workout is worse than one that says "fartlek, 20 minutes"
 * without knowing what a fartlek is.
 */
fun guidedSteps(workout: PlannedWorkout): List<GuidedStep> {
    val steps = buildList {
        workout.steps.forEach { step -> expand(step, this) }
    }
    if (steps.isNotEmpty()) return steps

    // Nothing structured. Most non-running sports are described only in prose,
    // and a plan that says "60 min endurance ride" is still a session someone
    // wants counted down — so it becomes a single block rather than an empty
    // screen with a Finish button on it.
    return listOf(
        GuidedStep(
            title = workout.title.ifBlank { "Workout" },
            detail = buildString {
                workout.durationMinutes?.let { append("$it min") }
                workout.distanceMeters?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(distance(it))
                }
            }.ifBlank { null },
            note = workout.description,
            seconds = workout.durationMinutes?.let { it * 60 },
            metres = workout.distanceMeters?.takeIf { it > 0 },
        )
    )
}

private fun expand(step: WorkoutStep, into: MutableList<GuidedStep>) {
    when (step.type) {
        "strength_exercise" -> expandStrength(step, into)
        "mobility_exercise" -> expandMobility(step, into)
        "interval_set" -> expandRepeats(step, into, label = "Interval")
        "effort_set" -> expandRepeats(step, into, label = "Effort")
        else -> into += GuidedStep(
            title = cardioTitle(step.type),
            detail = target(step.durationMin?.let { minutes(it) }, effort(step)),
            note = step.note,
            seconds = step.durationMin?.let { minutes(it) },
            metres = step.distanceM?.takeIf { it > 0 },
            kind = when (step.type) {
                "warmup" -> StepKind.Warmup
                "cooldown" -> StepKind.Cooldown
                else -> StepKind.Work
            },
            countsDistance = step.type != "walk",
        )
    }
}

/**
 * Sets, with the rest between them.
 *
 * No countdown on the working set: a set ends when the bar is racked, and a
 * timer that decides that for you is a timer people put the bar down for. The
 * rest afterwards *is* timed, because that is the number nobody keeps honestly
 * in their head — and it starts itself, as the strength runner's does.
 */
private fun expandStrength(step: WorkoutStep, into: MutableList<GuidedStep>) {
    val name = step.name ?: "Exercise"
    val sets = (step.sets ?: 1).coerceIn(1, MAX_SETS)
    val rest = step.restSeconds ?: 0

    for (set in 1..sets) {
        into += GuidedStep(
            title = name,
            detail = buildString {
                step.reps?.let { append("$it reps") }
                step.weightKg?.takeIf { it > 0 }?.let {
                    if (isNotEmpty()) append(" · ")
                    append("${it.roundToInt()} kg")
                }
                step.targetRpe?.let {
                    if (isNotEmpty()) append(" · ")
                    append("RPE ${it.roundToInt()}")
                }
                step.tempo?.let {
                    if (isNotEmpty()) append(" · ")
                    append("tempo $it")
                }
            }.ifBlank { null },
            note = step.note,
            cues = step.cues,
            position = "Set $set of $sets",
            kind = if (step.phase == "warmup") StepKind.Warmup else StepKind.Work,
            exercise = StrengthTarget(name, step.reps, step.weightKg),
        )
        if (rest > 0 && set < sets) into += recovery(rest)
    }
}

/**
 * Holds: every set, and both sides of a one-sided stretch.
 *
 * A short transition between them rather than none, matching the mobility
 * runner — being dropped straight into the next stretch means the first ten
 * seconds of every hold are spent getting into it.
 */
private fun expandMobility(step: WorkoutStep, into: MutableList<GuidedStep>) {
    val name = step.name ?: "Stretch"
    val sets = (step.sets ?: 1).coerceIn(1, MAX_SETS)
    val sides = if (step.eachSide) listOf("Left", "Right") else listOf(null)
    val hold = step.durationSeconds ?: DEFAULT_HOLD_SECONDS

    for (set in 1..sets) {
        sides.forEach { side ->
            into += GuidedStep(
                title = name,
                detail = "${hold}s hold",
                note = step.description ?: step.note,
                cues = step.cues + listOfNotNull(step.breathCue),
                seconds = hold,
                position = listOfNotNull(
                    side,
                    "Set $set of $sets".takeIf { sets > 1 },
                ).joinToString(" · ").ifBlank { null },
                kind = if (step.phase == "warmup") StepKind.Warmup else StepKind.Work,
            )
            into += recovery(TRANSITION_SECONDS)
        }
    }
    // The transition after the last hold belongs to whatever comes next, and
    // nothing does if this was the final stretch.
    if (into.lastOrNull()?.kind == StepKind.Rest) into.removeAt(into.lastIndex)
}

/**
 * A repeat set, unrolled.
 *
 * `interval_set` measures its work in metres and its rest in seconds;
 * `effort_set` measures both in minutes, sometimes in seconds. Both unroll the
 * same way, so the difference is which fields are read rather than which code
 * runs.
 */
private fun expandRepeats(step: WorkoutStep, into: MutableList<GuidedStep>, label: String) {
    val reps = (step.reps ?: 1).coerceIn(1, MAX_REPS)
    val work = step.durationMinEach?.let { minutes(it) }
        ?: step.durationSecEach?.roundToInt()
        ?: step.durationMin?.let { minutes(it) }?.takeIf { step.distanceM == null }
    val distance = step.distanceM?.takeIf { it > 0 }
    val rest = step.restMin?.let { minutes(it) } ?: step.restSec ?: step.restSeconds ?: 0

    for (rep in 1..reps) {
        into += GuidedStep(
            title = label,
            detail = target(
                work,
                distance?.let { "${it.roundToInt()} m" },
                effort(step),
            ),
            note = step.note.takeIf { rep == 1 },
            seconds = work,
            metres = distance,
            position = "$rep of $reps",
        )
        if (rest > 0 && rep < reps) into += recovery(rest)
    }
}

/**
 * A step as it should be *said* when it starts.
 *
 * The screen's shorthand does not survive a TTS engine: "Recovery. 30s" came
 * out as "recovery, thirty ess" — or on some engines just "thirty s" — and a
 * runner with the phone in a pocket could not tell a rest from a rep. So a
 * rest is announced as what it is ("30 second rest"), and every other step's
 * units are spelled out ("400 metres", "8 minutes").
 */
fun spokenStep(step: GuidedStep): String {
    if (step.kind == StepKind.Rest) {
        val secs = step.seconds ?: return "Rest"
        return "${spokenSeconds(secs)} rest"
    }
    return listOfNotNull(step.title, step.position, step.detail?.let(::spokenUnits)).joinToString(". ")
}

/** "30 second", "2 minute", "1 minute 30 second" — the form "… rest" wants. */
internal fun spokenSeconds(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return when {
        m == 0 -> "$s second"
        s == 0 -> "$m minute"
        else -> "$m minute $s second"
    }
}

/** The detail's abbreviations, spelled out: 30s, 8 min, 5:30, 400 m, 60 kg. */
internal fun spokenUnits(detail: String): String = detail
    .replace(Regex("""\b(\d+):(\d{2})\b""")) { r ->
        val (m, s) = r.destructured
        val sec = s.toInt()
        "$m minute${if (m == "1") "" else "s"}" + if (sec > 0) " $sec second${if (sec == 1) "" else "s"}" else ""
    }
    .replace(Regex("""\b(\d+)s\b""")) { r -> plural(r.groupValues[1], "second") }
    .replace(Regex("""\b(\d+) min\b""")) { r -> plural(r.groupValues[1], "minute") }
    .replace(Regex("""\b(\d+) m\b""")) { r -> plural(r.groupValues[1], "metre") }
    .replace(Regex("""\b(\d+) kg\b""")) { r -> plural(r.groupValues[1], "kilogram") }
    .replace(" · ", ", ")

private fun plural(n: String, unit: String) = "$n $unit${if (n == "1") "" else "s"}"

private fun recovery(seconds: Int) = GuidedStep(
    title = "Recovery",
    detail = "${seconds}s",
    seconds = seconds,
    kind = StepKind.Rest,
)

/** Minutes as the generator writes them — fractional, and always whole seconds here. */
private fun minutes(value: Double): Int = (value * 60).roundToInt()

/** The pace zone or the effort zone, whichever this sport steers by. */
private fun effort(step: WorkoutStep): String? =
    step.pace?.let(::zoneLabel) ?: step.intensity?.let(::zoneLabel)

private fun target(seconds: Int?, vararg parts: String?): String? =
    (listOfNotNull(*parts) + listOfNotNull(seconds?.let { clockish(it) }))
        .joinToString(" · ")
        .ifBlank { null }

private fun clockish(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun cardioTitle(type: String): String = when (type) {
    "warmup" -> "Warm-up"
    "cooldown" -> "Cool-down"
    "run" -> "Run"
    "ride" -> "Ride"
    "swim" -> "Swim"
    "walk" -> "Walk"
    "fartlek" -> "Fartlek"
    "activity", "" -> "Session"
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Pace and effort zone names, as the plan generator writes them. */
private fun zoneLabel(zone: String): String = when (zone) {
    "easy" -> "Easy"
    "recovery" -> "Recovery"
    "marathon" -> "Marathon pace"
    "threshold" -> "Threshold"
    "interval" -> "Interval"
    "repetition" -> "Rep pace"
    "tempo" -> "Tempo"
    "endurance" -> "Endurance"
    "sweet_spot" -> "Sweet spot"
    "aerobic" -> "Aerobic"
    else -> zone.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** What a stretch is held for when the step does not say. Matches the flow runner. */
private const val DEFAULT_HOLD_SECONDS = 30

/** Long enough to get into the next position, short enough not to be a rest. */
private const val TRANSITION_SECONDS = 10

/**
 * Caps, against a plan that says something impossible.
 *
 * These are not expected to bind. They exist because the expansion is a loop
 * over numbers that arrived over the network, and the failure mode of not
 * having them is an out-of-memory on a screen someone opened at the gym.
 */
private const val MAX_SETS = 12
private const val MAX_REPS = 40
