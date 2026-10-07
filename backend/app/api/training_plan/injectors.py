# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Workout-list builders that augment the endurance plan before persistence.

Each function takes the list of workout dicts produced by the endurance/strength
calculators and returns it with extra sessions merged in (sorted by date):
  - ``_inject_strength_workouts``  strength + custom workouts for include_strength goals
  - ``_inject_stretch_flows``      a recovery stretch flow alongside each workout
  - ``_inject_field_tests``        FTP/Pmax field tests at strategic plan weeks (MTB/cycling)

These run inside ``generate_plan`` and ``_regenerate_future_workouts`` so the
explicit and background plan paths produce identical output.
"""

import logging
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.calculators.local_day import user_today
from app.calculators.strength_plan import generate_strength_workouts
from app.calculators.strength_plan.leveling import effective_experience, infer_experience_suggestion
from app.calculators.training_plan import _sport_family
from app.models.activity import User
from app.models.strength import (
    UserCustomExercise,
    UserExerciseStrength,
    UserExercisePreference,
)
from app.models.health import Injury
from app.models.training_plan import PlannedWorkout
from app.models.user_settings import UserSettings
from app.models.workout import UserWorkout, UserWorkoutExercise, UserWorkoutSession
from app.services.crypto_context import MissingDecryptionKey

from .helpers import _goal_anchor

log = logging.getLogger(__name__)


def _refresh_experience_suggestion(db: Session, user_id: int, us: UserSettings) -> None:
    """Compute a conservative history-derived experience-level suggestion and
    store it on settings (never auto-applied). Runs on plan regeneration.

    Respects dismissal: if the user dismissed a suggestion, it isn't resurfaced
    unless the *new* suggestion is for a different level.
    """
    from datetime import datetime, timezone

    now = datetime.now(timezone.utc)
    cutoff = now - timedelta(weeks=12)

    sessions = db.execute(
        select(UserWorkoutSession.completed_at)
        .where(UserWorkoutSession.user_id == user_id,
               UserWorkoutSession.completed_at >= cutoff)
    ).scalars().all()
    last = db.execute(
        select(UserWorkoutSession.completed_at)
        .where(UserWorkoutSession.user_id == user_id)
        .order_by(UserWorkoutSession.completed_at.desc())
    ).scalars().first()

    def _weeks_since(dt):
        if not dt:
            return 999
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int((now - dt).days // 7)

    signals = {
        "sessions_12wk": len(sessions),
        "e1rm_trend": "flat",   # conservative default; upgrade needs != "falling"
        "weeks_since_last": _weeks_since(last),
    }
    suggestion = infer_experience_suggestion(
        effective_experience(us.strength_experience, us.activity_frequency), signals)

    if not suggestion:
        # Nothing to suggest — clear any stale, non-dismissed note.
        if us.experience_suggestion and not us.experience_suggestion_dismissed:
            us.experience_suggestion = None
            us.experience_suggestion_reason = None
        return

    new_level = suggestion["suggested"]
    # A brand-new suggestion (or one for a different level than the dismissed
    # one) is surfaced fresh; a re-computed identical dismissed one stays hidden.
    if us.experience_suggestion == new_level and us.experience_suggestion_dismissed:
        return
    if us.experience_suggestion != new_level:
        us.experience_suggestion_dismissed = False
    us.experience_suggestion = new_level
    us.experience_suggestion_reason = suggestion["reason"]


# ── Strength data lookups ────────────────────────────────────────────────────

def _get_active_injuries(db: Session, user_id: int) -> list:
    """Return Injury rows whose end_date is null or in the future.

    Injury.body_part/injury_type/notes are encrypted — loading any row from
    this table requires an active decryption key, which isn't guaranteed
    here (plan generation can run outside a request, e.g. triggered by
    background ingestion, with no live session). Degrade to "no known
    active injuries this run" rather than crash — this means injury-based
    exercise exclusion/load-reduction is silently skipped for that run, so
    it's logged, not swallowed silently.
    """
    today = user_today(db, user_id)
    try:
        return (
            db.query(Injury)
            .filter(
                Injury.user_id == user_id,
                (Injury.end_date.is_(None)) | (Injury.end_date >= today),
            )
            .all()
        )
    except MissingDecryptionKey:
        log.warning(
            "No decryption key available — skipping injury-based plan adaptation "
            "for user %s this run (will apply again once they're logged in)",
            user_id,
        )
        return []


def _build_exercise_library(db: Session) -> dict:
    """Return exercise library as {name: dict} for the strength plan calculator.

    Uses an in-memory cache populated at startup — the exercise_library table
    is read-only at runtime so there is no staleness risk.
    """
    from app.main import get_exercise_library_cache
    return get_exercise_library_cache()


def _build_strength_records(db: Session, user_id: int) -> dict:
    """Return UserExerciseStrength as {exercise_name: dict} for the strength plan calculator."""
    rows = db.query(UserExerciseStrength).filter_by(user_id=user_id).all()
    return {
        r.exercise_name: {
            "estimated_1rm_kg":       r.estimated_1rm_kg,
            "last_weight_kg":         r.last_weight_kg,
            "last_reps":              r.last_reps,
            "last_session_volume_kg": r.last_session_volume_kg,
            "sessions_completed":     r.sessions_completed,
            "progression_stage":      r.progression_stage,
        }
        for r in rows
    }


def _total_strength_sessions(db: Session, user_id: int) -> int:
    """Return the maximum sessions_completed across all exercises (overall training age)."""
    rows = db.query(UserExerciseStrength).filter_by(user_id=user_id).all()
    if not rows:
        return 0
    return max((r.sessions_completed or 0) for r in rows)


def _build_custom_exercise_list(db: Session, workout_id: int) -> list[dict]:
    """Fetch a custom workout's exercises with progressive overload weights."""
    rows = db.execute(
        select(UserWorkoutExercise)
        .where(UserWorkoutExercise.workout_id == workout_id)
        .order_by(UserWorkoutExercise.order_index)
    ).scalars().all()
    return [
        {
            "name": r.exercise_name,
            "sets": r.target_sets,
            # "type", "name", "sets" and "reps" are the keys the strength FIT
            # encoder reads for a work step (calculators/fit_workout.py). Reps
            # are one number: a watch step has a single reps target, which is
            # why the builder no longer offers a range.
            "type": "rest" if r.item_kind == "rest" else "strength_exercise",
            "reps": r.target_reps,
            "rir_target": r.rir_target,
            "rest_seconds": r.rest_seconds,
            "weight_method": r.weight_method,
            "weight_value": r.weight_value,
            "item_kind": r.item_kind,
            "group_uid": r.group_uid,
            "group_kind": r.group_kind,
            "group_rounds": r.group_rounds,
            "group_rest_seconds": r.group_rest_seconds,
        }
        for r in rows
    ]


