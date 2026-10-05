# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Plan generator: assemble dated strength + mobility PlannedWorkout dicts.

`generate_strength_workouts` is the orchestrator. It walks the plan span week by
week, places sessions on suitable days (avoiding hard-cardio interference),
selects exercises (exercises.py), prescribes sets/reps/weight (periodization.py
+ loads.py + injuries.py), and adds a weekly standalone mobility session
(mobility.py). The two module-private helpers below build the workout card
description and choose the mobility day.

Scheduling rules (NOTES.md design decisions):
  - Supplementary tier placed on light/rest days to avoid overtraining
  - Strength sessions are inserted in addition to the existing endurance plan
  - Concurrent training: strength BEFORE endurance; never scheduled on a hard
    cardio day if avoidable

Science: 2–3×/week at 10–20 sets/muscle = optimal for all goals except pure
strength (3–5×/week).
"""

from __future__ import annotations

import math
from datetime import date, timedelta

from .exercises import _select_exercises
from .slots import LEG_SPLITS
from .archetypes import select_archetype
from .injuries import _exercise_is_safe, _injury_load_modifier
from .leveling import (
    experience_max_difficulty,
    experience_stage_floor,
    starting_weight_factor,
)
from .loads import conservative_starting_weight, round_weight_for_equipment, training_weight
from .mobility import _mobility_description, _mobility_steps
from .periodization import (
    _periodization_prescription,
    _rest_seconds,
    _session_duration_minutes,
    _TIER_SETS,
    is_endurance_family,
)

def generate_strength_workouts(
    goal,
    sport_family: str,
    today: date,
    existing_workouts: list[dict],
    equipment: list[str],
    library: dict,
    strength_records: dict,
    active_injuries: list,
    sessions_count: int = 0,
    units: str = "metric",
    preferred_exercises: set[str] | None = None,
    excluded_exercises: set[str] | None = None,
    session_max_minutes: int | None = None,
    regen_salt: str = "",
    confirmed_no_exercises: set[str] | None = None,
    stretch_candidates: list | None = None,
    experience: str | None = None,
    anchor_date: date | None = None,
) -> list[dict]:
    """
    Generate strength PlannedWorkout dicts to be inserted alongside the
    endurance sessions in the existing plan.

    Returns list of dicts compatible with PlannedWorkout model.
    """
    tier = getattr(goal, "strength_tier", 3)
    explicit_days = getattr(goal, "strength_days_per_week", None)
    race_date     = getattr(goal, "event_date", None)
    # A race already run is no race. Left in place, the 12-week fallback span
    # below would still be cut off at it — every day is "on or after the race"
    # — and the plan would silently carry no strength or mobility at all.
    if race_date and race_date <= today:
        race_date = None
    include_mobility = True
    # Experience-derived knobs (leveling.py): an extra difficulty ceiling, a
    # periodization-stage floor, and cold-start load conservatism.
    exp_max_difficulty = experience_max_difficulty(experience)
    exp_session_floor  = experience_stage_floor(experience)
    exp_weight_factor  = starting_weight_factor(experience)
    # Rep/load scheme depends on whether strength is supplementary to an
    # endurance sport or the primary goal (see periodization.py).
    endurance = is_endurance_family(sport_family)

    # ── Determine sessions per week ──────────────────────────────────────────
    # Five intensity levels (1–5), default 3:
    #   5 = 5×/week  (dedicated strength athlete)
    #   4 = 4×/week  (strength-primary)
    #   3 = 3×/week  (balanced, default)
    #   2 = 2×/week  (supplementary, endurance-plan strength)
    #   1 = 1×/week  (maintenance / minimal dose)
    if explicit_days:
        sessions_per_week = int(explicit_days)
    elif tier == 5:
        sessions_per_week = 5
    elif tier == 4:
        sessions_per_week = 4
    elif tier == 3:
        sessions_per_week = 3   # default balanced
    elif tier == 2:
        sessions_per_week = 2   # supplementary
    else:
        sessions_per_week = 1   # minimal

    # ── Build lookup of existing scheduled dates ────────────────────────────
    # Map date → workout_type to avoid scheduling strength on hard cardio days
    existing_by_date: dict[date, list[str]] = {}
    for w in existing_workouts:
        d = w.get("scheduled_date") if isinstance(w, dict) else getattr(w, "scheduled_date", None)
        wtype = w.get("workout_type") if isinstance(w, dict) else getattr(w, "workout_type", "")
        if d:
            existing_by_date.setdefault(d, []).append(wtype or "")

    # ── Determine plan span ───────────────────────────────────────────────────
    if race_date and race_date > today:
        plan_end = race_date
    else:
        # No race goal: generate 12 weeks
        plan_end = today + timedelta(weeks=12)

    plan_monday = today - timedelta(days=today.weekday())
    total_weeks = max(1, math.ceil((plan_end - plan_monday).days / 7))

    # Block anchor: the training block a week belongs to is defined by its
    # absolute calendar position relative to the goal's start Monday (not the
    # plan's — which re-anchors to *today* on every regeneration). This keeps
    # block-stable main lifts and deload placement stable across regenerations.
    anchor_monday = (anchor_date or today) - timedelta(days=(anchor_date or today).weekday())

    # ── Split type rotation ───────────────────────────────────────────────────
    split_rotation_ppl  = ["ppl_push", "ppl_pull", "ppl_legs", "ppl_push", "ppl_pull"]
    split_rotation_ul   = ["upper_a", "lower_a", "upper_b", "lower_b", "full_body"]
    split_rotation_supp = ["supp_lower", "supp_upper_core"]
    split_rotation_min  = ["full_body"]  # 1x/week — single full-body session

    if tier >= 5:
        split_sequence = split_rotation_ppl[:sessions_per_week]
    elif tier == 4:
        split_sequence = split_rotation_ul[:sessions_per_week]
    elif tier == 3:
        split_sequence = split_rotation_ul[:sessions_per_week]
    elif tier == 2:
        split_sequence = split_rotation_supp
    else:
        split_sequence = split_rotation_min

    # ── Preferred weekdays for strength (Mon/Wed/Fri for 3×, or Mon/Thu for 2×) ─
    # These are biases — if there's a hard cardio session already, we prefer
    # a different day.  We never block strength on a hard cardio day.
    if sessions_per_week >= 4:
        preferred_days = [0, 1, 3, 4, 5]   # Mon, Tue, Thu, Fri, Sat
    elif sessions_per_week == 3:
        preferred_days = [0, 2, 4]          # Mon, Wed, Fri
    elif sessions_per_week == 2:
        preferred_days = [0, 3]             # Mon, Thu
    else:
        preferred_days = [2]                # Wed only for 1×/week

    # ── Session counter for DUP indexing ─────────────────────────────────────
    session_counter = 0
    # Split index runs across the whole plan (not reset per week) so the split
    # rotation balances over time — e.g. a 3-day upper/lower athlete gets 3
    # upper + 3 lower across two weeks rather than 2:1 upper every week.
    split_idx = 0
    workouts = []

    for week_num in range(total_weeks):
        week_start = plan_monday + timedelta(weeks=week_num)

        # Absolute (regen-stable) week/block indices from the goal anchor.
        week_index = (week_start - anchor_monday).days // 7
        block_num = week_index // 4
        week_in_block = week_index % 4   # 0,1,2 loading → 3 deload

        # Determine periodization prescription for this week. The experience
        # floor lets a self-declared regular/advanced lifter with an empty
        # logbook skip the novice linear stage; real history wins via max().
        overall_sessions = max(sessions_count + session_counter, exp_session_floor)
        if overall_sessions < 20:
            stage = "linear"
        elif overall_sessions < 100:
            stage = "weekly_undulating"
        else:
            stage = "dup"

        # Deload detection: every 4th week (matches 3:1 endurance cycle)
        # DUP: additional deload every 8th week to prevent overreaching (Issurin 2010)
        is_deload = (week_in_block == 3) or (stage == "dup" and week_index % 8 == 7)

        week_sessions_placed = 0
        # Names used earlier this week — avoided so a 3–5 day block doesn't
        # repeat the same lift on back-to-back days.
        week_used: set[str] = set()

        for day_offset in range(7):
            if week_sessions_placed >= sessions_per_week:
                break

            session_date = week_start + timedelta(days=day_offset)
            weekday = session_date.weekday()  # 0=Mon, 6=Sun

            # Skip past dates
            if session_date < today:
                continue

            # Stop at race date
            if race_date and session_date >= race_date:
                continue

            # ── Day preference scoring ────────────────────────────────────────
            # Score: prefer preferred_days, penalise days with hard cardio,
            #        and penalise days immediately after hard cardio.
            is_preferred = weekday in preferred_days

            day_types  = existing_by_date.get(session_date, [])
            _HARD_TYPES = frozenset(("intervals", "threshold", "tempo", "vo2max",
                                     "race", "long", "long_run", "fartlek",
                                     "race_pace", "short_quality", "aerobic"))
            is_hard_cardio = any(wt in _HARD_TYPES for wt in day_types)

            # Skip days with hard cardio (concurrent training interference)
            if is_hard_cardio:
                continue
            # Prefer preferred days; don't skip non-preferred but place preferred first
            if not is_preferred and week_sessions_placed < sessions_per_week:
                # Try to keep going to find a preferred day
                still_possible = any(
                    (week_start + timedelta(days=d)).weekday() in preferred_days
                    and (week_start + timedelta(days=d)) >= today
                    and not any(
                        wt in ("intervals", "threshold", "tempo", "vo2max")
                        for wt in existing_by_date.get(week_start + timedelta(days=d), [])
                    )
                    for d in range(day_offset + 1, 7)
                )
                if still_possible:
                    continue

            # ── Determine split for this session ─────────────────────────────
            split_key = split_sequence[split_idx % len(split_sequence)] if split_sequence else "full_body"
            split_idx += 1

            # ── Workout archetype (coach blueprint) for this session ─────────
            # Rotates per block so a split cycles its applicable archetypes block
            # to block. None → fall back to the default (no-archetype) behaviour.
            archetype = select_archetype(split_key, sport_family, tier, stage, block_num)
            superset_of = {}
            if archetype:
                for gi, pair in enumerate(archetype.supersets):
                    for slot_key in pair:
                        superset_of[slot_key] = gi

            # ── Exercise selection ────────────────────────────────────────────
            max_ex = 3 if tier <= 2 else (4 if tier == 3 else 5)
            slot_picks = _select_exercises(
                sport_family=sport_family,
                tier=tier,
                split_type=split_key,
                equipment=equipment,
                library=library,
                active_injuries=active_injuries,
                max_exercises=max_ex,
                preferred=preferred_exercises,
                excluded=excluded_exercises,
                week_num=week_num,
                regen_salt=regen_salt,
                confirmed_no=confirmed_no_exercises,
                used_this_week=week_used,
                max_difficulty=exp_max_difficulty,
                block_num=block_num,
                return_slots=True,
            )

            if not slot_picks:
                continue  # No safe exercises available (e.g. all banned by injuries)

            exercises = [name for _slot, name in slot_picks]
            week_used.update(exercises)

            # ── Build prescription for each exercise ──────────────────────────
            steps = []
            # Representative prescription for the card copy / duration estimate,
            # captured from the first (primary) lift so the values shown aren't
            # the loop-leaked ones from the last accessory.
            desc_sets = desc_reps = desc_rpe = None
            for slot_key, ex_name in slot_picks:
                ex = library.get(ex_name, {})
                pattern = ex.get("movement_pattern", "push")
                rest_s  = _rest_seconds(pattern, tier)
                scheme  = archetype.schemes.get(slot_key) if archetype else None

                # Use per-exercise progression stage for accurate prescription
                strength_rec = strength_records.get(ex_name)
                ex_stage = strength_rec.get("progression_stage", stage) if strength_rec else stage

                # DUP: session index mod 3 determines rep range for that day.
                # Weekly-undulating keys on the week WITHIN the block so the same
                # (block-stable) main lift walks up %1RM across the 3 loading
                # weeks and into the deload — a coherent progression the athlete
                # can feel, rather than a scheme that reshuffles every week.
                dup_session_idx = session_counter % 3 if ex_stage == "dup" else 0
                presc = _periodization_prescription(
                    progression_stage=ex_stage,
                    week_in_cycle=week_in_block if ex_stage == "weekly_undulating" else dup_session_idx,
                    endurance=endurance,
                )

                sets_count = _TIER_SETS.get(tier, 3)
                reps       = presc["reps"]
                rpe_target = presc["rpe_target"]
                pct_1rm    = presc["pct_1rm"]
                tempo      = None

                # Archetype scheme override for this slot (e.g. a 5×5 main hinge,
                # a prescribed tempo/rest). Only the keys present are overridden;
                # the rest keep the periodization defaults above.
                if scheme:
                    sets_count = scheme.get("sets", sets_count)
                    reps       = scheme.get("reps", reps)
                    rpe_target = scheme.get("rpe", rpe_target)
                    pct_1rm    = scheme.get("pct_1rm", pct_1rm)
                    rest_s     = scheme.get("rest_seconds", rest_s)
                    tempo      = scheme.get("tempo")

                # Deload: cut volume and ease intensity so the week absorbs the
                # prior block's fatigue (Zourdos 2019, Schoenfeld 2019). Applied
                # here (not in the prescription) so it composes with every stage.
                if is_deload:
                    sets_count = max(1, sets_count // 2)
                    pct_1rm    = round(pct_1rm * 0.85, 3)
                    rpe_target = min(rpe_target, 6)

                if desc_sets is None:
                    desc_sets, desc_reps, desc_rpe = sets_count, reps, rpe_target

                inj_modifier = _injury_load_modifier(ex, active_injuries)
                ex_equip = ex.get("equipment", ["bodyweight"])

                if strength_rec and strength_rec.get("estimated_1rm_kg"):
                    base_1rm = strength_rec["estimated_1rm_kg"]
                    weight   = training_weight(base_1rm * inj_modifier, pct_1rm)
                    if weight < 0.1:
                        weight = 0.0
                elif strength_rec and strength_rec.get("last_weight_kg"):
                    weight = training_weight(
                        strength_rec["last_weight_kg"] * inj_modifier, pct_1rm
                    )
                else:
                    weight = conservative_starting_weight(
                        pattern, ex.get("is_compound", True), ex_equip
                    )
                    weight *= inj_modifier * exp_weight_factor

                # Round to realistic gym weights based on the exercise's equipment type
                if weight > 0:
                    weight = round_weight_for_equipment(weight, ex_equip, units=units)

                step = {
                    "type":             "strength_exercise",
                    "name":             ex_name,
                    "garmin_category":  ex.get("garmin_category"),
                    "garmin_subtype":   ex.get("garmin_subtype"),
                    "sets":             sets_count,
                    "reps":             reps,
                    "weight_kg":        weight,
                    "target_rpe":       rpe_target,
                    "rest_seconds":     rest_s,
                    "primary_muscles":  ex.get("primary_muscles", []),
                    "movement_pattern": pattern,
                    "cues":             (ex.get("cues") or [])[:3],
                    "phase":            "main",
                }
                if tempo:
                    step["tempo"] = tempo
                if slot_key in superset_of:
                    step["superset_group"] = superset_of[slot_key]
                steps.append(step)

            # Plyometric finisher on leg-focused days for running/MTB — power
            # transfers to gait/pedal stroke — and alpine skiing, where the
            # turn is a rebound. Never on upper or deload days.
            if (sport_family in ("running", "mountain_biking", "alpine_skiing")
                    and split_key in LEG_SPLITS and not is_deload):
                plyo = "Box Jump" if "barbell" in equipment or "dumbbell" in equipment else "Squat Jump"
                if plyo in library and _exercise_is_safe(library[plyo], active_injuries):
                    plyo_ex = library[plyo]
                    steps.append({
                        "type":             "strength_exercise",
                        "name":             plyo,
                        "garmin_category":  plyo_ex.get("garmin_category"),
                        "garmin_subtype":   plyo_ex.get("garmin_subtype"),
                        "sets":             3,
                        "reps":             6,
                        "weight_kg":        0.0,
                        "target_rpe":       8,
                        "rest_seconds":     90,
                        "primary_muscles":  plyo_ex.get("primary_muscles", []),
                        "movement_pattern": "plyometric",
                        "cues":             (plyo_ex.get("cues") or [])[:3],
                        "phase":            "finisher",
                    })

            # No stretches here: a strength session is lifts only. Stretching
            # is its own workout — the flow placed on the same day
            # (api/training_plan/injectors.inject_stretch_flows_with) and the
            # weekly mobility session below. A warm-up of library stretches
            # used to be put in front of the lifts.

            # ── Build workout title and description ───────────────────────────
            split_labels = {
                "ppl_push":        "Push Day",
                "ppl_pull":        "Pull Day",
                "ppl_legs":        "Leg Day",
                "upper_a":         "Upper Body",
                "upper_b":         "Upper Body",
                "lower_a":         "Lower Body",
                "lower_b":         "Lower Body",
                "full_body":       "Full Body",
                "supp_lower":      "Lower Body",
                "supp_upper_core": "Upper Body & Core",
            }
            split_label = split_labels.get(split_key, "Strength")
            deload_tag  = " — Deload" if is_deload else ""

            # An archetype names and flavours the session; otherwise fall back to
            # the generic split label. The coach-voice periodization facts are
            # appended after the archetype's intent line.
            base_desc = _build_description(exercises, desc_sets, desc_reps, desc_rpe,
                                           is_deload, stage, sport_family,
                                           week_in_block=week_in_block)
            if archetype:
                title = f"{archetype.name}{deload_tag}"
                intent = archetype.intent or archetype.tagline
                description = f"{intent} {base_desc}".strip()
            else:
                title = f"{split_label} Workout{deload_tag}"
                description = base_desc

            duration = _session_duration_minutes(exercises, library, desc_sets, desc_reps,
                                                 tier=tier, max_minutes=session_max_minutes)

            workout = {
                "scheduled_date":   session_date,
                "sport":            "strength_training",
                "workout_type":     "strength",
                "title":            title,
                "description":      description,
                "duration_minutes": duration,
                "distance_meters":  None,
                "steps":            steps,
            }
            if archetype and archetype.cooldown_theme:
                # Consumed by the post-workout flow to theme the cooldown.
                workout["cooldown_theme"] = archetype.cooldown_theme
            workouts.append(workout)

            session_counter     += 1
            week_sessions_placed += 1

        # ── Weekly mobility session ───────────────────────────────────────────
        # 1× per week standalone mobility; post-workout mobility is added in API
        # layer. Skipped when no stretch pool was supplied (e.g. library empty).
        if include_mobility and week_sessions_placed > 0 and stretch_candidates:
            # Collect dates used by strength sessions this week to avoid doubling up
            strength_dates_this_week = {
                w["scheduled_date"] for w in workouts
                if w.get("workout_type") == "strength"
                and week_start <= w["scheduled_date"] < week_start + timedelta(days=7)
            }
            mobility_date = _find_mobility_day(
                week_start, existing_by_date, today, race_date,
                exclude_dates=strength_dates_this_week,
            )
            mob_steps = _mobility_steps(
                sport_family, stretch_candidates,
                week_num=week_num, regen_salt=regen_salt,
            )
            if mobility_date and mob_steps:
                workouts.append({
                    "scheduled_date":   mobility_date,
                    "sport":            "strength_training",
                    "workout_type":     "mobility",
                    "title":            "Mobility & Recovery",
                    "description":      _mobility_description(sport_family),
                    "duration_minutes": 25,
                    "distance_meters":  None,
                    "steps":            mob_steps,
                })

    return workouts


def _find_mobility_day(
    week_start: date,
    existing_by_date: dict,
    today: date,
    race_date: date | None,
    exclude_dates: set | None = None,
) -> date | None:
    """Find a good day for the weekly standalone mobility session.
    Prefers the day after a hard workout; avoids days already used for strength."""
    if exclude_dates is None:
        exclude_dates = set()

    _HARD_TYPES_MOB = frozenset(("intervals", "threshold", "long", "long_run",
                                 "tempo", "fartlek", "race_pace"))

    def _is_usable(d: date) -> bool:
        return (d >= today
                and (race_date is None or d < race_date)
                and d not in exclude_dates)

    # Prefer the day after a hard workout (active recovery effect)
    for offset in range(7):
        d = week_start + timedelta(days=offset)
        if not _is_usable(d):
            continue
        day_before = existing_by_date.get(d - timedelta(days=1), [])
        if any(wt in _HARD_TYPES_MOB for wt in day_before):
            return d

    # Fallback: prefer days with no cardio scheduled (Wed/Fri/Sat priority)
    for offset in [2, 4, 5, 6, 1, 3, 0]:
        d = week_start + timedelta(days=offset)
        if not _is_usable(d):
            continue
        day_types = existing_by_date.get(d, [])
        if not day_types:  # fully empty day — perfect
            return d

    # Last resort: any usable day
    for offset in range(7):
        d = week_start + timedelta(days=offset)
        if _is_usable(d):
            return d

    return None


def _build_description(
    exercises: list[str],
    sets: int,
    reps: int,
    rpe: int,
    is_deload: bool,
    stage: str,
    sport_family: str,
    week_in_block: int | None = None,
) -> str:
    """
    Generate a concise coach-voice description for the workout card.
    """
    rpe_desc = {5: "very easy", 6: "easy", 7: "moderate", 8: "hard", 9: "very hard"}.get(rpe, "moderate")
    # Position within the 4-week block (3 loading weeks + deload) so the athlete
    # sees the progression narrative on the same key lifts.
    week_note = ""
    if week_in_block is not None and not is_deload:
        week_note = f"Week {week_in_block + 1} of 4 — same key lifts as last week, add load. "
    stage_note = {
        "linear":            "Adding weight each session as you build strength.",
        "weekly_undulating": "Volume and intensity vary week to week for optimal adaptation.",
        "dup":               "Daily rep-range variation maximizes neural and muscular adaptation.",
    }.get(stage, "")

    sport_note = {
        "running":      "Targeted to improve running economy and injury resilience.",
        "cycling":      "Squat and single-leg work proven to improve cycling power and efficiency.",
        "climbing":     "Pulling strength and finger endurance for climbing performance.",
        "paddling":     "Horizontal pulling and rotational power for paddle efficiency.",
        "mountain_biking": "Heavy compound movements to improve sprint power and trail control.",
        "hiking":       "Single-leg strength and hip stability for demanding terrain.",
        "alpine_skiing": "Heavy squats and single-leg work for the forces of a turn; the plan's"
                         " eccentric and plyometric days build on them.",
        "nordic_skiing": "Hinge, single-leg and pulling strength for poling power and a stable kick.",
    }.get(sport_family, "")

    ex_list = ", ".join(exercises[:3])
    if len(exercises) > 3:
        ex_list += f", +{len(exercises) - 3} more"

    if is_deload:
        return (f"Deload week — {sets}×{reps} at {rpe_desc} effort. "
                f"Keep same weights, cut volume in half. Key lifts: {ex_list}. "
                "Recovery session to absorb the last 3 weeks of training.")
    return (f"{week_note}{sets}×{reps} at Perceived Exertion {rpe} ({rpe_desc}). {stage_note} "
            f"{sport_note} Focus: {ex_list}.")
