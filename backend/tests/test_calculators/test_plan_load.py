# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Planned load: a week is sized so its sessions carry the load asked of it,
and weeks that mix sports (triathlon, multi-sport fitness goals) share one
target between them.

Before this, a runner's plan stopped rising at about +3–4 CTL a week and past
CTL 50 could not hold CTL at all; a cyclist got 60–75% of each extra point.
Dates are anchored to a fixed Monday: nothing here depends on today.
"""

from datetime import date, datetime, timedelta
from types import SimpleNamespace

import pytest

from app.calculators.plan.base import vdot_to_paces
from app.calculators.plan.generator.fitness import (
    fitness_week_targets,
    generate_fitness_plan,
)
from app.calculators.plan.generator.multisport import IMPACT_FAMILIES
from app.calculators.plan.generator.plan import generate_training_plan
from app.calculators.plan.load import workout_tss
from app.calculators.plan.moved import role
from app.calculators.plan.running import _run_with_breaks

MONDAY = date(2026, 3, 2)
RUN_BESTS = [(5000, 3.6), (10000, 3.35)]
_FAMILY = {"running": "running", "cycling": "cycling", "mountain_biking": "mountain_biking",
           "swimming": "swimming", "hiking": "hiking"}


def _goal(sport, ramp, dpw, sports=None):
    return SimpleNamespace(event_sport=sport, ctl_ramp_per_week=ramp, days_per_week=dpw,
                           mtb_discipline=None, cycling_discipline=None, fitness_sports=sports)


def _paces_for(w, paces):
    return paces if w["sport"] == "running" else None


def _week_loads(workouts, paces=None):
    loads: dict[int, float] = {}
    for w in workouts:
        k = (w["scheduled_date"] - MONDAY).days // 7
        loads[k] = loads.get(k, 0.0) + workout_tss(w["steps"], _paces_for(w, paces))
    return loads


def _ratios(sport, ctl, ramp, dpw, sports=None):
    goal = _goal(sport, ramp, dpw, sports)
    targets = fitness_week_targets(ctl, ctl, ramp, MONDAY, MONDAY)
    vdot, ws = generate_fitness_plan(goal, [], RUN_BESTS, MONDAY, ctl, ctl, anchor=MONDAY,
                                     ftp=250, threshold_hr=165)
    loads = _week_loads(ws, vdot_to_paces(vdot) if vdot else None)
    return [loads[i] / targets[i]["tss"] for i in range(3)]


@pytest.mark.parametrize("sport,ctl,dpw", [
    ("running", 50.0, 5), ("running", 60.0, 6), ("cycling", 60.0, 5),
    ("cycling", 40.0, 4), ("mountain_biking", 50.0, 5),
])
def test_a_build_week_carries_the_load_its_ramp_asks_for(sport, ctl, dpw):
    """At +4 a CTL-50 runner's week used to come out at 60–80% of its target;
    the number on the slider has to reach the calendar."""
    for r in _ratios(sport, ctl, 4.0, dpw):
        assert r == pytest.approx(1.0, abs=0.1)


def test_a_high_fitness_runner_can_hold_their_fitness():
    """Past CTL 50 the old plan could not even hold CTL: an 80 km weekly cap
    and 90-minute easy runs capped the week below what the runner already did."""
    for r in _ratios("running", 70.0, 0.0, 6):
        assert r == pytest.approx(1.0, abs=0.1)


def test_a_week_too_big_for_single_runs_gets_doubles_not_marathon_easy_runs():
    """Past the per-run caps, time on feet comes from a second short run on
    easy days, never from lengthening one run past what the legs tolerate."""
    _, ws = generate_fitness_plan(_goal("running", 4.0, 5), [], RUN_BESTS, MONDAY, 80.0, 80.0,
                                  anchor=MONDAY)
    doubles = [w for w in ws if w["title"].startswith("Second run")]
    assert doubles
    assert all(w["duration_minutes"] <= 80 for w in doubles)  # 60 + walks + variation
    assert all(w["workout_type"] != "long" or w["duration_minutes"] <= 250 for w in ws)


@pytest.mark.parametrize("sport", ["rowing", "open_water_swimming"])
def test_rowers_and_swimmers_double_rather_than_fall_short(sport):
    """Four days of rowing or swimming at CTL 40 +4 came out at 60–80% of
    the week: every session at its cap. Both sports train twice a day."""
    for r in _ratios(sport, 40.0, 4.0, 4):
        assert r == pytest.approx(1.0, abs=0.1)


@pytest.mark.parametrize("sport", ["hiking", "cross_country_skiing"])
def test_a_long_hike_or_ski_day_is_not_cut_to_ninety_minutes(sport):
    """The new sports shared the planner's 90-minute long cap; a long day in
    the mountains is the session those sports are built around."""
    _, ws = generate_fitness_plan(_goal(sport, 3.0, 5), [], [], MONDAY, 50.0, 50.0, anchor=MONDAY)
    longest = max(w["duration_minutes"] for w in ws if w["workout_type"] in ("long", "back_to_back"))
    assert longest > 150
    for r in _ratios(sport, 50.0, 3.0, 5):
        assert r == pytest.approx(1.0, abs=0.1)


def test_a_low_fitness_week_rests_days_rather_than_overshooting():
    """At CTL 20 on seven days a week, floor-length rides every day would be
    about twice the load asked for; easy days are rested instead."""
    _, ws = generate_fitness_plan(_goal("cycling", 0.0, 7), [], [], MONDAY, 20.0, 20.0,
                                  anchor=MONDAY)
    first = [w for w in ws if (w["scheduled_date"] - MONDAY).days < 7]
    assert len(first) < 7
    target = fitness_week_targets(20.0, 20.0, 0.0, MONDAY, MONDAY)[0]["tss"]
    assert _week_loads(first)[0] <= target * 1.15


def test_the_long_session_is_at_most_forty_percent_of_the_week():
    """With few days a week a 2:1 long session would be half the training;
    the long-run share is an injury guard, kept on purpose."""
    _, ws = generate_fitness_plan(_goal("running", 2.0, 3), [], RUN_BESTS, MONDAY, 40.0, 40.0,
                                  anchor=MONDAY)
    week = [w for w in ws if (w["scheduled_date"] - MONDAY).days < 7]
    total = sum(w["duration_minutes"] for w in week)
    long_min = sum(w["duration_minutes"] for w in week if w["workout_type"] == "long")
    assert long_min <= total * 0.45  # 40% of sized time; warm-up walks sit outside it


def test_a_walk_break_run_near_capacity_keeps_its_length():
    """Segments were capped at 2 km, so a trained runner's 2-hour run just
    over 85% of capacity came out as 3 × 2 km."""
    paces = vdot_to_paces(48.0)
    steps = _run_with_breaks(120, paces, capacity_km=24.0)
    run = next(s for s in steps if s["type"] == "interval_set")
    assert run["reps"] * run["distance_m"] >= 18000


def test_an_experienced_runner_is_not_cut_to_the_races_volume_cap():
    """A 100 km/week runner training for a 10 km used to be planned at 45 km —
    detraining for the race they train for."""
    hist = [SimpleNamespace(sport="running", distance_meters=100000.0, duration_seconds=None,
                            started_at=datetime(2026, 2, 27) - timedelta(weeks=k)) for k in range(8)]
    goal = SimpleNamespace(event_date=MONDAY + timedelta(weeks=12), event_sport="running",
                           event_distance_meters=10000.0, days_per_week=6, plan_intensity=1.0,
                           mtb_discipline=None, cycling_discipline=None)
    _, ws = generate_training_plan(goal, hist, RUN_BESTS, MONDAY)
    first_week = [w for w in ws if (w["scheduled_date"] - MONDAY).days < 7]
    assert sum(w["distance_meters"] or 0 for w in first_week) > 75000


# ── Triathlon ────────────────────────────────────────────────────────────────

def _tri(distance, dpw=6, weeks=12):
    goal = SimpleNamespace(event_date=MONDAY + timedelta(weeks=weeks, days=5),
                           event_sport="triathlon", event_distance_meters=distance,
                           days_per_week=dpw, plan_intensity=1.0,
                           mtb_discipline=None, cycling_discipline=None)
    return generate_training_plan(goal, [], RUN_BESTS, MONDAY, ftp=250, threshold_hr=165)


def test_a_triathlon_plan_trains_all_three_sports():
    """It used to borrow the running templates and plan 'easy triathlon'."""
    _, ws = _tri(51_500.0)
    assert {"swimming", "cycling", "running"} <= {w["sport"] for w in ws}
    assert ws[-1]["workout_type"] == "race" and ws[-1]["sport"] == "triathlon"


def test_a_brick_is_a_ride_then_a_run_on_the_same_day():
    _, ws = _tri(51_500.0)
    bricks = [i for i, w in enumerate(ws) if w["workout_type"] == "brick_run"]
    assert bricks
    for i in bricks:
        ride = ws[i - 1]
        assert ride["sport"] == "cycling" and ride["scheduled_date"] == ws[i]["scheduled_date"]
        assert ride["title"].startswith("Brick")


@pytest.mark.parametrize("distance,bike_over_run", [(25_750.0, 1.0), (226_000.0, 1.4)])
def test_the_load_split_follows_the_race_distance(distance, bike_over_run):
    """Long course is won on the bike; the split has to show it."""
    _, ws = _tri(distance, dpw=7)
    by = {}
    for w in ws:
        by[w["sport"]] = by.get(w["sport"], 0.0) + workout_tss(w["steps"], None)
    assert by["cycling"] > by["running"] * bike_over_run
    assert by["swimming"] < by["cycling"]


def test_the_taper_is_joint():
    """A sport that kept its volume through the taper arrives tired."""
    _, ws = _tri(113_000.0, weeks=14)
    per: dict[tuple[int, str], float] = {}
    for w in ws:
        k = ((w["scheduled_date"] - MONDAY).days // 7, w["sport"])
        per[k] = per.get(k, 0.0) + workout_tss(w["steps"], None)
    last = max(k[0] for k in per)
    for sport in ("swimming", "cycling", "running"):
        peak = max(v for (wk, s), v in per.items() if s == sport and wk < last - 3)
        assert per[(last, sport)] < peak * 0.8


# ── Multi-sport fitness ──────────────────────────────────────────────────────

def _history(hours: dict[str, float]):
    out = []
    for sport, h in hours.items():
        for k in range(8):
            out.append(SimpleNamespace(sport=sport, distance_meters=None, duration_seconds=h * 3600,
                                       started_at=datetime(2026, 2, 27) - timedelta(weeks=k)))
    return out


def _multi(sports, hist, dpw=5, ramp=3.0, ctl=45.0):
    goal = _goal(sports[0], ramp, dpw, sports)
    return generate_fitness_plan(goal, hist, RUN_BESTS, MONDAY, ctl, ctl, anchor=MONDAY,
                                 ftp=250, threshold_hr=165)[1]


def test_a_multi_sport_week_carries_one_shared_load():
    targets = fitness_week_targets(45.0, 45.0, 3.0, MONDAY, MONDAY)
    ws = _multi(["running", "cycling"], _history({"running": 4, "cycling": 3}))
    loads = _week_loads(ws, vdot_to_paces(48.0))
    for i in range(3):
        assert loads[i] == pytest.approx(targets[i]["tss"], rel=0.12)


def test_the_share_follows_what_the_athlete_actually_does():
    ws = _multi(["running", "cycling"], _history({"cycling": 6, "running": 1}))
    by = {}
    for w in ws:
        by[w["sport"]] = by.get(w["sport"], 0.0) + w["duration_minutes"]
    assert by["cycling"] > by["running"]


def test_no_two_hard_days_and_no_two_impact_days_in_a_row():
    """Seiler's hard-easy alternation is about the athlete, not the sport; and
    bone and tendon recover slower than lungs, which is why cross-training exists."""
    ws = _multi(["running", "cycling", "swimming"],
                _history({"running": 3, "cycling": 3, "swimming": 1}), dpw=6, ramp=4.0)
    hard = {"long"} | {"intervals", "tempo", "fartlek", "sweet_spot", "threshold", "vo2"}
    by_day = {}
    for w in ws:
        by_day.setdefault(w["scheduled_date"], []).append(w)
    days = sorted(by_day)
    for a, b in zip(days, days[1:]):
        if (b - a).days != 1 or (a - MONDAY).days // 7 != (b - MONDAY).days // 7:
            continue
        ha = any(w["workout_type"] in hard for w in by_day[a])
        hb = any(w["workout_type"] in hard for w in by_day[b])
        assert not (ha and hb), (a, b)
        ia = any(_FAMILY.get(w["sport"]) in IMPACT_FAMILIES for w in by_day[a])
        ib = any(_FAMILY.get(w["sport"]) in IMPACT_FAMILIES for w in by_day[b])
        assert not (ia and ib), (a, b)


def test_a_triathlon_fitness_goal_trains_swim_bike_and_run():
    ws = _multi(["triathlon"], [], dpw=6)
    assert {"swimming", "cycling", "running"} <= {w["sport"] for w in ws}


def test_a_moved_brick_run_only_takes_a_brick_runs_place():
    """Matched by role, a moved brick run must not swallow an easy run."""
    assert role("brick_run") != role("easy")
