# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A sync agent reports what it put on, or took off, a watch — for its own
account only.

The ids in these reports are the agent's word. Any user can pair an agent for
themselves, so an endpoint that trusted the ids let one account mark another's
workouts as on the watch (so they never got pushed) and, for courses flagged to
be purged once the watch dropped them, delete them outright.
"""
from datetime import date, datetime, timedelta, timezone

import pytest

from app.auth import hash_password
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.custom_track import CustomTrack
from app.models.sync_agents import SyncAgent
from app.models.training_plan import PlannedWorkout, TrainingPlan
from app.models.user_settings import UserSettings
from app.services.sync_agent_auth import generate_pairing_token


def _account(db, username):
    u = User(name=username.title(), username=username, is_admin=username == "alice")
    db.add(u)
    db.flush()
    db.add(UserSettings(user_id=u.id, password_hash=hash_password("x" * 12)))
    return u


@pytest.fixture
def alices_things(db):
    """Alice's workout, race plan and purge-pending course, and an agent of Bob's."""
    alice, bob = _account(db, "alice"), _account(db, "bob")
    goal = TrainingGoal(user_id=alice.id, goal_type="event", event_name="Race",
                        event_sport="running", event_date=date.today() + timedelta(days=60))
    db.add(goal)
    db.flush()
    plan = TrainingPlan(goal_id=goal.id, user_id=alice.id, sport="running")
    db.add(plan)
    db.flush()
    workout = PlannedWorkout(plan_id=plan.id, user_id=alice.id,
                             scheduled_date=date.today() + timedelta(days=1),
                             sport="running", workout_type="tempo", title="Tempo")
    race = RacePlan(goal_id=goal.id, user_id=alice.id)
    course = CustomTrack(user_id=alice.id, name="Alice's loop", purge_after_delete=True,
                         watch_uploaded_at=datetime.now(timezone.utc))
    db.add_all([workout, race, course])
    raw, token_hash = generate_pairing_token()
    db.add(SyncAgent(user_id=bob.id, created_by_user_id=bob.id, kind="mobile-app",
                     label="Bob's phone", token_hash=token_hash))
    db.commit()
    return {"workout": workout, "race": race, "course": course,
            "headers": {"Authorization": f"Bearer {raw}"}}


def test_an_agent_cannot_mark_another_accounts_workouts_uploaded(client, db, alices_things):
    t = alices_things
    resp = client.post("/training-plan/sync/mark-uploaded", headers=t["headers"], json=[
        {"type": "workout", "id": t["workout"].id, "filename": "W1.fit"},
        {"type": "race_plan", "id": t["race"].id, "filename": "RACE_1.fit"},
    ])
    assert resp.status_code == 200
    db.refresh(t["workout"])
    db.refresh(t["race"])
    assert t["workout"].watch_uploaded_at is None
    assert t["race"].watch_uploaded_at is None


def test_an_agent_cannot_mark_another_accounts_workouts_deleted(client, db, alices_things):
    t = alices_things
    resp = client.post("/training-plan/sync/mark-deleted", headers=t["headers"],
                       json={"ids": [t["workout"].id]})
    assert resp.status_code == 200
    db.refresh(t["workout"])
    assert t["workout"].watch_deleted_at is None


def test_an_agent_cannot_delete_another_accounts_course(client, db, alices_things):
    """The worst of them: a course flagged purge_after_delete is deleted, not
    just marked, when the watch reports it gone."""
    t = alices_things
    course_id = t["course"].id
    resp = client.post("/maps/sync/mark-course-deleted", headers=t["headers"],
                       json={"ids": [course_id]})
    assert resp.status_code == 200
    db.expire_all()
    assert db.get(CustomTrack, course_id) is not None


def test_an_agent_cannot_mark_another_accounts_course_uploaded(client, db, alices_things):
    t = alices_things
    t["course"].watch_uploaded_at = None
    db.commit()
    client.post("/maps/sync/mark-course-uploaded", headers=t["headers"],
                json=[{"id": t["course"].id, "filename": "TRK_1.fit"}])
    db.refresh(t["course"])
    assert t["course"].watch_uploaded_at is None
