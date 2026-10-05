# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A workout written on the phone must still encode to a FIT file here.

The phone builds its own watch files now, but the server builds them too — for
the browser's sync path and for garmin-sync — so a workout authored on a phone
has to survive both encoders. The seam between them is the `steps` JSON, and it
is easy to break in a way nothing notices: the step emitters read fields with
`.get(key, default)`, so a phone that sent an explicit `null` where it means
"not set" would defeat every default and raise inside the encoder rather than
falling back.

kotlinx is configured with `explicitNulls = false`, so it omits them. These
tests pin that assumption from this side, using payloads shaped exactly as the
phone serialises them.
"""
import base64
from datetime import datetime, timedelta, timezone

from app.api.training_plan.sync import _build_fit_b64
from app.models.training_plan import PlannedWorkout

# Relative to now rather than a fixed date — see the same note in
# test_planned_workout_authoring. A hardcoded day here broke twice over once it
# went past: the workout fell out of the upcoming window, and the edit below
# became older than the row it was editing, so the field-level merge refused it.
SCHEDULED = (datetime.now(timezone.utc) + timedelta(days=4)).date().isoformat()
EDITED_AT = (datetime.now(timezone.utc) + timedelta(hours=6)).isoformat()


class TestPhoneAuthoredWorkoutEncodes:
    def _create(self, client, steps, workout_type="interval", sport="running"):
        resp = client.post("/coaching/plan/workouts", json={
            "scheduled_date": SCHEDULED,
            "title": "Written on a phone",
            "sport": sport,
            "workout_type": workout_type,
            "steps": steps,
        })
        assert resp.status_code == 201, resp.text
        return resp.json()

    def test_an_endurance_workout_round_trips_into_a_fit_file(self, client, user, db):
        # Exactly what the phone's WorkoutStep serialises to: the fields it set,
        # the non-null defaults kotlinx writes, and no nulls at all.
        created = self._create(client, [
            {"type": "warmup", "duration_min": 15.0, "pace": "easy",
             "each_side": False, "cues": [], "primary_muscles": []},
            {"type": "interval_set", "reps": 6, "distance_m": 800.0,
             "rest_sec": 90, "pace": "threshold",
             "each_side": False, "cues": [], "primary_muscles": []},
            {"type": "cooldown", "duration_min": 10.0, "pace": "easy",
             "each_side": False, "cues": [], "primary_muscles": []},
        ])
        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        fit = base64.b64decode(_build_fit_b64(row, db))
        assert fit[8:12] == b".FIT"
        assert len(fit) > 100

    def test_a_strength_workout_round_trips(self, client, user, db):
        created = self._create(
            client,
            [{"type": "strength_exercise", "name": "Back Squat", "sets": 4,
              "reps": 5, "weight_kg": 80.0, "rest_seconds": 150,
              "each_side": False, "cues": [], "primary_muscles": []}],
            workout_type="strength", sport="training",
        )
        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        fit = base64.b64decode(_build_fit_b64(row, db))
        assert fit[8:12] == b".FIT"

    def test_a_mobility_workout_round_trips(self, client, user, db):
        created = self._create(
            client,
            [{"type": "mobility_exercise", "name": "Low Lunge",
              "duration_seconds": 45, "sets": 2, "each_side": True,
              "cues": [], "primary_muscles": []}],
            workout_type="mobility", sport="training",
        )
        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        fit = base64.b64decode(_build_fit_b64(row, db))
        assert fit[8:12] == b".FIT"

    def test_a_step_with_no_optional_fields_falls_back_to_defaults(self, client, user, db):
        """The bare minimum a phone can send. If any emitter read an explicit
        null here instead of an absent key, this is where it would raise."""
        created = self._create(client, [
            {"type": "run"},
            {"type": "interval_set", "reps": 3},
            {"type": "fartlek"},
            {"type": "effort_set", "reps": 2},
        ])
        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        fit = base64.b64decode(_build_fit_b64(row, db))
        assert fit[8:12] == b".FIT"

    def test_a_workout_edited_to_have_steps_still_encodes(self, client, user, db):
        created = self._create(client, [])
        steps = [{"type": "run", "duration_min": 40.0, "pace": "marathon"}]
        resp = client.patch(f"/coaching/plan/workouts/{created['id']}", json={
            "steps": steps, "edited_at": EDITED_AT,
        })
        assert resp.status_code == 200
        assert resp.json()["steps"] == steps

        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        fit = base64.b64decode(_build_fit_b64(row, db))
        assert fit[8:12] == b".FIT"
