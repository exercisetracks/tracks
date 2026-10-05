# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the cycling corpus: road_cycling.py and mtb.py, run in-process.

The phone's port (com.tracks.core.cycling) replays every case and must agree
exactly. Regenerable: the Python stays the oracle. Synthetic inputs only.

The two functions that query the active goal run against a stand-in session
that returns the goals the real query would, so the fixture pins the rule
(active event goals in the sport, earliest date first, nulls last, own
discipline else inferred from distance) rather than Postgres itself.

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_cycling_fixtures.py
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators import mtb, road_cycling  # noqa: E402

OUT = Path("/spec/fixtures/cycling.json")

SPORTS = [None, "", "cycling", "Road Biking", "indoor_cycling", "virtual_cycling", "e_biking",
          "mountain_biking", "trail biking", "running", "gravel_cycling"]
DISTANCES = [None, -1.0, 0.0, 7999.0, 8000.0, 24999.9, 25000.0, 99999.0, 100000.0, 250000.0]
DISCIPLINES = [None, "", "road_race", "CRITERIUM", "time_trial", "hill_climb", "xco", "XCM",
               "enduro", "trail", "unknown"]
LTHRS = [None, 0, -5, 150, 163, 171, 175]
SECONDS = [None, 0.0, 59.0, 180.0, 181.0, 300.0, 720.0, 1200.0, 2400.0, 3600.0, 3601.0,
           7200.0, 7201.0, 14400.0, 14401.0, 30000.0]
GRADS = [-0.2, -0.1, -0.05, -0.013, -0.0, 0.0, 0.013, 0.05, 0.1, 0.2]
TSS = [None, 0.0, 45.25, 88.35, 100.0, 123.45]


class _Query:
    """Enough of a Query for active_*_discipline: filter/order ignored, first() given."""

    def __init__(self, first):
        self._first = first

    def filter(self, *_a, **_k):
        return self

    def order_by(self, *_a):
        return self

    def first(self):
        return self._first


def _session(goals, sports):
    eligible = [g for g in goals if g.is_active and g.goal_type == "event" and g.event_sport in sports]
    # event_date ASC NULLS LAST; stable for ties, like the phone.
    eligible.sort(key=lambda g: (g.event_date is None, g.event_date or date.min))
    return SimpleNamespace(query=lambda *_: _Query(eligible[0] if eligible else None))


def _goal(active, gtype, sport, when, disc, dist, *, mtb_goal):
    return SimpleNamespace(
        is_active=active, goal_type=gtype, event_sport=sport,
        event_date=date.fromisoformat(when) if when else None,
        mtb_discipline=disc if mtb_goal else None,
        cycling_discipline=None if mtb_goal else disc,
        event_distance_meters=dist,
    )


GOAL_SETS = [
    [],
    [("t", "event", "cycling", "2026-11-01", None, 30000.0)],
    [("f", "event", "cycling", "2026-11-01", "criterium", None)],
    [("t", "volume", "cycling", "2026-11-01", "criterium", None)],
    [("t", "event", "cycling", None, "time_trial", None),
     ("t", "event", "road_biking", "2026-12-01", "Hill_Climb", None),
     ("t", "event", "cycling", "2026-10-15", None, 5000.0)],
    [("t", "event", "mountain_biking", "2026-11-01", None, 40000.0)],
    [("t", "event", "trail_biking", None, "ENDURO", None),
     ("t", "event", "mountain_biking", "2027-01-01", None, 10000.0)],
    [("t", "event", "running", "2026-11-01", "xco", 5000.0)],
]


