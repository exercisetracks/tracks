# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A hill climb's W/kg target, which used to ride inside the race plan's old
fuelling dict. The dict went with that fuelling model; the target must not."""
from datetime import date, timedelta

from app.models.coaching import TrainingGoal
from app.models.user_settings import UserSettings


def _hill_climb(db, user, discipline="hill_climb"):
    us = db.query(UserSettings).filter_by(user_id=user.id).one()
    us.ftp_mode, us.ftp_manual, us.weight_kg = "manual", 280, 70.0
    us.threshold_hr_mode, us.threshold_hr_manual = "manual", 170
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="Col", event_sport="cycling",
                     cycling_discipline=discipline, event_distance_meters=12_000,
                     event_date=date.today() + timedelta(days=60))
    db.add(g)
    db.commit()
    return g


def test_a_hill_climb_keeps_its_w_per_kg_target_and_the_watts_it_means(client, user, db):
    g = _hill_climb(db, user)
    r = client.post(f"/coaching/goals/{g.id}/race-plan/generate")
    assert r.status_code == 200, r.text
    plan = r.json()
    assert plan["climb_target_w_per_kg"] > 0
    assert plan["climb_target_watts"] == round(plan["climb_target_w_per_kg"] * 70)
    assert plan["target_hr_ceiling"]
    assert "fueling_plan" not in plan


def test_a_road_race_has_no_climb_target(client, user, db):
    g = _hill_climb(db, user, discipline="road_race")
    plan = client.post(f"/coaching/goals/{g.id}/race-plan/generate").json()
    assert plan["climb_target_w_per_kg"] is None
    assert plan["climb_target_watts"] is None
