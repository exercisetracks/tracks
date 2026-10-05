// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import com.tracks.core.api.TrainingGoal
import com.tracks.core.plan.PlanBase
import com.tracks.core.plan.EventDate

/**
 * The goal form's state — the web's `newDraft()` (components/goals/helpers.js)
 * and the fields its New goal form writes, per goal type.
 *
 * A goal type only writes its own fields: an event goal has no CTL ramp, a
 * volume goal no event date. Writing the others as null would stamp them and
 * erase a value somebody set when the goal was a different type — so
 * [values] leaves them out entirely rather than clearing them.
 */
data class GoalDraft(
    val id: Int? = null,
    val goalType: String = "event",
    val eventName: String = "",
    val eventSport: String = "running",
    val eventDate: String = "",
    val eventDistanceMeters: Double? = null,
    /** Fitness: CTL change per week. +2 to start — a steady build nobody gets hurt on. */
    val ctlRampPerWeek: Double = 2.0,
    /** Fitness: the sports it trains, in the order picked; never empty. */
    val fitnessSports: List<String> = listOf("running"),
    val targetWeeklyKm: Double? = null,
    val volumeSport: String = "running",
    val daysPerWeek: Int = 5,
    val planIntensity: Double = 1.0,
    val mtbDiscipline: String = "trail",
    val cyclingDiscipline: String = "road_race",
    val scheduleTests: Boolean = false,
    val includeStrength: Boolean = false,
    val strengthTier: Int = 3,
    val strengthDaysPerWeek: Int? = null,
    val notes: String = "",
    /** The event preset picked last (form-only): which chip is lit. */
    val presetId: String? = null,
    /**
     * The recommended date and its reasons (form-only), for the "?" beside
     * the date. [eventDateAuto] says the date is still the recommended one,
     * so a new sport or distance moves it; picking a date by hand ends that.
     */
    val dateAdvice: EventDate.Advice? = null,
    val eventDateAuto: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    /** A name nobody typed: blank, or still exactly what a preset filled in. */
    private val nameIsAuto: Boolean get() = eventName.isBlank() || eventName in GoalOptions.PRESET_NAMES

    /**
     * An event preset picked: its distance and its name, both. The name used
     * to be filled only while blank, so tapping "10 Mile" and then "10K" left
     * a 10K race called "10 Mile"; a name the person typed is still kept.
     * A preset with no distance ("Gravel Race") clears one the previous
     * preset filled, and keeps one typed by hand.
     */
    fun pickPreset(p: EventPreset): GoalDraft = copy(
        presetId = p.id,
        // An open-water race is an open-water plan.
        eventSport = if (p.id.startsWith("ow-")) "open_water_swimming" else eventSport,
        eventDistanceMeters = p.distanceMeters ?: eventDistanceMeters.takeIf { presetId == null },
        eventName = if (nameIsAuto) p.name.orEmpty() else eventName,
    )

    /** Another sport: its presets differ, so what the last one filled in goes too. */
    fun pickSport(sport: String): GoalDraft = copy(
        eventSport = sport,
        eventDistanceMeters = null,
        presetId = null,
        eventName = if (nameIsAuto) "" else eventName,
    )

    /**
     * The recommendation for this sport and distance: taken as the date on a
     * new event goal until a date is picked by hand; on a goal being edited
     * only its reasons are kept, for the "?".
     */
    fun withAdvice(advice: EventDate.Advice?): GoalDraft = when {
        advice == null -> this
        isNew && goalType == "event" && (eventDate.isBlank() || eventDateAuto) ->
            copy(dateAdvice = advice, eventDate = advice.date.isoformat(), eventDateAuto = true)
        else -> copy(dateAdvice = advice)
    }

    /** What must be filled in before Save is allowed — the web disables its button on the same. */
    val missing: String?
        get() = when (goalType) {
            "event" -> when {
                eventDate.isBlank() -> "Pick the event date."
                else -> null
            }
            "fitness" -> null
            "volume_target" -> if (targetWeeklyKm == null) "Set a weekly distance." else null
            else -> "Choose a goal type."
        }

    /** The contract fields this goal type writes. */
    fun values(): Map<String, Any?> {
        val common = mapOf<String, Any?>(
            "goal_type" to goalType,
            "notes" to notes.ifBlank { null },
            "days_per_week" to daysPerWeek,
            "plan_intensity" to planIntensity,
            "include_strength" to includeStrength,
            "strength_tier" to strengthTier,
            "strength_days_per_week" to strengthDaysPerWeek,
        )
        return common + when (goalType) {
            "event" -> buildMap {
                put("event_name", eventName.ifBlank { null })
                put("event_sport", eventSport)
                put("event_date", eventDate)
                put("event_distance_meters", eventDistanceMeters)
                put("schedule_tests", scheduleTests)
                if (eventSport == "mountain biking") put("mtb_discipline", mtbDiscipline)
                if (eventSport in ROAD_CYCLING) put("cycling_discipline", cyclingDiscipline)
            }
            // The sports go in fitness_sports; the first also in event_sport,
            // the column every other plan path (strength, stretch flows) reads.
            "fitness" -> mapOf(
                "event_sport" to fitnessSports.first(),
                "fitness_sports" to fitnessSports,
                "ctl_ramp_per_week" to ctlRampPerWeek,
            )
            "volume_target" -> mapOf("target_weekly_km" to targetWeeklyKm, "volume_sport" to volumeSport)
            else -> emptyMap()
        }
    }

    /**
     * A kind of swimming or skiing picked. Alpine also turns strength on —
     * that plan leans on the strength planner for its heavy leg work — but it
     * stays a toggle the user can turn back off.
     */
    fun withVariant(sport: String): GoalDraft =
        copy(eventSport = sport, includeStrength = includeStrength || sport == "alpine_skiing")

    /**
     * A fitness sport picked or unpicked; the last one cannot be unpicked.
     * Picking a kind of a sport already picked (open water after pool) swaps
     * it: the planner trains one of each sport family, so two would read as
     * two sports and plan as one.
     */
    fun toggleFitnessSport(sport: String): GoalDraft {
        if (sport in fitnessSports) {
            return if (fitnessSports.size > 1) copy(fitnessSports = fitnessSports - sport) else this
        }
        val family = PlanBase.sportFamily(sport)
        val i = fitnessSports.indexOfFirst { PlanBase.sportFamily(it) == family }
        return if (i >= 0) copy(fitnessSports = fitnessSports.toMutableList().also { it[i] = sport })
        else copy(fitnessSports = fitnessSports + sport)
    }

    /** Only the fields that differ from [original] — an unchanged field is not re-stamped. */
    fun changedValues(original: TrainingGoal?): Map<String, Any?> {
        if (original == null) return values()
        val before = of(original).values()
        return values().filter { (k, v) -> before[k] != v || k !in before }
    }

    companion object {
        /** Road-cycling sports that take a discipline, as on the web. */
        val ROAD_CYCLING = setOf("cycling")

        fun of(goal: TrainingGoal) = GoalDraft(
            id = goal.id,
            goalType = goal.goalType.ifBlank { "event" },
            eventName = goal.eventName.orEmpty(),
            eventSport = goal.eventSport ?: "running",
            eventDate = goal.eventDate.orEmpty(),
            eventDistanceMeters = goal.eventDistanceMeters,
            // Clamped: a goal saved when the slider went to +8 would open off
            // its end, and saving it back would write a ramp the plan caps anyway.
            ctlRampPerWeek = (goal.ctlRampPerWeek ?: 2.0).coerceIn(GoalOptions.RAMP_MIN, GoalOptions.RAMP_MAX),
            fitnessSports = goal.fitnessSports?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(goal.eventSport ?: "running"),
            targetWeeklyKm = goal.targetWeeklyKm,
            volumeSport = goal.volumeSport ?: "running",
            daysPerWeek = goal.daysPerWeek ?: 5,
            planIntensity = goal.planIntensity ?: 1.0,
            mtbDiscipline = goal.mtbDiscipline ?: "trail",
            cyclingDiscipline = goal.cyclingDiscipline ?: "road_race",
            scheduleTests = goal.scheduleTests ?: false,
            includeStrength = goal.includeStrength ?: false,
            strengthTier = goal.strengthTier ?: 3,
            strengthDaysPerWeek = goal.strengthDaysPerWeek,
            notes = goal.notes.orEmpty(),
            presetId = GoalOptions.PRESETS[goal.eventSport ?: "running"]
                ?.firstOrNull { it.distanceMeters != null && it.distanceMeters == goal.eventDistanceMeters }?.id,
        )
    }
}

