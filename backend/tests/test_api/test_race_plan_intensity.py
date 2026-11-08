# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The race-intensity slider: how hard to race against the prediction, for
somebody who believes they are fitter (or less fit) than their numbers say.
It scales what the targets ask for — speed on foot, power on a bike — and the
finish follows. The phone applies the same (SportPlan.kt, held to it by the
intensity cases in spec/fixtures/race_plan.json)."""
from datetime import date, timedelta
from types import SimpleNamespace

import pytest

from app.api.coaching.race_plan import sport_plan
from app.models.coaching import TrainingGoal


@pytest.fixture
def goal(db, user, monkeypatch):
    monkeypatch.setattr("app.api.coaching.race_helpers._get_running_metrics",
                        lambda db, user, us: (50.0, None))
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="10K", event_sport="running",
                     event_distance_meters=10_000, event_date=date.today() + timedelta(days=60))
    db.add(g)
    db.commit()
    return g


def _generate(client, goal):
    r = client.post(f"/coaching/goals/{goal.id}/race-plan/generate")
    assert r.status_code == 200, r.text
    return r.json()


def test_a_harder_intensity_is_a_faster_plan_and_a_faster_badge(client, goal):
    """5 % harder is 5 % faster, in the plan and on the goal card alike —
    otherwise the card would still promise the predicted time."""
    before = _generate(client, goal)
    assert before["intensity"] == 1.0
    badge_before = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]

    patched = client.patch(f"/coaching/goals/{goal.id}/race-plan", json={"intensity": 1.05})
    assert patched.json()["intensity"] == 1.05
    after = _generate(client, goal)

    assert after["predicted_seconds"] == pytest.approx(before["predicted_seconds"] / 1.05, abs=2)
    assert after["lap_paces"][0]["target_sec_per_km"] < before["lap_paces"][0]["target_sec_per_km"]
    badge_after = client.get(f"/coaching/goals/{goal.id}/predicted-time").json()["predicted_seconds"]
    assert badge_after == pytest.approx(badge_before / 1.05, abs=1)


def test_an_intensity_past_the_slider_is_refused(client, goal):
    for v in (0.5, 1.2):
        r = client.patch(f"/coaching/goals/{goal.id}/race-plan", json={"intensity": v})
        assert r.status_code == 422, v


def test_a_bike_races_on_more_power_but_its_share_is_of_the_real_ftp():
    """The watch compares target_watts_pct_ftp to the FTP it holds, which is
    the measured one; a share of the raised FTP would read 100 % for watts
    above it."""
    def ride(k):
        rp = SimpleNamespace(course_segments=None, course_type="flat", pace_hr_mode="pace", split_spread=0.0,
                             triathlon_splits_m=None, technicality_factor=None, technicality_label=None,
                             target_hr_ceiling=None, climb_target_w_per_kg=None, course_path=None,
                             tri_leg_courses=None, tri_transitions_m=None, intensity=k)
        metrics = {"family": "cycling", "vdot": None, "run_indices": None, "ftp": 250, "css": None,
                   "max_hr": None, "lthr": None}
        goal = SimpleNamespace(mtb_discipline=None, cycling_discipline="time_trial")
        laps, sec, _ = sport_plan("cycling", metrics, rp, goal, 40_000)
        return laps[0], sec

    (lap, sec), (hard, hard_sec) = ride(None), ride(1.08)
    assert hard["target_watts"] == pytest.approx(lap["target_watts"] * 1.08, abs=1)
    assert hard["target_watts_pct_ftp"] == round(hard["target_watts"] / 250 * 100)
    assert hard_sec < sec
