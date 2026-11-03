# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A triathlon's courses: one whole file split at T1/T2, a file per leg, or
both — organisers publish either, and each leg's course shapes its targets."""
from types import SimpleNamespace

from app.api.coaching.race_plan import sport_plan
from app.calculators.race_predictor.triathlon import auto_transitions, leg_courses, slice_segments, total_m

SPLITS = {"swim_m": 1500, "bike_m": 40000, "run_m": 10000}
DEG_M = 111_195.0          # metres per degree of latitude


def _loop_course():
    """Swim out and back from the beach, a bike loop back to transition,
    then a run out and back: every leg returns to the transition zone, which
    sits 1.6 km into the file (the swim ran a little long)."""
    path, segs = [], []
    def go(north_m, west_m):
        path.append([round(north_m / DEG_M, 6), round(-west_m / DEG_M, 6), 10.0])
    for i in list(range(9)) + list(range(7, -1, -1)):    # swim: 800 m out and back
        go(i * 100, 0)
    corners = [lambda t: (0, t), lambda t: (t, 10), lambda t: (10, 10 - t), lambda t: (10 - t, 0)]
    for side in corners:                                  # bike: a 10 km square, back to transition
        for t in range(1, 11):
            north, west = side(t)
            go(north * 1000, west * 1000)
    for i in list(range(1, 6)) + list(range(4, -1, -1)):  # run: 5 km out and back
        go(-i * 1000, 0)
    from app.calculators.race_predictor.geo import _haversine_m
    for a, b in zip(path, path[1:]):
        d = _haversine_m(a[0], a[1], b[0], b[1])
        if d > 0:
            segs.append({"distance_m": round(d, 1), "elevation_gain_m": 0.0, "gradient": 0.0})
    return segs, path


def test_a_whole_course_is_split_where_it_returns_to_transition():
    """The swim ran 1.6 km, not 1.5: T1 is where the course came back to the
    beach, not at the nominal distance."""
    segs, path = _loop_course()
    t1, t2 = auto_transitions(segs, path, SPLITS)
    assert abs(t1 - 1600) < 120
    assert abs(t2 - (1600 + 40000)) < 1500


def test_a_point_to_point_course_falls_back_to_the_nominal_distances():
    path = [[0.0, i * 0.01, 0.0] for i in range(60)]
    segs = [{"distance_m": 1111.9, "elevation_gain_m": 0.0, "gradient": 0.0}] * 59
    assert auto_transitions(segs, path, SPLITS) == [1500.0, 41500.0]


def test_a_legs_own_file_wins_over_its_stretch_of_the_whole():
    segs, path = _loop_course()
    own = {"bike": {"segments": [{"distance_m": 42000.0, "elevation_gain_m": 300.0, "gradient": 0.01}],
                    "path": [[1.0, 1.0, 5.0]]}}
    legs = leg_courses(segs, path, own, None, SPLITS)["legs"]
    assert legs["bike"]["source"] == "own" and total_m(legs["bike"]["segments"]) == 42000.0
    assert legs["swim"]["source"] == legs["run"]["source"] == "whole"


def test_the_users_transitions_always_win():
    segs, path = _loop_course()
    legs = leg_courses(segs, path, None, [2000.0, 42000.0], SPLITS)
    assert legs["transitions_m"] == [2000.0, 42000.0]
    assert abs(total_m(legs["legs"]["swim"]["segments"]) - 2000) < 1


def test_slicing_keeps_distance_and_cuts_a_segment_in_proportion():
    segs = [{"distance_m": 1000.0, "elevation_gain_m": 50.0, "gradient": 0.05}] * 3
    cut = slice_segments(segs, 500, 2000)
    assert total_m(cut) == 1500.0 and cut[0]["elevation_gain_m"] == 25.0


def test_a_hilly_bike_leg_slows_the_bike_and_sets_power_by_grade():
    """Leg courses shape their targets as a single-sport course does."""
    flat = {"bike": {"segments": [{"distance_m": 1000.0, "elevation_gain_m": 0.0, "gradient": 0.0}] * 40, "path": []}}
    hilly = {"bike": {"segments": [{"distance_m": 1000.0, "elevation_gain_m": 60.0 if i % 2 else 0.0,
                                    "gradient": 0.06 if i % 2 else -0.06} for i in range(40)], "path": []}}
    def plan(files):
        rp = SimpleNamespace(course_segments=None, course_path=None, tri_leg_courses=files, tri_transitions_m=None,
                             course_type="flat", pace_hr_mode="pace", split_spread=0.0, triathlon_splits_m=None,
                             technicality_factor=None, technicality_label=None, target_hr_ceiling=None,
                             climb_target_w_per_kg=None)
        m = {"family": "triathlon", "vdot": 50.0, "run_indices": None, "ftp": 250, "css": None, "max_hr": None, "lthr": None}
        _, _, snap = sport_plan("triathlon", m, rp, SimpleNamespace(mtb_discipline=None, cycling_discipline=None), 51_500)
        return {l["leg"]: l for l in snap["triathlon_legs"]}
    f, h = plan(flat)["bike"], plan(hilly)["bike"]
    assert h["predicted_seconds"] > f["predicted_seconds"]
    watts = [l["target_watts"] for l in h["lap_paces"]]
    assert max(watts) > min(watts)
