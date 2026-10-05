# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Session-runner logging: per-session (not per-set) progression, planned-workout
completion, and double-log rejection.

These cover the Phase-3 fix to POST /workouts/sessions where progression state
was previously mutated once per *set* instead of once per exercise per session.
"""

from datetime import date

from app.database import SessionLocal
from app.models.strength import UserExerciseStrength
from app.models.training_plan import PlannedWorkout, TrainingPlan
from app.models.coaching import TrainingGoal


def _session_body(planned_workout_id=None):
    # One exercise, three sets — the old bug counted this as 3 sessions.
    return {
        "planned_workout_id": planned_workout_id,
        "session_rpe": 8,
        "exercises": [
            {
                "exercise_name": "Barbell Back Squat",
                "sets": [
                    {"weight_kg": 100, "reps": 5, "rpe": 7},
                    {"weight_kg": 100, "reps": 5, "rpe": 8},
                    {"weight_kg": 100, "reps": 5, "rpe": 9},
                ],
            }
        ],
    }


def _make_planned_workout(user_id):
    db = SessionLocal()
    try:
        goal = TrainingGoal(user_id=user_id, goal_type="fitness")
        db.add(goal)
        db.flush()
        plan = TrainingPlan(user_id=user_id, goal_id=goal.id, sport="strength_training")
        db.add(plan)
        db.flush()
        pw = PlannedWorkout(
            user_id=user_id, plan_id=plan.id, scheduled_date=date.today(),
            sport="strength_training", workout_type="strength",
            title="Leg Day", steps=[], is_complete=False,
        )
        db.add(pw)
        db.commit()
        return pw.id
    finally:
        db.close()


class TestWorkoutSessionProgression:
    def test_sessions_completed_increments_once_per_session(self, client, user):
        resp = client.post("/workouts/sessions", json=_session_body())
        assert resp.status_code == 201, resp.text

        db = SessionLocal()
        try:
            row = (
                db.query(UserExerciseStrength)
                .filter_by(user_id=user.id, exercise_name="Barbell Back Squat")
                .one()
            )
            # Three sets logged, but exactly ONE session counted.
            assert row.sessions_completed == 1
            assert row.estimated_1rm_kg and row.estimated_1rm_kg > 100
            assert row.progression_stage == "linear"
        finally:
            db.close()

    def test_two_sessions_count_two(self, client, user):
        client.post("/workouts/sessions", json=_session_body())
        client.post("/workouts/sessions", json=_session_body())
        db = SessionLocal()
        try:
            row = (
                db.query(UserExerciseStrength)
                .filter_by(user_id=user.id, exercise_name="Barbell Back Squat")
                .one()
            )
            assert row.sessions_completed == 2
        finally:
            db.close()

    def test_planned_workout_marked_complete(self, client, user):
        pw_id = _make_planned_workout(user.id)
        resp = client.post("/workouts/sessions", json=_session_body(planned_workout_id=pw_id))
        assert resp.status_code == 201, resp.text
        assert resp.json()["planned_workout_id"] == pw_id

        db = SessionLocal()
        try:
            pw = db.get(PlannedWorkout, pw_id)
            assert pw.is_complete is True
        finally:
            db.close()

    def test_double_log_rejected(self, client, user):
        pw_id = _make_planned_workout(user.id)
        first = client.post("/workouts/sessions", json=_session_body(planned_workout_id=pw_id))
        assert first.status_code == 201
        second = client.post("/workouts/sessions", json=_session_body(planned_workout_id=pw_id))
        assert second.status_code == 409

    def test_unknown_planned_workout_404(self, client, user):
        resp = client.post("/workouts/sessions", json=_session_body(planned_workout_id=999999))
        assert resp.status_code == 404