def _goal_cases():
    out = []
    for gs in GOAL_SETS:
        wire = [dict(isActive=a == "t", goalType=t, eventSport=s, eventDate=d, discipline=disc,
                     eventDistanceMeters=dist) for a, t, s, d, disc, dist in gs]
        road = [_goal(a == "t", t, s, d, disc, dist, mtb_goal=False) for a, t, s, d, disc, dist in gs]
        mtbg = [_goal(a == "t", t, s, d, disc, dist, mtb_goal=True) for a, t, s, d, disc, dist in gs]
        rs = _session(road, road_cycling.ROAD_CYCLING_SPORTS)
        ms = _session(mtbg, mtb.MTB_SPORTS)
        m_disc = mtb.active_mtb_discipline(ms, 1)
        out.append({
            "goals": wire,
            "road": road_cycling.active_cycling_discipline(rs, 1),
            "mtb": m_disc,
            "mtb_tss": [
                {"sport": sp, "tss": t, "expected": mtb.apply_mtb_tss_multiplier(sp, t, ms, 1)}
                for sp in ("mountain_biking", "cycling", None) for t in (None, 88.35, 100.0)
            ],
        })
    return out


def _laps_cases():
    segs = [{"distance_m": 1500.0, "gradient": 0.04}, {"distance_m": 700.0, "gradient": -0.06},
            {"distance_m": 2300.0}, {"distance_m": 950.0, "gradient": 0.11}]
    cases = []
    for kw in [
        dict(distance_m=0.0, total_sec=3600.0),
        dict(distance_m=5000.0, total_sec=0.0),
        dict(distance_m=5000.0, total_sec=1200.0, lap_km=0.0),
        dict(distance_m=5.0, total_sec=60.0),
        dict(distance_m=10000.0, total_sec=2400.0),
        dict(distance_m=10005.0, total_sec=2400.0, course_type="flat"),
        dict(distance_m=10011.0, total_sec=2400.0, course_type="hilly", discipline="xco", lthr=170),
        dict(distance_m=42500.0, total_sec=9000.0, lap_km=2.5, split_spread=0.6, course_type="Mountainous",
             discipline="xcm", lthr=165),
        dict(distance_m=5450.0, total_sec=1500.0, split_spread=-0.4, course_segments=segs, discipline="enduro", lthr=171),
        dict(distance_m=8000.0, total_sec=900.0, course_type="unknown_type"),
        dict(distance_m=3000.0, total_sec=100.0),   # pace floored at 60 s/km
    ]:
        laps, total = mtb.mtb_hr_only_laps(**kw)
        cases.append({"input": kw, "laps": laps, "total": total})
    return cases


def main() -> None:
    fix = {
        "_comment": "Generated by spec/make_cycling_fixtures.py from road_cycling.py and mtb.py. Do not edit.",
        "sports": [{"sport": s, "road": road_cycling.is_road_cycling(s), "indoor": road_cycling.is_indoor_cycling(s),
                    "mtb": mtb.is_mtb(s)} for s in SPORTS],
        "infer": [{"distance": d, "road": road_cycling.infer_discipline_from_distance(d),
                   "mtb": mtb.infer_discipline_from_distance(d)} for d in DISTANCES],
        "disciplines": [{"discipline": d, "drafting": road_cycling.drafting_factor(d),
                         "mtb_tss": mtb.mtb_tss_multiplier(d),
                         "hr": [{"lthr": l, "road": road_cycling.race_hr_ceiling(d, l),
                                 "mtb": mtb.race_hr_ceiling(d, l)} for l in LTHRS]} for d in DISCIPLINES],
        "seconds": [{"seconds": s, "road_fuel": road_cycling.fueling_plan(s), "mtb_fuel": mtb.fueling_plan(s),
                     "w_per_kg": road_cycling.hill_climb_target_w_per_kg(s)} for s in SECONDS],
        "grades": [{"grad": g, "road": road_cycling.road_grade_multiplier(g),
                    "mtb": mtb.mtb_grade_multiplier(g)} for g in GRADS],
        "indoor_tss": [{"sport": s, "tss": t, "expected": road_cycling.apply_indoor_tss_multiplier(s, t)}
                       for s in SPORTS for t in TSS],
        "pace": [{"sec": s, "expected": mtb._fmt_pace_sec_per_km(s)}
                 for s in (-1.0, 0.0, 59.4, 59.5, 60.0, 239.5, 240.5, 299.6, 359.49, 1234.5)],
        "goals": _goal_cases(),
        "laps": _laps_cases(),
    }
    OUT.write_text(json.dumps(fix, indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