/**
 * An event preset: the web's `EVENT_PRESETS`, per sport. [name] is what picking
 * it writes into the event's name — the label where the label reads as a
 * name ("Half Marathon"), something better where it does not ("Olympic
 * (S/B/R)" names a format, not an event), and null for a preset that names
 * nothing ("Track / Other").
 */
data class EventPreset(val id: String, val label: String, val distanceMeters: Double?, val name: String? = label)

object GoalOptions {
    val TYPES = listOf(
        Triple("event", "Race / Event", "Train for a specific event on a target date."),
        Triple("fitness", "Fitness", "Build, hold or ease off your fitness — a rolling four-week plan."),
        Triple("volume_target", "Weekly Volume", "Hit a target weekly distance for a sport."),
    )

    val SPORTS = linkedMapOf(
        "running" to "Running", "cycling" to "Cycling", "mountain biking" to "Mountain Biking",
        "swimming" to "Swimming", "triathlon" to "Triathlon", "hiking" to "Hiking",
        "skiing" to "Skiing", "rowing" to "Rowing", "climbing" to "Climbing",
        "strength_training" to "Strength Training", "other" to "Other",
    )

    /**
     * Which kind of swimming or skiing — the web's `SPORT_VARIANTS`. The kind
     * is stored as the sport itself ("alpine_skiing"), which is what the
     * planner and the watch read, so no discipline field is needed. A bare
     * "swimming" or "skiing" is the first option.
     */
    val SPORT_VARIANTS: Map<String, List<Pair<String, String>>> = mapOf(
        "swimming" to listOf("swimming" to "Pool", "open_water_swimming" to "Open water"),
        "skiing" to listOf(
            "cross_country_skiing" to "Cross-country",
            "backcountry_skiing" to "Skimo / touring",
            "alpine_skiing" to "Alpine",
        ),
    )

