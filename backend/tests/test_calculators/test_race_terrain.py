# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race splits that follow the terrain rather than the kilometre markers."""
import random

import pytest

from app.calculators.race_predictor import compute_lap_paces, course_grade_factor, grade_cost_multiplier
from app.calculators.race_predictor.terrain import MAX_SEGMENTS, terrain_segments


def _course(profile):
    """[(metres, gradient)] → 100 m course segments."""
    out = []
    for metres, g in profile:
        for _ in range(int(metres // 100)):
            out.append({"distance_m": 100.0, "gradient": g, "elevation_gain_m": 100 * g})
    return out


def test_a_short_steep_hill_gets_its_own_slower_split():
    """The case per-kilometre splits got wrong: a 300 m wall inside a flat
    kilometre was averaged away, and its target was nearly flat pace."""
    course = _course([(2000, 0.0), (300, 0.09), (2700, 0.0)])
    laps, _ = compute_lap_paces(1500, 5000, course_segments=course, terrain=True)
    hill = [l for l in laps if l["kind"] == "steep_up"]
    assert len(hill) == 1
    assert 200 <= hill[0]["distance_m"] <= 500
    flat = [l for l in laps if l["kind"] == "flat"]
    assert hill[0]["target_sec_per_km"] > max(l["target_sec_per_km"] for l in flat) * 1.2
    assert hill[0]["label"].startswith("Steep climb")


def test_a_descent_is_run_faster_than_the_flat():
    course = _course([(1500, 0.0), (1000, -0.04), (1500, 0.0)])
    laps, _ = compute_lap_paces(1200, 4000, course_segments=course, terrain=True)
    down = next(l for l in laps if l["kind"] == "down")
    assert down["target_sec_per_km"] < min(l["target_sec_per_km"] for l in laps if l["kind"] == "flat")


def test_a_noisy_marathon_fits_the_watch_and_covers_the_whole_course():
    rnd = random.Random(7)
    course = [{"distance_m": 100.0, "gradient": rnd.uniform(-0.08, 0.08)} for _ in range(422)]
    segs = terrain_segments(course, 42195)
    assert len(segs) <= MAX_SEGMENTS
    assert abs(sum(s["distance_m"] for s in segs) - 42195) < 1


def test_without_a_course_the_splits_are_still_kilometres():
    laps, _ = compute_lap_paces(1500, 5000, terrain=True)
    assert [l["distance_m"] for l in laps] == [1000] * 5
    assert "kind" not in laps[0]


def test_the_finish_time_is_the_same_whichever_way_the_course_is_cut():
    """Only the boundaries move; the effort model does not. Kilometres, miles
    and terrain priced their laps by each lap's mean grade, so the finish
    moved with the cut — by 6 % on rolling ground."""
    course = _course([(2000, 0.0), (500, 0.07), (2500, -0.01)] + [(200, 0.04), (200, -0.04)] * 5)
    _, by_km = compute_lap_paces(1500, 7000, course_segments=course)
    _, by_mile = compute_lap_paces(1500, 7000, lap_km=1.60934, course_segments=course)
    _, by_terrain = compute_lap_paces(1500, 7000, course_segments=course, terrain=True)
    assert by_km == by_mile == by_terrain
    assert by_terrain == round(1500 * course_grade_factor(course, 7000), 1)


def test_a_200_m_climb_gets_a_split_of_its_own_at_its_own_grade():
    """A bridge or a short rise: under the old 300 m minimum it was folded
    into two kilometres of flat, whose target it raised by under 1 %."""
    course = _course([(4000, 0.0), (200, 0.05), (5800, 0.0)])
    laps, _ = compute_lap_paces(3000, 10000, course_segments=course, terrain=True)
    climb = [l for l in laps if l["kind"] == "up"]
    assert [l["distance_m"] for l in climb] == [200]
    assert climb[0]["label"] == "Climb 5%"
    assert climb[0]["grade_multiplier"] == round(grade_cost_multiplier(0.05), 4)


def test_a_hill_inside_a_split_still_counts():
    """Too short to pace on its own, a 100 m pitch is folded into the flat —
    whose price is its ground's, the pitch included, not its mean grade's."""
    course = _course([(3000, 0.0), (100, 0.06), (2900, 0.0)])
    laps, total = compute_lap_paces(1800, 6000, course_segments=course, terrain=True)
    host = next(l for l in laps if l["start_km"] * 1000 <= 3050 < l["start_km"] * 1000 + l["distance_m"])
    expected = 1 + 100 * (grade_cost_multiplier(0.06) - 1) / host["distance_m"]
    assert host["grade_multiplier"] == pytest.approx(expected, abs=1e-4)
    assert total > 1800 * 1.003


def test_rolling_ground_costs_time_and_says_so():
    """200 m up, 200 m down: each split's mean grade is nil, so the plan read
    the road as dead flat — but a descent does not give back what the climb
    took, and the ground costs about 7 %."""
    course = _course([(200, 0.04), (200, -0.04)] * 25)
    laps, total = compute_lap_paces(3000, 10000, course_segments=course, terrain=True)
    assert total == pytest.approx(3000 * (grade_cost_multiplier(0.04) + grade_cost_multiplier(-0.04)) / 2, abs=0.1)
    assert total > 3000 * 1.06
    assert {l["label"] for l in laps} == {"Rolling"}


def test_a_crest_is_shared_between_its_climb_and_its_descent():
    """Smoothed, the top of a climb that runs straight into a descent reads
    as 200 m of flat. Folded whole into the climb it diluted it below the
    climb band, and 500 m rolls came out as rolling ground with no climbs."""
    course = _course([(500, 0.04), (500, -0.04)] * 10)
    laps, _ = compute_lap_paces(3000, 10000, course_segments=course, terrain=True)
    assert [l["kind"] for l in laps] == ["up", "down"] * 10
    assert {l["label"] for l in laps} == {"Climb 4%", "Descent 4%"}


def test_climbs_cost_what_minetti_measured_for_runners():
    """The running curve, not the walking one it was fed until 2026-11
    (1.44 on 5 %, 1.96 on 10 %); descents as they were."""
    assert grade_cost_multiplier(0.05) == pytest.approx(1.301, abs=0.001)
    assert grade_cost_multiplier(0.10) == pytest.approx(1.658, abs=0.001)
    assert grade_cost_multiplier(-0.05) == pytest.approx(0.881, abs=0.001)
    assert grade_cost_multiplier(-0.10) == pytest.approx(0.799, abs=0.001)
    assert grade_cost_multiplier(-0.20) == pytest.approx(1.0, abs=0.002)
