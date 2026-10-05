# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Authoring a plan from a phone, and merging the edits that come back.

The phone can build a training calendar with no network, which makes plan edits
the first writes Tracks has that the server must arbitrate: two sides can touch
the same workout and one of them has to lose. These tests pin the rule down —
newest write per *field* wins — because the failure mode is silent. A merge that
loses a tick or a rename does not raise; it just quietly shows the wrong plan.
"""
import re
from datetime import datetime, timedelta, timezone

from app.models.coaching import TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan

HLC = re.compile(r"^\d{16}-[0-9a-f]{4}-[0-9a-f]{16}$")

# Anchored to the clock, not to the calendar.
#
# These were fixed dates in 2026, which worked until the day they stopped: a
# scheduled workout has to be in the *future* for /coaching/workouts/upcoming to
# return it, so once that date passed, two tests here began failing on every
# clone — for a reason that has nothing to do with what they test. Relative
# offsets cannot go stale that way.
NOW = datetime.now(timezone.utc).replace(microsecond=0)

# Distinct days, all comfortably ahead of today, all before the goal event.
DAY_ONE = (NOW + timedelta(days=4)).date()
DAY_TWO = (NOW + timedelta(days=5)).date()
DAY_THREE = (NOW + timedelta(days=6)).date()
EVENT_DATE = (NOW + timedelta(days=64)).date()


def _plan(db, user) -> TrainingPlan:
    """A generated plan to hang prescribed workouts off. A plan needs a goal —
    it is the thing plans are generated from — so both come together."""
    goal = TrainingGoal(
        user_id=user.id, goal_type="event", is_active=True,
        event_name="Autumn Half", event_sport="running",
        event_date=EVENT_DATE,
    )
    db.add(goal)
    db.flush()
    plan = TrainingPlan(goal_id=goal.id, user_id=user.id, sport="running")
    db.add(plan)
    db.flush()
    return plan




class TestCreatePlannedWorkout:
    def test_a_user_workout_belongs_to_no_plan(self, client, user):
        resp = client.post("/coaching/plan/workouts", json={
            "scheduled_date": DAY_ONE.isoformat(),
            "title": "Hill repeats",
            "sport": "running",
            "workout_type": "interval",
            "steps": [{"type": "run", "duration_min": 40}],
        })
        assert resp.status_code == 201, resp.text
        body = resp.json()
        assert body["plan_id"] is None
        assert body["origin"] == "user"
        assert body["title"] == "Hill repeats"
        assert body["steps"] == [{"type": "run", "duration_min": 40}]

    def test_regenerating_a_plan_leaves_it_alone(self, client, user, db):
        """The reason plan_id is nullable rather than pointing at the plan.

        Both paths generate_plan deletes through filter on plan_id, so a
        workout with none survives without any special case — and this is the
        test that says so, because the next person to touch that code will not
        otherwise know it is load-bearing.
        """
        plan = _plan(db, user)
        prescribed = PlannedWorkout(
            plan_id=plan.id, user_id=user.id, scheduled_date=DAY_TWO,
            sport="running", workout_type="easy", title="Easy 8k",
        )
        db.add(prescribed)
        db.commit()

        mine = client.post("/coaching/plan/workouts", json={
            "scheduled_date": DAY_TWO.isoformat(), "title": "My own session",
        }).json()

        # What a regeneration does to the old plan's workouts.
        db.query(PlannedWorkout).filter(
            PlannedWorkout.plan_id == plan.id,
        ).delete(synchronize_session=False)
        db.commit()

        survivors = db.query(PlannedWorkout).filter_by(user_id=user.id).all()
        assert [w.id for w in survivors] == [mine["id"]]

    def test_fields_are_stamped_so_a_later_edit_is_compared_not_assumed(self, client, user, db):
        """Without a stamp, the first phone edit of each field would win by
        default rather than being compared against when the web wrote it."""
        created = client.post("/coaching/plan/workouts", json={
            "scheduled_date": DAY_ONE.isoformat(), "title": "Stamped",
        }).json()
        row = db.query(PlannedWorkout).filter_by(id=created["id"]).first()
        assert HLC.match(row.clock["title"])
        assert HLC.match(row.clock["scheduled_date"])
        assert row.uid


class TestLiveEdits:
    """The web app's PATCH. Offline edits and their conflicts go through
    /sync/push now (tests/test_sync); what matters here is that a live edit
    takes part in that merge without knowing it exists."""

    def _workout(self, client) -> dict:
        return client.post("/coaching/plan/workouts", json={
            "scheduled_date": DAY_ONE.isoformat(),
            "title": "Original",
            "description": "As written",
        }).json()

    def test_a_live_edit_lands(self, client, user):
        w = self._workout(client)
        resp = client.patch(f"/coaching/plan/workouts/{w['id']}", json={"title": "Renamed"})
        assert resp.status_code == 200
        assert resp.json()["title"] == "Renamed"

    def test_a_live_edit_beats_a_phone_stamp_from_a_fast_clock(self, client, user, db):
        """A phone whose clock runs an hour ahead stamped the title. Someone at
        the desk looking at that title and changing it is still the newest
        edit; without stamping past the stored stamp, the web would silently
        fail to save until wall time caught up."""
        w = self._workout(client)
        row = db.query(PlannedWorkout).filter_by(id=w["id"]).first()
        ahead = int((datetime.now(timezone.utc) + timedelta(hours=1)).timestamp() * 1000)
        row.clock = {**row.clock, "title": f"{ahead:016d}-0000-00000000000000ff"}
        db.commit()

        resp = client.patch(f"/coaching/plan/workouts/{w['id']}", json={"title": "From the desk"})
        assert resp.json()["title"] == "From the desk"
        db.refresh(row)
        assert row.clock["title"] > f"{ahead:016d}-0000-00000000000000ff"

    def test_edits_to_different_fields_stamp_only_those_fields(self, client, user, db):
        """The whole reason stamps are per field: ticking a session off must
        not look like an edit to its title, or a phone's rename made meanwhile
        would lose to a change nobody made."""
        w = self._workout(client)
        row = db.query(PlannedWorkout).filter_by(id=w["id"]).first()
        title_stamp = row.clock["title"]
        client.patch(f"/coaching/plan/workouts/{w['id']}", json={"is_complete": True})
        db.refresh(row)
        assert row.clock["title"] == title_stamp
        assert row.clock["is_complete"] > title_stamp

    def test_clearing_a_field_is_an_edit(self, client, user, db):
        """exclude_unset rather than exclude_none: null is a value somebody wrote."""
        w = self._workout(client)
        resp = client.patch(f"/coaching/plan/workouts/{w['id']}", json={"description": None})
        assert resp.json()["description"] is None
        row = db.query(PlannedWorkout).filter_by(id=w["id"]).first()
        assert "description" in row.clock

    def test_steps_round_trip(self, client, user):
        """A workout authored on the phone arrives as structure, not prose —
        and the watch file is built from exactly these."""
        w = self._workout(client)
        steps = [
            {"type": "warmup", "duration_min": 15, "pace": "easy"},
            {"type": "interval_set", "reps": 5, "distance_m": 800, "rest_sec": 90},
        ]
        resp = client.patch(f"/coaching/plan/workouts/{w['id']}", json={
            "steps": steps,
        })
        assert resp.status_code == 200
        assert resp.json()["steps"] == steps

    def test_an_omitted_field_is_untouched(self, client, user):
        w = self._workout(client)
        client.patch(f"/coaching/plan/workouts/{w['id']}", json={
            "title": "New title",
        })
        resp = client.get("/coaching/workouts/upcoming?days=3650&limit=50")
        row = next(x for x in resp.json() if x["id"] == w["id"])
        assert row["description"] == "As written"
