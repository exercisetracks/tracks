// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

/**
 * What a number means, and what to do about it.
 *
 * ## Why this is a table and not a string in each screen
 *
 * These are definitions, and a definition that appears in two places and drifts
 * is worse than one that appears once. Form is explained on the dashboard and
 * again wherever else it shows up; body battery is on the health page and in
 * the Today band. Keeping the copy in one table means the phone and the browser
 * can be checked against each other by reading two files, rather than by
 * grepping for a sentence.
 *
 * ## Why the copy is what it is
 *
 * A metric explanation earns its place by changing a decision. "HRV is heart
 * rate variability" is a restatement of the label; "a multi-day drop is the
 * useful signal, a single low night usually is not" is a reason to keep
 * training or to back off. Every entry here says what the number is in one
 * line, then what to do with it — the same shape the web app's `?` popovers
 * use, and several of these are its wording verbatim so the two do not disagree
 * about their own product.
 */
data class MetricInfo(val title: String, val body: List<String>) {
    constructor(title: String, vararg paragraphs: String) : this(title, paragraphs.toList())
}

object Explain {

    // ── Training model ───────────────────────────────────────────────────────

    val Fitness = MetricInfo(
        "Fitness and fatigue",
        "Fitness (CTL) is a 42-day exponential moving average of your daily " +
            "training stress. It rises slowly as you train consistently and " +
            "decays gradually during rest.",
        "Fatigue (ATL) is a 7-day average. It spikes quickly after hard " +
            "sessions and drops rapidly during rest weeks.",
        "To build fitness, keep fatigue regularly above fitness — then let it " +
            "fall back before anything that matters.",
    )

    /** The fitness goal's slider. The web's `FITNESS_RAMP_INFO` says the same. */
    val FitnessRamp = MetricInfo(
        "Fitness change per week",
        "How many CTL points your fitness should gain each week. 0 holds it; " +
            "below 0 eases off, as in an off-season.",
        "Every fourth week is lighter so the gains settle. The plan rolls four " +
            "weeks ahead and rebuilds from your actual fitness as you train.",
        "Above +3 is aggressive: hard to absorb for long, and injury risk rises. " +
            "Past +6 a week the first week asks for about twice your usual daily load, " +
            "so the slider stops there.",
    )

    /** The fitness goal's sport picker. The web's `FITNESS_SPORTS_INFO` says the same. */
    val FitnessSports = MetricInfo(
        "Sports",
        "Pick every sport you want to train. The plan shares one weekly load between them — " +
            "fitness is one number, whatever made it.",
        "The share follows what you have actually been doing lately, and each sport keeps " +
            "at least a day. Hard days and running days are spread so they do not land back to back.",
        "Triathlon trains swimming, cycling and running.",
    )

    // The web's INTENSITY_INFO and STRENGTH_FOCUS_INFO (components/goals/constants.jsx) say the same.
    val PlanIntensity = MetricInfo(
        "Intensity",
        "Scales how long every planned session is: Easy is half, Moderate the plan as designed, Max half again.",
        "Too hard? Turn it down any time — the plan rebuilds from today, and what you have done stays.",
    )

    val StrengthFocus = MetricInfo(
        "Strength focus",
        "How many strength sessions a week the plan adds, 1 to 5, and how heavy the split is: " +
            "1 keeps what you have, 5 is a dedicated strength block.",
        "Change it any time; the plan rebuilds.",
    )

    /** The goal form's pool / open-water choice. The web's `SPORT_VARIANTS.swimming.info`. */
    val SwimVariant = MetricInfo(
        "Pool or open water",
        "Pool plans are sets in lengths (drills, kick, pull, CSS and VO2 sets) that run as " +
            "pool-swim workouts on the watch.",
        "Open-water plans are timed, and practise sighting, drafting and the pace changes of a mass start.",
    )

    /** The goal form's kind of skiing. The web's `SPORT_VARIANTS.skiing.info`. */
    val SkiVariant = MetricInfo(
        "Kind of skiing",
        "Cross-country and skimo are endurance plans: distance, threshold and uphill intervals, " +
            "with dry-land ski-walking and bounding.",
        "Alpine is a dry-land conditioning block: aerobic base, eccentric leg work, plyometrics, " +
            "agility and ski-run intervals. Turn on strength for the heavy leg work.",
    )

    val Form = MetricInfo(
        "Form (TSB)",
        "Form is fitness minus fatigue. Negative means you are carrying " +
            "fatigue; positive means you are rested.",
        "The optimal band (−30 to −5) is where most fitness gains happen — " +
            "training hard without overdoing it. Fresh is where you want to be " +
            "before a race.",
        "Long stretches in the high-risk band are how overtraining starts.",
    )