# ── Injectors ────────────────────────────────────────────────────────────────

def _inject_strength_workouts(
    db: Session,
    goal,
    user_id: int,
    existing_workout_dicts: list[dict],
    regen_salt: str = "",
) -> list[dict]:
    """
    Generate strength and mobility sessions for a goal with include_strength=True
    and merge them into the existing workout list, sorted by date.

    Returns the merged list.  Skips injection if goal.include_strength is False
    or if the exercise library is empty (migration not yet run).
    """
    if not getattr(goal, "include_strength", False):
        return existing_workout_dicts

    # Copy the shared cache before merging user customs — mutating the cache
    # itself would leak one user's custom exercises into every other user's plan.
    library = dict(_build_exercise_library(db))
    if not library:
        return existing_workout_dicts

    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    equipment = (us.equipment_available if us and us.equipment_available
                 else ["bodyweight", "dumbbell"])
    units    = (us.units if us and us.units else "metric")
    experience = effective_experience(us.strength_experience, us.activity_frequency) if us else None

    # Refresh the history-derived experience "coach note" (suggestion only).
    if us is not None:
        try:
            _refresh_experience_suggestion(db, user_id, us)
        except Exception:
            pass  # a suggestion is non-essential; never block plan generation

    strength_records = _build_strength_records(db, user_id)
    active_injuries  = _get_active_injuries(db, user_id)
    total_sessions   = _total_strength_sessions(db, user_id)

    sport_family = _sport_family((goal.event_sport or "running").lower())

    # Load user exercise preferences
    prefs = db.query(UserExercisePreference).filter_by(user_id=user_id).all()
    preferred_exercises = {p.exercise_name for p in prefs if p.preference == "preferred"}
    excluded_exercises  = {p.exercise_name for p in prefs if p.preference == "excluded"}

    # Names that the user has marked as NOT animating on their primary watch.
    # The planner skips these even though has_animation=True in the library,
    # because user-observed reality > manifest-membership.
    confirmed_no_exercises: set[str] = set()
    user_row = db.query(User).filter(User.id == user_id).first()
    prod_id = user_row.primary_device_product_id if user_row else None
    if prod_id is not None:
        from app.calculators.garmin_animations import build_confirmation_lookup
        ac_lookup = build_confirmation_lookup(db, prod_id, user_id)
        for name, ex in library.items():
            cat = ex.get("garmin_category")
            sub = ex.get("garmin_subtype")
            if cat is None or sub is None:
                continue
            entry = ac_lookup.get((cat, sub))
            if entry is None:
                continue
            # Own confirmation overrides aggregate; else fall through to
            # any other-user "no" as long as no other-user "yes" countered.
            if entry["own"] is False:
                confirmed_no_exercises.add(name)
            elif entry["own"] is None and entry["others_no"] and not entry["others_yes"]:
                confirmed_no_exercises.add(name)

    # Merge custom exercises into library (tagged so _select_exercises can find them)
    customs = db.query(UserCustomExercise).filter_by(user_id=user_id).all()
    for ce in customs:
        library[ce.name] = {
            "name": ce.name,
            "primary_muscles": ce.primary_muscles or [],
            "secondary_muscles": ce.secondary_muscles or [],
            "equipment": ce.equipment or ["bodyweight"],
            "movement_pattern": ce.movement_pattern or "push",
            "is_compound": ce.is_compound,
            "difficulty": ce.difficulty,
            "garmin_category": ce.garmin_category,
            "garmin_subtype": ce.garmin_subtype,
            "has_animation": bool(ce.has_animation),
            "_is_custom": True,
        }

    # Eligible stretch pool (device- and preference-aware) for the weekly
    # standalone mobility session — same source as the post-workout flows.
    from app.api.flexibility.generation import build_stretch_candidates
    stretch_candidates = build_stretch_candidates(db, user_id, prod_id)

    strength_dicts = generate_strength_workouts(
        goal=goal,
        sport_family=sport_family,
        today=user_today(db, user_id),
        existing_workouts=existing_workout_dicts,
        equipment=equipment,
        library=library,
        strength_records=strength_records,
        active_injuries=active_injuries,
        sessions_count=total_sessions,
        units=units,
        preferred_exercises=preferred_exercises or None,
        excluded_exercises=excluded_exercises or None,
        session_max_minutes=getattr(goal, "strength_session_minutes", None),
        regen_salt=regen_salt,
        confirmed_no_exercises=confirmed_no_exercises or None,
        stretch_candidates=stretch_candidates,
        experience=experience,
        # The goal's first stamp, as the phone anchors it (LocalPlanning.planStart);
        # `created_at` is when this server first saw the row, so a goal made on
        # a phone put its strength blocks in different weeks here and there.
        anchor_date=_goal_anchor(goal),
    )

    # ── Inject user's custom workouts into the plan ──────────────────────
    custom_workouts = (
        db.query(UserWorkout)
        .filter_by(user_id=user_id, include_in_plan=True)
        .all()
    )
    customs: list[tuple] = []
    recently_used: set = set()
    if custom_workouts:
        # Don't reuse a custom workout that was scheduled within the last 14 days
        cutoff = user_today(db, user_id) - timedelta(days=14)
        recently_used = set(
            row[0] for row in db.execute(
                select(PlannedWorkout.custom_workout_id).where(
                    PlannedWorkout.user_id == user_id,
                    PlannedWorkout.custom_workout_id.isnot(None),
                    PlannedWorkout.scheduled_date >= cutoff,
                )
            ).all()
        )
        for cw in custom_workouts:
            cw_muscles = set()
            for we in db.query(UserWorkoutExercise).filter_by(workout_id=cw.id).all():
                lib_ex = library.get(we.exercise_name)
                if lib_ex:
                    cw_muscles.update(lib_ex.get("primary_muscles", []))
                    cw_muscles.update(lib_ex.get("secondary_muscles", []))
            customs.append((cw.id, cw.name, cw_muscles,
                            lambda cid=cw.id: _build_custom_exercise_list(db, cid)))

    return attach_custom_workouts(existing_workout_dicts, strength_dicts, customs, recently_used)


