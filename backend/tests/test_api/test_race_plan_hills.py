# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A course's hills, priced alike wherever a race's time is shown: the race
plan, the goal card's badge, and each leg of a triathlon. The phone does the
same (SportPlan.kt, held to it by spec/fixtures/race_plan.json)."""
from datetime import date, timedelta
from types import SimpleNamespace

import pytest

from app.api.coaching.race_plan import sport_plan
from app.models.coaching import RacePlan, TrainingGoal


def _course(profile):
    return [{"distance_m": 100.0, "gradient": g, "elevation_gain_m": round(max(0.0, 100 * g), 1)}
            for metres, g in profile for _ in range(int(metres // 100))]


HILLY_10K = _course([(2000, 0.0), (1000, 0.05), (1000, -0.05), (2000, 0.0), (300, 0.08), (300, -0.08),
                     (1400, 0.0), (500, 0.03), (500, -0.03), (1000, 0.0)])


@pytest.fixture
def goal(db, user, monkeypatch):
    monkeypatch.setattr("app.api.coaching.race_helpers._get_running_metrics",
                        lambda db, user, us, goal: {"vdot": 48.0, "vdot_today": 48.0,
                                                     "adherence": None, "run_indices": None})
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="Hilly 10K", event_sport="running",
                     event_distance_meters=10_000, event_date=date.today() + timedelta(days=40))
    db.add(g)
    db.commit()
    return g


def test_the_goal_card_prices_the_courses_hills_as_the_plan_does(client, db, user, goal):
    """The badge priced every race flat, so on a hilly course the card and
    the plan disagreed by the hills."""
    flat_badge = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]
    db.add(RacePlan(goal_id=goal.id, user_id=user.id, course_segments=HILLY_10K))
    db.commit()
    plan = client.post(f"/coaching/goals/{goal.id}/race-plan/generate").json()
    badge = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]
    assert badge > flat_badge * 1.02
    assert badge == pytest.approx(plan["predicted_seconds"], abs=1)


def test_a_terrain_type_reaches_the_goal_card_too(client, db, user, goal):
    flat_badge = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]
    db.add(RacePlan(goal_id=goal.id, user_id=user.id, course_type="hilly"))
    db.commit()
    badge = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]
    assert badge == pytest.approx(flat_badge * 1.04, abs=1)


def _tri(**rp):
    plan = SimpleNamespace(course_segments=None, course_type="flat", pace_hr_mode="pace", split_spread=0.0,
                           triathlon_splits_m=None, technicality_factor=None, technicality_label=None,
                           target_hr_ceiling=None, climb_target_w_per_kg=None, course_path=None,
                           tri_leg_courses=None, tri_transitions_m=None, intensity=None)
    for k, v in rp.items():
        setattr(plan, k, v)
    metrics = {"family": "triathlon", "vdot": 48.0, "run_indices": None, "ftp": 230, "css": 100.0,
               "max_hr": None, "lthr": None}
    _, sec, snap = sport_plan("triathlon", metrics, plan, SimpleNamespace(mtb_discipline=None,
                                                                         cycling_discipline=None), 25_750)
    return sec, {leg["leg"]: leg for leg in snap["triathlon_legs"]}


def test_a_triathlon_run_leg_is_timed_with_its_hills():
    """Its laps were paced for the hills while the leg's time was the flat
    one, so the two disagreed."""
    _, flat = _tri()
    _, hilly = _tri(tri_leg_courses={"run": {"segments": _course([(1000, 0.05), (1000, -0.05)] * 2
                                                                   + [(1000, 0.0)]), "path": []}})
    run = hilly["run"]
    assert run["predicted_seconds"] > flat["run"]["predicted_seconds"] * 1.03
    laps_time = sum(l["distance_m"] / 1000 * l["target_sec_per_km"] for l in run["lap_paces"])
    assert laps_time == pytest.approx(run["predicted_seconds"], rel=0.002)


def test_a_terrain_type_reaches_a_triathlons_legs():
    """The picker shows for a triathlon with no course, and changed nothing."""
    flat_sec, flat = _tri()
    hilly_sec, hilly = _tri(course_type="hilly")
    assert hilly["run"]["predicted_seconds"] == pytest.approx(flat["run"]["predicted_seconds"] * 1.04, rel=1e-9)
    assert hilly["bike"]["predicted_seconds"] == pytest.approx(flat["bike"]["predicted_seconds"] * 1.02, rel=1e-9)
    assert hilly["swim"]["predicted_seconds"] == flat["swim"]["predicted_seconds"]
    assert hilly_sec > flat_sec