    /** The sport chip a stored sport belongs to: "alpine_skiing" is under Skiing. */
    fun sportChip(sport: String): String =
        SPORT_VARIANTS.entries.firstOrNull { (chip, options) -> sport == chip || options.any { it.first == sport } }
            ?.key ?: sport

    /** The variant shown selected for [sport]: itself, or its chip's first option. */
    fun variantOf(sport: String): String? {
        val options = SPORT_VARIANTS[sportChip(sport)] ?: return null
        return options.firstOrNull { it.first == sport }?.first ?: options.first().first
    }

    /**
     * What a fitness goal can train, as the concrete sports the planner reads
     * — each swimming and skiing kind its own choice, since a multi-select has
     * no room for a second "Kind" row per sport. Strength is left out: it
     * comes with the goal's own strength settings; "Other" has nothing to
     * plan. Triathlon stands for its three sports.
     */
    val FITNESS_SPORTS: List<Pair<String, String>> = listOf(
        "running" to "Running", "cycling" to "Cycling", "mountain biking" to "Mountain Biking",
        "swimming" to "Pool Swimming", "open_water_swimming" to "Open-Water Swimming",
        "triathlon" to "Triathlon", "hiking" to "Hiking",
        "cross_country_skiing" to "Cross-Country Skiing", "backcountry_skiing" to "Skimo / Touring",
        "alpine_skiing" to "Alpine Skiing", "rowing" to "Rowing", "climbing" to "Climbing",
    )