    val Readiness = MetricInfo(
        "Readiness",
        "A single score out of 100 for how recovered you are, combining last " +
            "night's sleep, your HRV and your resting heart rate against your " +
            "own baselines, and your recent training load. With no watch it is " +
            "read from training load alone — your recorded workouts — and is a " +
            "rougher guide.",
        "It is a suggestion about today's intensity, not a verdict. One low " +
            "score after a late night means less than three in a row.",
    )

    val Vo2Max = MetricInfo(
        "VO₂max",
        "The watch's estimate of how much oxygen you can use at maximum " +
            "effort, in ml/kg/min. It is computed from pace against heart rate " +
            "on steady outdoor runs and rides. With no watch reading, it is " +
            "estimated from your running pace instead: the best run of the last " +
            "90 days, by Jack Daniels' VDOT formula — rougher, and only as high " +
            "as your hardest recent run.",
        "Read the direction, not the number. The absolute value depends on the " +
            "model; the trend over months is the part that reflects training.",
    )

    /**
     * Deliberately separate from [TrainingLoad], which the weekly-volume card
     * used to show. Training load is the *stress* the fitness model runs on;
     * this chart plots kilometres and hours, which are neither. The wrong
     * explanation is worse than none: it invites the reader to treat the bars
     * as scores.
     */
    val WeeklyVolume = MetricInfo(
        "Weekly volume",
        "How much you trained in each week, Monday to Sunday. The columns are " +
            "distance and the line is time, on their own scales — two weeks of " +
            "60 km are not the same week if one took four hours and the other " +
            "took nine.",
        "Weeks rather than days because a week is the unit that survives a " +
            "missed session. Consistency reads here as an even row; a spike " +
            "followed by a gap reads as one.",
        "Tap a sport below to narrow both series to it.",
    )

    val TrainingLoad = MetricInfo(
        "Training load",
        "Each session's stress score, accumulated. A hard hour scores more " +
            "than an easy one, and the totals are what the fitness model is " +
            "built from.",
    )

    // ── Sleep ────────────────────────────────────────────────────────────────

    val Sleep = MetricInfo(
        "Sleep stages",
        "Deep sleep is when physical recovery happens; REM is when the day is " +
            "consolidated; light sleep is most of the night and is where the " +
            "transitions live.",
        "Proportions matter more than the total. Eight hours that are almost " +
            "all light sleep is a worse night than seven with a normal deep " +
            "share — which is why the stages are stacked rather than plotted " +
            "as separate lines.",
        "Awake time is measured separately and never counted as sleep, so " +
            "time in bed is the two added together. A night with an hour of " +
            "waking in it is a different night from a short one slept straight " +
            "through, and only the split says which you had.",
    )

    val SleepScore = MetricInfo(
        "Sleep score",
        "The watch's own 0–100 verdict on the night, from duration, stage " +
            "balance, restlessness and overnight heart rate.",
        "Useful as a trend against your own history. Comparing it with someone " +
            "else's is comparing two devices' opinions.",
    )

    // ── Body ─────────────────────────────────────────────────────────────────

    val BodyBattery = MetricInfo(
        "Body Battery",
        "The watch's estimate of how much you have left, from 0 to 100. It " +
            "charges with rest and sleep and drains with activity and stress.",
        "The shape of the day is the signal: waking below 50 repeatedly, or " +
            "never charging past 60, says more than any single reading.",
        "This is the device's own model — Garmin has not published it, so it is " +
            "stored exactly as reported and never recomputed here.",
    )

    val RestingHr = MetricInfo(
        "Resting heart rate",
        "Your lowest sustained heart rate, usually measured overnight. It " +
            "falls over months as aerobic fitness improves.",
        "A jump of five or more beats above your own normal, for more than a " +
            "day, is a reliable early sign of illness, poor sleep or too much " +
            "training.",
    )

    val Hrv = MetricInfo(
        "HRV",
        "The variation between consecutive heartbeats, in milliseconds, " +
            "measured overnight. Higher generally means better recovered.",
        "Absolute values vary hugely between people — yours is only comparable " +
            "with your own. A multi-day decline is the useful signal; one low " +
            "night usually is not.",
    )

    val Spo2 = MetricInfo(
        "Blood oxygen",
        "The percentage of oxygen your blood is carrying, measured at the " +
            "wrist. Typically 95–100% at sea level.",
        "Wrist readings are noisy and drop with a cold sensor or a loose strap. " +
            "It is most interesting at altitude, where a falling overnight " +
            "average tracks how acclimatised you are.",
    )

