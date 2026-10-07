# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race plans regenerate every time their page opens, so regenerating must be
cheap and must not disturb the watch when nothing changed."""
from datetime import date, datetime, timedelta, timezone

import pytest

from app.api.coaching import race_helpers
from app.models.coaching import RacePlan, TrainingGoal


@pytest.fixture
def goal(db, user, monkeypatch):
    monkeypatch.setattr("app.api.coaching.race_helpers._get_running_metrics",
                        lambda db, user, us: (50.0, None))
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="10K", event_sport="running",
                     event_distance_meters=10_000, event_date=date.today() + timedelta(days=60))
    db.add(g)
    db.commit()
    return g


def test_regenerating_an_unchanged_plan_leaves_the_watch_alone(client, user, db, goal):
    """Otherwise every visit to the page re-sent the plan to the watch."""
    first = client.post(f"/coaching/goals/{goal.id}/race-plan/generate")
    assert first.status_code == 200, first.text
    rp = db.query(RacePlan).filter_by(goal_id=goal.id).one()
    rp.watch_uploaded_at = datetime.now(timezone.utc)
    db.commit()
    client.post(f"/coaching/goals/{goal.id}/race-plan/generate")
    db.refresh(rp)
    assert rp.watch_uploaded_at is not None


def test_a_changed_plan_goes_to_the_watch_again(client, user, db, goal):
    client.post(f"/coaching/goals/{goal.id}/race-plan/generate")
    rp = db.query(RacePlan).filter_by(goal_id=goal.id).one()
    rp.watch_uploaded_at = datetime.now(timezone.utc)
    db.commit()
    client.patch(f"/coaching/goals/{goal.id}/race-plan", json={"split_spread": 0.04})
    client.post(f"/coaching/goals/{goal.id}/race-plan/generate")
    db.refresh(rp)
    assert rp.watch_uploaded_at is None


def test_race_weather_is_fetched_once_per_place_and_day(monkeypatch):
    calls = []
    monkeypatch.setattr(race_helpers, "_fetch_weather_uncached",
                        lambda lat, lon, d: calls.append(1) or {"temperature_c": 12, "source": "forecast"})
    d = date.today() + timedelta(days=3)
    race_helpers._fetch_weather(46.5, 7.5, d)
    race_helpers._fetch_weather(46.5, 7.5, d)
    assert len(calls) == 1
    race_helpers._fetch_weather(46.6, 7.5, d)
    assert len(calls) == 2
