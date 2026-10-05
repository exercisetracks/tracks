# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The strength fingerprint a matched session writes.

The pure rule is strength_fingerprint_updates (held to the phone by
spec/fixtures/matching.json); these check that the database wrapper around it
still reads and writes UserExerciseStrength the way it did before the split.
"""
from datetime import datetime, timezone

from app.api.training_plan.matching import _update_strength_fingerprint
from app.models.activity import Activity, StrengthSet
from app.models.strength import UserExerciseStrength


def _session(db, user, sets):
    a = Activity(user_id=user.id, sport="training", started_at=datetime.now(timezone.utc))
    db.add(a)
    db.flush()
    for i, (name, w, r) in enumerate(sets, 1):
        db.add(StrengthSet(activity_id=a.id, set_number=i, set_type="active",
                           exercise_name=name, weight_kg=w, repetitions=r))
    db.flush()
    return a.id


def test_a_first_session_creates_the_exercise_state(db, user):
    """Without the row, next week's prescription has nothing to progress from."""
    _update_strength_fingerprint(db, user.id, _session(db, user, [("Squat", 100.0, 5), ("Squat", 105.0, 3)]))
    rec = db.query(UserExerciseStrength).filter_by(user_id=user.id, exercise_name="Squat").one()
    assert rec.sessions_completed == 1
    assert rec.progression_stage == "linear"
    assert rec.last_weight_kg == 105.0 and rec.last_reps == 3
    assert rec.last_session_volume_kg == 815.0
    assert rec.estimated_1rm_kg is not None


def test_a_weaker_session_keeps_the_best_estimate_and_still_counts(db, user):
    """An off day must not lower the 1RM the plan loads from, but it is still a session."""
    _update_strength_fingerprint(db, user.id, _session(db, user, [("Squat", 120.0, 3)]))
    best = db.query(UserExerciseStrength).filter_by(user_id=user.id, exercise_name="Squat").one().estimated_1rm_kg
    _update_strength_fingerprint(db, user.id, _session(db, user, [("Squat", 60.0, 5)]))
    rec = db.query(UserExerciseStrength).filter_by(user_id=user.id, exercise_name="Squat").one()
    assert rec.estimated_1rm_kg == best
    assert rec.sessions_completed == 2