    /**
     * Longer than its neighbours, because the chart behind this dial is the
     * only one in the app that changes resolution under the reader — every
     * reading on a short window, one point per day on a long one — and a
     * spike means twenty minutes in the first and a whole day in the second.
     * A reader who does not know that is being quietly misled by a chart that
     * looks the same either way.
     */
    val Stress = MetricInfo(
        "Stress",
        "A 0–100 score the watch derives from heart rate variability during " +
            "the day. Low is calm, high is a body under load — physical or not.",
        "Exercise reads as high stress, which is correct and worth remembering " +
            "when reading a training day.",
        "The chart is the watch's own readings — one every few minutes — with " +
            "the line coloured by the band it is passing through: teal is " +
            "resting, green low, amber medium, orange high. The shading under " +
            "it is the same four colours, so how much of a day was spent high " +
            "up is readable as a block of colour rather than a number.",
        "The dashed line is your average across the window. A level means " +
            "little on its own; higher or lower than your own usual is the " +
            "comparison worth making.",
        "Where the line breaks, the watch recorded nothing — off the wrist, or " +
            "on a charger. Faint vertical lines are midnights, which is how a " +
            "habit shows up: the same peak at the same hour on most days.",
        "Over a year or a lifetime the chart switches to one point per day, " +
            "because a year of three-minute readings is a solid block of ink. " +
            "The label under the chart says which of the two you are looking " +
            "at — a spike is twenty minutes on one and a whole day on the other.",
    )

    val Respiration = MetricInfo(
        "Respiration",
        "Breaths per minute, averaged across the day. Adults at rest are " +
            "typically 12–20.",
        "A raised overnight average alongside a raised resting heart rate is " +
            "one of the earlier signs that something is coming on.",
    )

    val Steps = MetricInfo(
        "Steps",
        "Everything you walked, training or not. It is a measure of how active " +
            "the day was rather than how hard you trained.",
        "Worth watching on rest days: a 20,000-step rest day is not a rest day.",
    )

    val CaloriesBurned = MetricInfo(
        "Calories burned",
        "Everything the day has cost so far: the resting burn your body spends " +
            "simply existing, plus the active calories you earned on top of it. " +
            "The dial shows the two as separate colours.",
        "Resting burn is the watch's metabolic-rate estimate, scaled to how " +
            "much of the day has actually happened — so at breakfast it is a " +
            "fraction of the figure, not the whole one.",
    )

    val Weight = MetricInfo(
        "Weight",
        "Entered by hand or synced from a scale. It feeds power-to-weight and " +
            "the calorie targets.",
        "Weigh at the same time of day — first thing, before eating — or the " +
            "daily noise will be larger than the trend.",
    )

    val Hydration = MetricInfo(
        "Hydration",
        "What you have logged drinking today. Sweat losses run roughly half a " +
            "litre to a litre an hour of hard exercise, more in heat.",
    )

    val CaloriesIn = MetricInfo(
        "Calories eaten",
        "Everything you logged eating that day, added up. Compare it with the " +
            "day's burn to see roughly where the balance landed.",
        "Days from before food was logged meal by meal show the daily total " +
            "that was typed in instead.",
    )

    // ── Activity ─────────────────────────────────────────────────────────────

    val ClimbEffort = MetricInfo(
        "Climb effort",
        "Garmin's per-route effort score (0–100). It combines time on the " +
            "wall, vertical speed and heart rate intensity.",
    )

    val GritFlow = MetricInfo(
        "Grit and Flow",
        "Grit scores how rough and demanding a descent is, from acceleration " +
            "data. Flow scores how smoothly you rode it — higher means better " +
            "lines and less braking.",
        "Both are Garmin mountain-bike metrics, recorded every second.",
    )

    // ── Strength & flexibility ───────────────────────────────────────────────

    val MyWorkouts = MetricInfo(
        "My workouts",
        "Tap New, or tick exercises below and save them as a workout.",
    )

    val MyFlows = MetricInfo(
        "Your flows",
        "Tap New, or tick stretches below and save them as a flow.",
    )

    /** What the ♡ and ⊘ on every library row do — once, not on every visit. */
    val Library = MetricInfo(
        "Library",
        "Tick items to start a session or save them. Tap one for details.",
        "♡ Prefer — your plan picks it more often.",
        "⊘ Never — your plan leaves it out.",
    )
}