    // Triathlon distances are the three legs summed: super sprint 0.4 + 10 +
    // 2.5, sprint 0.75 + 20 + 5, Olympic 1.5 + 40 + 10, 70.3 1.9 + 90 + 21.1,
    // full 3.8 + 180 + 42.2 km. GoalPresetsTest checks every preset's
    // distance against the distance its label names.
    val PRESETS: Map<String, List<EventPreset>> = mapOf(
        "running" to listOf(
            EventPreset("5k", "5K", 5000.0), EventPreset("10k", "10K", 10000.0),
            EventPreset("15k", "15K", 15000.0), EventPreset("10mile", "10 Mile", 16093.0),
            EventPreset("half", "Half Marathon", 21097.0), EventPreset("marathon", "Marathon", 42195.0),
            EventPreset("50k", "50K Ultra", 50000.0), EventPreset("50mile", "50 Mile Ultra", 80467.0),
            EventPreset("100k", "100K Ultra", 100000.0), EventPreset("100mile", "100 Mile Ultra", 160934.0),
            EventPreset("track", "Track / Other", null, name = null),
        ),
        "cycling" to listOf(
            EventPreset("tt20", "20K Time Trial", 20000.0), EventPreset("tt40", "40K Time Trial", 40000.0),
            EventPreset("metric", "Metric Century", 100000.0),
            EventPreset("century", "Century (100 mi)", 160934.0, name = "Century"),
            EventPreset("double", "Double Century", 321869.0), EventPreset("gravel", "Gravel Race", null),
            EventPreset("stage", "Stage Race", null), EventPreset("crit", "Criterium", null),
            EventPreset("roadrace", "Road Race", null),
        ),
        "mountain biking" to listOf(
            EventPreset("xc", "XC Race", null), EventPreset("enduro", "Enduro", null),
            EventPreset("marathon", "Marathon (50K+)", 50000.0, name = "MTB Marathon"),
            EventPreset("endur50", "50 Mile", 80467.0, name = "50 Mile MTB"),
            EventPreset("endur100", "100 Mile", 160934.0, name = "100 Mile MTB"),
            EventPreset("bikepack", "Bikepacking", null),
        ),
        "swimming" to listOf(
            EventPreset("tri-sprint", "Sprint Tri Swim (750 m)", 750.0, name = "Sprint Tri Swim"),
            EventPreset("tri-oly", "Olympic Tri Swim (1.5 K)", 1500.0, name = "Olympic Tri Swim"),
            EventPreset("tri-half", "70.3 Swim (1.9 K)", 1900.0, name = "70.3 Swim"),
            EventPreset("tri-full", "Ironman Swim (3.8 K)", 3800.0, name = "Ironman Swim"),
            EventPreset("ow-1mi", "1 Mile Open Water", 1609.0), EventPreset("ow-5k", "5K Open Water", 5000.0),
            EventPreset("ow-10k", "10K Open Water", 10000.0), EventPreset("pool-meet", "Pool Meet", null),
        ),
        "triathlon" to listOf(
            EventPreset("supersprint", "Super Sprint", 12900.0, name = "Super Sprint Triathlon"),
            EventPreset("sprint", "Sprint (S/B/R)", 25750.0, name = "Sprint Triathlon"),
            EventPreset("olympic", "Olympic (S/B/R)", 51500.0, name = "Olympic Triathlon"),
            EventPreset("half", "Half (70.3)", 113000.0, name = "70.3 Triathlon"),
            EventPreset("full", "Ironman (140.6)", 226000.0, name = "Ironman Triathlon"),
            EventPreset("aquabike", "Aquabike", null), EventPreset("duathlon", "Duathlon", null),
        ),
        "hiking" to listOf(
            EventPreset("day", "Day Hike", null), EventPreset("fkt", "FKT Attempt", null),
            EventPreset("thru", "Thru Hike", null), EventPreset("peak", "Peak / Summit", null, name = "Summit"),
        ),
        "skiing" to listOf(
            EventPreset("race", "Ski Race", null), EventPreset("skimo", "Ski Mountaineering", null),
            EventPreset("tour", "Backcountry Tour", null),
        ),
        "rowing" to listOf(
            EventPreset("2k", "2000 m Erg", 2000.0, name = "2K Erg"), EventPreset("5k", "5K Erg", 5000.0),
            EventPreset("head", "Head Race", null), EventPreset("sprint", "Sprint Race", null),
        ),
        "climbing" to listOf(
            EventPreset("project", "Project / Redpoint", null), EventPreset("comp", "Competition", null),
            EventPreset("trip", "Climbing Trip", null),
        ),
        "other" to listOf(EventPreset("custom", "Custom event", null, name = null)),
        "strength_training" to listOf(
            EventPreset("bodybuilding", "Bodybuilding / Hypertrophy", null, name = "Bodybuilding"),
            EventPreset("powerlifting", "Powerlifting", null), EventPreset("general", "General Strength", null),
        ),
    )

