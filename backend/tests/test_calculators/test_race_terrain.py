# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race splits that follow the terrain rather than the kilometre markers."""
import random

from app.calculators.race_predictor import compute_lap_paces
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
    """Only the boundaries move; the effort model does not."""
    course = _course([(2000, 0.0), (500, 0.07), (2500, -0.01)])
    _, by_km = compute_lap_paces(1500, 5000, course_segments=course)
    _, by_terrain = compute_lap_paces(1500, 5000, course_segments=course, terrain=True)
    assert abs(by_km - by_terrain) / by_km < 0.01