def attach_custom_workouts(existing_workout_dicts: list[dict], strength_dicts: list[dict],
                           customs: list[tuple], recently_used: set) -> list[dict]:
    """The custom-workout pass of `_inject_strength_workouts`, with no database.

    `customs` holds (id, name, muscles, exercises) per workout the user put in
    the plan — `exercises` a list, or a zero-argument callable returning one,
    so the server only queries it for a workout that is actually attached.
    Pure so the phone (com.tracks.core.plan.PlanAssembly) can be held to it.
    """
    for cw_id, cw_name, cw_muscles, exercises in customs:
        if cw_id in recently_used:
            continue
        if not cw_muscles:
            continue

        best_sd = None
        best_overlap = 0
        for sd in strength_dicts:
            if sd.get("custom_workout_id"):
                continue
            sd_muscles = set(sd.get("primary_muscles", [])).union(sd.get("secondary_muscles", []))
            overlap = len(cw_muscles & sd_muscles)
            if overlap > best_overlap:
                best_overlap = overlap
                best_sd = sd

        if best_sd and best_overlap >= 2:
            best_sd["type"] = "custom_strength"
            best_sd["custom_workout_id"] = cw_id
            best_sd["custom_workout_name"] = cw_name
            best_sd["custom_exercises"] = exercises() if callable(exercises) else exercises

    merged = existing_workout_dicts + strength_dicts
    merged.sort(key=lambda w: (
        w["scheduled_date"] if isinstance(w, dict) else w.scheduled_date
    ))
    return merged