    /** Every name a preset writes: a name still equal to one of these was filled in, not typed. */
    val PRESET_NAMES: Set<String> = PRESETS.values.flatten().mapNotNullTo(HashSet()) { it.name }

    /** The strength-focus slider's stops (the goal's strength_tier: sessions a week) and their words. */
    val STRENGTH_FOCUS = listOf(1 to "Maintain", 2 to "Supplement", 3 to "Balanced", 4 to "Strength-first", 5 to "Athlete")

    fun strengthFocusLabel(tier: Int): String = STRENGTH_FOCUS.firstOrNull { it.first == tier }?.second ?: "Balanced"

    /** The web's `INTENSITY_STOPS`. */
    val INTENSITY = listOf(0.5 to "Easy", 0.75 to "Light", 1.0 to "Moderate", 1.25 to "Hard", 1.5 to "Max")

    fun intensityLabel(v: Double): String = INTENSITY.minBy { kotlin.math.abs(it.first - v) }.second

    /**
     * The fitness slider's range and step (CTL per week), as on the web. The
     * top is +6: the plan delivers it, and past it the first week's load is
     * about twice fitness (backend schemas/coaching.py CtlRamp has the
     * measurements and the reasoning).
     */
    const val RAMP_MIN = -2.0
    const val RAMP_MAX = 6.0
    const val RAMP_STEP = 0.5

    /** Above this the slider turns red: the last stretch, hard to absorb for long. */
    const val RAMP_RISK = 3.0

    /** "+3 CTL / week", "−1.5 CTL / week", "0 CTL / week". */
    fun rampLabel(v: Double): String {
        val n = if (v % 1.0 == 0.0) "%.0f".format(kotlin.math.abs(v)) else "%.1f".format(kotlin.math.abs(v))
        val sign = when { v > 0 -> "+"; v < 0 -> "−"; else -> "" }
        return "$sign$n CTL / week"
    }

    /** The word for where a ramp sits — the web's `rampWord`. */
    fun rampWord(v: Double): String = when {
        v < 0 -> "Detraining"
        v < 1 -> "Maintain"
        v <= RAMP_RISK -> "Build"
        else -> "Aggressive"
    }

    val MTB_DISCIPLINES = listOf(
        Triple("xco", "XCO", "Olympic XC · 90 min, VO2 + W' heavy"),
        Triple("xcm", "XCM", "Marathon XC · 2–6 h, sweet spot + long"),
        Triple("enduro", "Enduro", "Timed descents · matchbook + skills heavy"),
        Triple("trail", "Trail", "Recreational riding · year-round periodization"),
    )

    val CYCLING_DISCIPLINES = listOf(
        Triple("road_race", "Road race / Fondo", "Sweet-spot + threshold-heavy build · long Sunday ride"),
        Triple("time_trial", "Time Trial", "Block VO2 → threshold + TT-pace · aero position"),
        Triple("hill_climb", "Hill climb", "Polarized Z2 + sustained climbing · W/kg focused"),
        Triple("criterium", "Criterium", "Anaerobic + over-under + sprint blocks · Carmichael compressed"),
    )
}
