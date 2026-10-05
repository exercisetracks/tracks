# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Activity → planned-workout matching and fitness-fingerprint advancement.

When the watcher imports a new activity it calls ``match_activity_to_workout``,
which finds a planned workout on the same date, scores completion, advances the
sport-family fingerprint (effective weeks / VDOT), auto-updates FTP-LTHR from
field tests, and rolls per-exercise strength e1RM state forward.

``match_activity_to_workout`` is called from the watcher thread and never raises.
"""

import logging

from sqlalchemy import select
from sqlalchemy.orm import Session

# activity_local_date is re-exported: matching was its first user, and the
# fixture generator and tests import it from here.
from app.calculators.local_day import activity_local_date  # noqa: F401
from app.calculators.strength_plan import estimate_1rm
from app.calculators.training_plan import _sport_family, calculate_vdot
from app.models.activity import Activity, StrengthSet
from app.models.strength import UserExerciseStrength
from app.models.training_plan import (
    PlannedWorkout,
    UserFitnessFingerprint,
)
from app.models.user_settings import UserSettings
from app.models.workout import UserWorkoutSession

log = logging.getLogger(__name__)


def _compute_completion_pct(workout: PlannedWorkout, activity: Activity) -> float:
    """Estimate what fraction of the planned workout was completed (0.0–1.0).

    For MTB activities, distance is unreliable (15 km of technical singletrack
    can cost more than 40 km of smooth gravel), so the score is duration-only.
    Other sports keep the duration + distance average.
    """
    if _sport_family(activity.sport or "") == "mountain_biking":
        if workout.duration_minutes and activity.duration_seconds:
            return round(min(1.0, (activity.duration_seconds / 60) / workout.duration_minutes), 3)
        return 0.0

    scores: list[float] = []
    if workout.distance_meters and activity.distance_meters:
        scores.append(min(1.0, activity.distance_meters / workout.distance_meters))
    if workout.duration_minutes and activity.duration_seconds:
        scores.append(min(1.0, (activity.duration_seconds / 60) / workout.duration_minutes))
    return round(sum(scores) / len(scores), 3) if scores else 0.0


def _update_fingerprint(
    db: Session,
    user_id: int,
    family: str,
    activity: Activity,
    workout: PlannedWorkout,
    completion_pct: float,
) -> None:
    """Advance effective_weeks in the fitness fingerprint based on completion."""
    fp = db.query(UserFitnessFingerprint).filter_by(
        user_id=user_id, sport_family=family
    ).first()
    if fp is None:
        fp = UserFitnessFingerprint(user_id=user_id, sport_family=family)
        db.add(fp)
        db.flush()

    fp.effective_weeks, fp.vdot, fp.sessions_completed = advance_fingerprint(
        fp.effective_weeks, fp.vdot, fp.sessions_completed, family,
        activity.distance_meters, activity.duration_seconds, workout.workout_type, completion_pct,
    )
    db.flush()


def advance_fingerprint(effective_weeks: float, vdot: float | None, sessions: int | None,
                        family: str, distance_meters, duration_seconds,
                        workout_type: str, completion_pct: float) -> tuple:
    """One matched activity's step of the fingerprint, with no database.

    Returns the new (effective_weeks, vdot, sessions_completed). Pure so the
    phone (com.tracks.core.plan.PlanAssembly.advanceFingerprint) can replay a
    user's matches and be held to the same answer.
    """
    # Advance proportional to completion; quality sessions earn a small bonus
    if completion_pct >= 0.5:
        quality_types = {"intervals", "tempo", "race_pace", "fartlek", "sweet_spot"}
        bonus = 0.15 if workout_type in quality_types else 0.0
        advance = min(1.0, completion_pct) * (1.0 + bonus)
        effective_weeks = round(effective_weeks + advance, 3)

    sessions = (sessions or 0) + 1

    # Track best VDOT for running (we record personal-best pace so it only ever rises)
    if family == "running" and distance_meters and duration_seconds:
        try:
            new_vdot = calculate_vdot(distance_meters, float(duration_seconds))
            if new_vdot > 20:
                vdot = max(vdot or 0.0, round(new_vdot, 2))
        except Exception:
            pass
    return effective_weeks, vdot, sessions


def field_test_updates(test_type: str, best_20min_watts: float | None, max_hr: int | None,
                       ftp_mode: str | None, threshold_hr_mode: str | None) -> dict:
    """What a completed field test sets: {"ftp_auto": W, "threshold_hr_auto": bpm}.

    Pure, so the phone (com.tracks.core.plan.Matching) can be held to it; see
    _apply_field_test_result for the reasoning behind each estimate.
    """
    out: dict = {}
    if test_type != "ftp20":
        return out
    if best_20min_watts and ftp_mode == "auto":
        new_ftp = int(round(best_20min_watts * 0.95))
        # Sanity range: 80–600 W for a human cyclist
        if 80 <= new_ftp <= 600:
            out["ftp_auto"] = new_ftp
    if max_hr and threshold_hr_mode == "auto":
        est_lthr = int(round(max_hr * 0.93))
        # Sanity range: 110–195 bpm
        if 110 <= est_lthr <= 195:
            out["threshold_hr_auto"] = est_lthr
    return out


def apply_field_tests(db: Session, user_id: int, us: UserSettings) -> None:
    """Replay every completed field test over the auto thresholds.

    Tests are applied oldest activity first, each overwriting only the keys it
    sets, so the latest test wins per value. This is a replay rather than a
    one-off write at match time because the history recompute
    (calculators.user_stats.recalculate_auto_values) rewrites the same columns:
    with one-off writes, whichever ran last won, so a server restart quietly
    undid a field test. Now the rule is the same whenever it runs, and the
    phone (com.tracks.core.local.LocalMatchEffects.fieldTests) applies it
    identically: manual, else the latest field test, else full history.

    For a 20-min FTP test:
      Power path:  best rolling 20-min power × 0.95 → ftp_auto
      HR path:     ~93% of activity's max_heart_rate → threshold_hr_auto
                   (max HR during a 20-min all-out test approximates true
                    HRmax closely; LTHR sits ~7% below — Friel 2018; the
                    average would underestimate, as it includes the warmup
                    and cooldown bracketing the test)
    pmax5 and rsa tests are informational only. Gated on ftp_mode /
    threshold_hr_mode == "auto", so manual values are never overridden.
    """
    from app.models.activity import PowerBest

    rows = (
        db.query(PlannedWorkout, Activity)
        .join(Activity, Activity.id == PlannedWorkout.completed_activity_id)
        .filter(
            PlannedWorkout.user_id == user_id,
            PlannedWorkout.is_complete.is_(True),
            PlannedWorkout.workout_type.like("field_test:%"),
        )
        .all()
    )
    # Sorted in Python so ties break on the uid byte for byte, as on the phone.
    rows.sort(key=lambda r: (r[1].started_at.isoformat() if r[1].started_at else "", r[0].uid or ""))
    for workout, activity in rows:
        test_type = workout.workout_type.split(":", 1)[1]
        best_pwr = None
        if test_type == "ftp20":
            best_pwr = (
                db.query(PowerBest.avg_watts)
                .filter(PowerBest.activity_id == activity.id,
                        PowerBest.duration_seconds == 1200)
                .scalar()
            )
        updates = field_test_updates(test_type, best_pwr, activity.max_heart_rate,
                                     us.ftp_mode, us.threshold_hr_mode)
        for key, value in updates.items():
            setattr(us, key, value)
        if updates:
            log.info("Field test %s for user %d set %s", test_type, user_id, updates)


def _apply_field_test_result(db: Session, user_id: int, workout: PlannedWorkout,
                             activity: Activity) -> None:
    """A field test just matched: replay all of them (see apply_field_tests)."""
    if not workout.workout_type.startswith("field_test:"):
        return
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    if us is None:
        return
    db.flush()
    apply_field_tests(db, user_id, us)
    db.flush()


def strength_fingerprint_updates(sets: list[dict], existing: dict[str, dict]) -> dict[str, dict]:
    """Per-exercise strength state after one session's active sets.

    `sets` are {"exercise_name", "weight_kg", "repetitions"} in set order;
    `existing` maps exercise name to its current {"estimated_1rm_kg",
    "sessions_completed"} (absent = never trained). Returns the new
    {"estimated_1rm_kg", "last_weight_kg", "last_reps",
    "last_session_volume_kg", "sessions_completed", "progression_stage"} for
    every exercise in the session. Pure, so the phone can be held to it.
    """
    by_exercise: dict[str, list[dict]] = {}
    for st in sets:
        if not st.get("exercise_name"):
            continue
        by_exercise.setdefault(st["exercise_name"], []).append(st)

    out: dict[str, dict] = {}
    for ex_name, ex_sets in by_exercise.items():
        best_1rm: float | None = None
        last_set = ex_sets[-1]
        session_volume = sum(
            (st["weight_kg"] or 0) * (st["repetitions"] or 0)
            for st in ex_sets
        )
        for st in ex_sets:
            if st["weight_kg"] and st["repetitions"] and 1 <= st["repetitions"] <= 10:
                e1rm = estimate_1rm(st["weight_kg"], st["repetitions"])
                if e1rm and (best_1rm is None or e1rm > best_1rm):
                    best_1rm = e1rm
        prev = existing.get(ex_name) or {}
        est = prev.get("estimated_1rm_kg")
        if best_1rm and (est is None or best_1rm > est):
            est = round(best_1rm, 1)
        total = (prev.get("sessions_completed") or 0) + 1
        # Advance progression stage based on total sessions
        if total >= 100:
            stage = "dup"
        elif total >= 20:
            stage = "weekly_undulating"
        else:
            stage = "linear"
        out[ex_name] = {
            "estimated_1rm_kg": est,
            "last_weight_kg": last_set["weight_kg"],
            "last_reps": last_set["repetitions"],
            "last_session_volume_kg": round(session_volume, 1),
            "sessions_completed": total,
            "progression_stage": stage,
        }
    return out


def _update_strength_fingerprint(db: Session, user_id: int, activity_id: int) -> None:
    """
    Update per-exercise e1RM and volume state from a freshly-imported
    strength activity.  Called from match_activity_to_workout whenever the
    activity has associated strength_sets rows.

    Algorithm:
      1. Group sets by exercise_name (active sets only).
      2. For each exercise, compute e1RM for each set (Epley+Brzycki+Wathan avg).
      3. Update UserExerciseStrength: keep the maximum e1RM observed this session.
      4. Advance sessions_completed and update progression_stage.
    """
    sets = (
        db.query(StrengthSet)
        .filter(
            StrengthSet.activity_id == activity_id,
            StrengthSet.set_type == "active",
            StrengthSet.weight_kg.isnot(None),
            StrengthSet.repetitions.isnot(None),
        )
        .all()
    )
    if not sets:
        return

    plain = [{"exercise_name": st.exercise_name, "weight_kg": st.weight_kg,
              "repetitions": st.repetitions} for st in sets]
    names = {p["exercise_name"] for p in plain if p["exercise_name"]}
    recs = {
        r.exercise_name: r for r in db.query(UserExerciseStrength).filter(
            UserExerciseStrength.user_id == user_id,
            UserExerciseStrength.exercise_name.in_(names),
        )
    }
    existing = {n: {"estimated_1rm_kg": r.estimated_1rm_kg, "sessions_completed": r.sessions_completed}
                for n, r in recs.items()}
    by_exercise = strength_fingerprint_updates(plain, existing)
    for ex_name, new in by_exercise.items():
        rec = recs.get(ex_name)
        if rec is None:
            rec = UserExerciseStrength(user_id=user_id, exercise_name=ex_name)
            db.add(rec)
        for key, value in new.items():
            setattr(rec, key, value)
    db.flush()
    log.info("Updated strength fingerprint for %d exercises (activity %d user %d)",
             len(by_exercise), activity_id, user_id)


_NEVER_MATCHED = ("rest", "race")


def pick_workout(candidates, act_date, act_family: str):
    """The planned workout an activity completes, or None.

    Same day, same sport family (a run never ticks off a strength session),
    not rest or race, not already matched. Hand-added workouts (no plan) are
    eligible like generated ones — they are the user's plan too.

    `candidates` must already be in (scheduled_date, uid) order, uids compared
    as plain strings, and the first eligible one wins. The order is explicit because the result is written to
    synced fields: every device runs this on the same rows, and the answers
    only agree if the pick cannot depend on how a database happened to return
    them. com.tracks.core.plan.Matching.pickWorkout is the phone's copy.
    """
    for w in candidates:
        if (w.scheduled_date == act_date
                and w.workout_type not in _NEVER_MATCHED
                and w.completed_activity_id is None
                and _sport_family(w.sport or "") == act_family):
            return w
    return None


def match_activity_to_workout(db: Session, activity_id: int, user_id: int) -> None:
    """
    Match a newly-imported activity to a planned workout on the same date,
    compute its completion %, and advance the user's fitness fingerprint.

    Called from the watcher thread — must never raise (errors are logged only).
    """
    try:
        activity = db.query(Activity).filter_by(id=activity_id, user_id=user_id).first()
        if not activity or not activity.started_at:
            return

        us = db.query(UserSettings).filter_by(user_id=user_id).first()
        act_date   = activity_local_date(activity.started_at, us.timezone if us else None)
        act_family = _sport_family(activity.sport or "")

        candidates = (
            db.query(PlannedWorkout)
            .filter(
                PlannedWorkout.user_id == user_id,
                PlannedWorkout.scheduled_date == act_date,
                PlannedWorkout.completed_activity_id.is_(None),
            )
            .all()
        )
        # Sorted here, not by ORDER BY: a text collation may order uids other
        # than bytewise (glibc ignores the dashes), and the phone compares bytes.
        candidates.sort(key=lambda w: (w.scheduled_date, str(w.uid)))
        workout = pick_workout(candidates, act_date, act_family)
        if workout is None:
            return

        pct = _compute_completion_pct(workout, activity)
        workout.completed_activity_id = activity.id
        workout.completion_pct        = pct
        workout.is_complete           = pct >= 0.8
        db.flush()

        _update_fingerprint(db, user_id, act_family, activity, workout, pct)
        # Field tests can auto-update FTP / LTHR (only when completed)
        if workout.is_complete:
            _apply_field_test_result(db, user_id, workout, activity)
        # Strength sets: update e1RM / volume fingerprint when present — unless
        # the user already logged this planned workout through the in-app
        # session runner (which fed the same progression update). Otherwise the
        # watch's FIT import for the same session would double-count it.
        already_logged = db.execute(
            select(UserWorkoutSession.id).where(
                UserWorkoutSession.planned_workout_id == workout.id,
            )
        ).first()
        if not already_logged:
            _update_strength_fingerprint(db, user_id, activity_id)
        db.commit()
        log.info(
            "Matched activity %d → workout %d (%.0f%% complete) for user %d",
            activity_id, workout.id, pct * 100, user_id,
        )
    except Exception:
        log.exception("match_activity_to_workout failed for activity %d user %d", activity_id, user_id)
        db.rollback()