def _inject_stretch_flows(db: Session, workout_dicts: list[dict], user_id: int,
                          regen_salt: str = "") -> list[dict]:
    """
    During plan generation, inject a complementary stretch flow alongside each
    endurance/strength workout.  This means the stretch is preloaded onto the
    watch at the same time as the workout — the user sees both when they sync
    once a week and doesn't need a second sync for post-workout recovery.

    Rules:
      - One stretch flow per workout date (skip if one already exists)
      - Only inject for workouts with workout_type in endurance/strength families
      - Skip rest/race/mobility/flexibility workout types
      - Use the workout's sport to select target muscle groups
    """
    from app.api.flexibility.generation import (
        build_stretch_candidates,
        generate_post_activity_stretch_flow,
    )

    # Build the eligible stretch pool and resolve the device once — not per
    # workout — then reuse across every day in the plan.
    prod_id = (
        db.query(User.primary_device_product_id)
        .filter(User.id == user_id).scalar()
    )
    candidates = build_stretch_candidates(db, user_id, prod_id)
    if not candidates:
        return workout_dicts

    def flow_for(sport, variety_key, is_strength, primary_muscles, cooldown_theme):
        return generate_post_activity_stretch_flow(
            sport=sport, db=db, user_id=user_id,
            regen_salt=regen_salt,
            primary_device_product_id=prod_id,
            variety_key=variety_key,
            is_strength=is_strength,
            primary_muscles=primary_muscles,
            candidates=candidates,
            cooldown_theme=cooldown_theme,
            return_meta=True,
        )

    return inject_stretch_flows_with(workout_dicts, flow_for)


def inject_stretch_flows_with(workout_dicts: list[dict], flow_for) -> list[dict]:
    """The placement half of `_inject_stretch_flows`, with no database.

    `flow_for(sport, variety_key, is_strength, primary_muscles, cooldown_theme)`
    is the stretch planner and returns its meta dict. Pure so the phone
    (com.tracks.core.plan.PlanAssembly) can be held to it.
    """
    existing_dates = {
        w["scheduled_date"] for w in workout_dicts
        if w.get("workout_type") in ("mobility", "flexibility")
    }
    skip_types = {"rest", "race", "mobility", "flexibility"}

    new_flows = []
    for w in workout_dicts:
        if w["workout_type"] in skip_types:
            continue
        d = w["scheduled_date"]
        if d in existing_dates:
            continue

        sport = w.get("sport", "running")
        # For strength sessions, stretch what was actually trained rather than
        # guessing from sport — pull the trained muscles off the workout steps.
        is_strength = (
            w.get("workout_type") in ("strength", "custom_strength")
            or w.get("sport") == "strength_training"
        )
        trained_muscles = None
        if is_strength:
            trained_muscles = sorted({
                m for s in w.get("steps", [])
                for m in (s.get("primary_muscles") or [])
            })

        meta = flow_for(str(sport).lower(), str(d), is_strength, trained_muscles,
                        w.get("cooldown_theme"))
        steps = meta["steps"]
        if not steps or len(steps) < 2:
            continue

        duration = sum(
            (s.get("duration_seconds", 60) * s.get("sets", 1) * (2 if s.get("each_side") else 1))
            for s in steps
        ) // 60 + 2

        # Prefer the flow archetype's name; fall back to the generic title.
        flow_name = meta.get("title")
        title = f"{flow_name} — {duration} min" if flow_name else f"Post-Workout Stretch — {duration} min"
        description = meta.get("tagline") or "Recovery stretch targeting muscles used in today's workout."

        flow = {
            "scheduled_date": d,
            "sport": "flexibility_training",
            "workout_type": "flexibility",
            "title": title,
            "description": description,
            "duration_minutes": duration,
            "distance_meters": None,
            "steps": steps,
        }
        new_flows.append(flow)
        existing_dates.add(d)

    workout_dicts.extend(new_flows)
    workout_dicts.sort(key=lambda w: w["scheduled_date"])
    return workout_dicts


def _inject_field_tests(workout_dicts: list[dict], goal, today: date) -> list[dict]:
    """
    Replace selected sessions with field-test workouts at strategic plan weeks.

    Schedule (MTB / cycling only — other sports skip):
      - First quality-day after week 2 → ftp20 (20-min FTP test)
      - Approximate start of build phase → pmax5 (5-min Pmax test)
      - Approximate mid-peak             → ftp20 (retest)

    Tests overwrite the existing planned workout on their scheduled day. The
    new step list is built lazily here by importing the generator's MTB
    field-test builder. Non-MTB cycling falls back to the same MTB ftp/pmax
    test definitions (they're sport-agnostic structures).
    """
    from app.calculators.training_plan import (
        _mtb_field_test,
        _workout_title,
        _duration_from_steps,
        _workout_description,
    )

    if not workout_dicts:
        return workout_dicts

    family = _sport_family((goal.event_sport or "").lower())
    if family not in {"mountain_biking", "cycling"}:
        return workout_dicts

    plan_start = workout_dicts[0]["scheduled_date"]
    race_date  = workout_dicts[-1]["scheduled_date"]
    total_days = (race_date - plan_start).days or 1

    # Test dates as fractions of plan length so they land in the right phase
    # regardless of plan length.
    target_dates = [
        plan_start + timedelta(days=int(total_days * 0.10)),  # early base
        plan_start + timedelta(days=int(total_days * 0.45)),  # start of build
        plan_start + timedelta(days=int(total_days * 0.75)),  # mid-peak
    ]
    test_types = ["ftp20", "pmax5", "ftp20"]

    sport = goal.event_sport or "mountain_biking"

    # Replace the nearest non-long, non-rest, non-test workout within ±4 days
    # of each target date.
    used_indices: set[int] = set()
    for target, test_type in zip(target_dates, test_types):
        best_idx = None
        best_dist = 999
        for i, w in enumerate(workout_dicts):
            if i in used_indices:
                continue
            if w["workout_type"] in ("long", "rest", "race"):
                continue
            if w["workout_type"].startswith("field_test"):
                continue
            d = abs((w["scheduled_date"] - target).days)
            if d < best_dist:
                best_dist = d
                best_idx = i
        if best_idx is None or best_dist > 4:
            continue

        steps = _mtb_field_test(test_type)
        # Use the canonical duration calculator so RSA tests (which only set
        # duration_sec_each) are accounted for correctly.
        duration = _duration_from_steps(steps, paces=None)
        wtype = f"field_test:{test_type}"
        workout_dicts[best_idx] = {
            "scheduled_date": workout_dicts[best_idx]["scheduled_date"],
            "sport": sport,
            "workout_type": wtype,
            "title": _workout_title(wtype, _sport_family(sport), None, duration),
            "description": _workout_description(steps),
            "duration_minutes": duration,
            "distance_meters": None,
            "steps": steps,
        }
        used_indices.add(best_idx)

    return workout_dicts
