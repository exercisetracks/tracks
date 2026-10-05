#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the heart-rate review corpus: which samples of a recording the
server believes, and the load it then computes.

## Where the expected values come from

The backend's own functions, run in-process: calculators/hr_review.py and the
importer's `_compute_hr_tss` that feeds it. The phone's port
(com.tracks.core.metrics.HrReview, and LocalImporter.hrTss) replays every
case and must agree exactly. Regenerable: rerun after changing either.

## Inputs

Synthetic and seeded — never a real recording, which would be personal data
and would rarely hold every failure in one place. Each recording is a
physiologically shaped session (heart rate following lagged effort, drifting
upward, with sensor noise and the parser's half-bpm medians) with one defect
applied, or none:

  clean · a strap that starts late · a strap that reads low for minutes ·
  an optical lock on to step or pedal rate that follows it as it changes ·
  a multi-sample artifact · values outside what a heart can do · a dead
  sensor · a recording that is mostly missing

over running, trail running, walking, hiking, road and mountain biking, and
sports with no effort model at all; at 1 s and at smart-recording intervals,
with pauses, with speed or altitude missing, and with recordings too short
for a fit.

## Running it

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_hr_review_fixtures.py
"""

from __future__ import annotations

import json
import random
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, "/app")

from app.calculators.hr_review import _power, review_heart_rate, review_points  # noqa: E402
from app.services.fit_import import _compute_hr_tss  # noqa: E402
from app.spec.taxonomy import sport_type  # noqa: E402

OUT = Path(__file__).resolve().parent / "fixtures" / "hr_review.json"

SPORTS = ["running", "trail_running", "walking", "hiking", "cycling", "mountain_biking",
          "indoor_cycling", "strength_training", "rowing", None]
DEFECTS = ["clean", "late_start", "strap_low", "cadence_lock", "artifact", "impossible",
           "dead", "mostly_missing"]


def _session(rng: random.Random, sport: str | None, n: int, sampling: str,
             with_speed: bool, with_altitude: bool) -> dict:
    kind = sport_type(sport)
    # Sports with no effort model are synthesised as running: what matters
    # is a heart rate that moves with an effort, not which effort.
    model = kind if kind in ("running", "hiking", "cycling", "mtb") else "running"
    foot = model in ("running", "hiking")
    t = 0.0
    times, hrs, speeds, alts, cads = [], [], [], [], []
    alt = 200.0
    lagged = 0.0                         # a heart starts at rest
    base = rng.uniform(55, 75)
    # bpm per W/kg: running costs ~10 W/kg, walking ~3.5, riding ~2.
    gain = (rng.uniform(5.0, 7.5) if model == "running" else rng.uniform(11.0, 16.0)
            if model == "hiking" else rng.uniform(20.0, 30.0))
    for i in range(n):
        if i:
            if sampling == "smart":
                t += rng.choice([1.0, 1.0, 2.0, 3.0, 5.0, 7.0])
            elif sampling == "paused" and rng.random() < 0.004:
                t += rng.uniform(30, 600)
            else:
                t += 1.0
        phase = int(t // rng.choice([180, 240, 300])) % 3
        if foot:
            v = (2.4, 3.0, 3.6)[phase] if model == "running" else (1.1, 1.4, 1.7)[phase]
            grade = (0.08, -0.05, 0.0)[phase]
        else:
            v = (6.0, 8.5, 10.5)[phase]
            grade = (0.05, -0.03, 0.0)[phase]
        v = round(v + rng.uniform(-0.15, 0.15), 3)
        if speeds and rng.random() < 0.01:
            v = 0.0                      # a stop at a junction
        alt = round(alt + grade * v * (times and (t - times[-1]) or 1.0), 1)
        p = _power(model, v, grade)
        dt = t - times[-1] if times else 0.0
        if dt > 10:
            lagged = lagged * (40 / (40 + dt))
            dt = 1.0
        lagged = lagged + (dt / (40 + dt)) * (p - lagged)
        hr = base + gain * lagged + t / 3600 * rng.uniform(4, 10) + rng.uniform(-2.5, 2.5)
        hr = round(hr * 2) / 2           # the parser's rolling median yields halves
        times.append(t)
        hrs.append(hr)
        speeds.append(v if with_speed else None)
        alts.append(alt if with_altitude else None)
        c = (rng.choice([80.0, 81.0, 82.0, 83.0]) if foot else rng.choice([85.0, 88.0, 90.0]))
        cads.append(c if not kind == "strength" else None)
    return {"sport": sport, "times": times, "hrs": hrs, "speeds": speeds,
            "altitudes": alts, "cadences": cads}


def _defect(rng: random.Random, s: dict, defect: str) -> None:
    hrs, cads = s["hrs"], s["cadences"]
    n = len(hrs)
    if defect == "late_start":
        for i in range(min(n, rng.randint(30, 400))):
            hrs[i] = None
    elif defect == "strap_low":
        a = rng.randint(0, max(0, n - 500))
        for i in range(a, min(n, a + rng.randint(150, 500))):
            hrs[i] = float(rng.choice([88, 90, 92, 95]))
    elif defect == "cadence_lock":
        a = rng.randint(0, max(0, n - 500))
        double = rng.random() < 0.5
        for i in range(a, min(n, a + rng.randint(150, 500))):
            c = float(78 + (i // 15) % 10)
            cads[i] = c
            hrs[i] = 2 * c if double else c
    elif defect == "artifact":
        for _ in range(rng.randint(1, 4)):
            a = rng.randint(0, max(0, n - 20))
            v = float(rng.choice([45, 205, 215]))
            for i in range(a, min(n, a + rng.randint(4, 14))):
                hrs[i] = v
    elif defect == "impossible":
        for _ in range(rng.randint(3, 30)):
            hrs[rng.randrange(n)] = float(rng.choice([0, 12, 250, 255]))
    elif defect == "dead":
        for i in range(n):
            hrs[i] = None
    elif defect == "mostly_missing":
        keep = rng.randint(0, max(1, n // 6))
        for i in range(keep, n):
            hrs[i] = None


def _review_json(r) -> dict | None:
    if r is None:
        return None
    return {"usable": r.usable, "avg_hr": r.avg_hr, "max_hr": r.max_hr,
            "replaced": r.replaced, "samples": r.samples}


def review_cases() -> list[dict]:
    rng = random.Random(61)
    out = []
    for sport in SPORTS:
        for defect in DEFECTS:
            for sampling in ("1s", "smart", "paused"):
                n = rng.choice([90, 400, 800, 1000])
                s = _session(rng, sport, n, sampling,
                             with_speed=rng.random() > 0.1, with_altitude=rng.random() > 0.2)
                _defect(rng, s, defect)
                r = review_heart_rate(s["sport"], s["times"], s["hrs"], s["speeds"],
                                      s["altitudes"], s["cadences"])
                out.append({"defect": defect, "sampling": sampling, **s, "expect": _review_json(r)})
    # The empty and single-sample edges.
    for s in ({"sport": "running", "times": [], "hrs": [], "speeds": [], "altitudes": [], "cadences": []},
              {"sport": "running", "times": [0.0], "hrs": [140.0], "speeds": [3.0],
               "altitudes": [10.0], "cadences": [85.0]},
              {"sport": "running", "times": [0.0, 1.0], "hrs": [140.0, 300.0], "speeds": [3.0, 3.0],
               "altitudes": [None, None], "cadences": [None, None]}):
        r = review_heart_rate(s["sport"], s["times"], s["hrs"], s["speeds"], s["altitudes"], s["cadences"])
        out.append({"defect": "edge", "sampling": "1s", **s, "expect": _review_json(r)})
    return out


def import_cases() -> list[dict]:
    """The importer's hrTSS over data points, as the parser hands them over:
    the review's plumbing, not only its arithmetic."""
    rng = random.Random(67)
    t0 = datetime(2026, 3, 28, 6, 30, tzinfo=timezone.utc)
    out = []
    for sport in ("running", "hiking", "cycling", "strength_training"):
        for defect in ("clean", "strap_low", "cadence_lock", "dead", "mostly_missing"):
            s = _session(rng, sport, 700, "smart", with_speed=True, with_altitude=True)
            _defect(rng, s, defect)
            points = [{"recorded_at": t0 + timedelta(seconds=t), "heart_rate": h, "speed": v,
                       "altitude": a, "cadence": c}
                      for t, h, v, a, c in zip(s["times"], s["hrs"], s["speeds"],
                                               s["altitudes"], s["cadences"])]
            present = [h for h in s["hrs"] if h is not None]
            activity = {
                "sport": sport, "duration_seconds": int(s["times"][-1]) + 1,
                "distance_meters": round(sum(v or 0 for v in s["speeds"]), 1),
                "total_ascent": round(rng.uniform(0, 400), 1),
                # What the parser's recompute_summary would have written.
                "avg_heart_rate": round(sum(present) / len(present)) if present else 142,
                "max_heart_rate": round(max(present)) if present else 171,
            }
            case = {"defect": defect, "activity": activity,
                    "points": [{**p, "recorded_at": p["recorded_at"].isoformat()} for p in points],
                    "review": _review_json(review_points(sport, points)), "tss": {}}
            for thr in (None, 160.0):
                case["tss"][str(thr)] = _compute_hr_tss(dict(activity), thr, points)
            out.append(case)
    return out


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_hr_review_fixtures.py from the backend's own functions. "
                    "Regenerable; do not edit by hand.",
        "review": review_cases(),
        "import": import_cases(),
    }
    OUT.write_text(json.dumps(corpus, separators=(",", ":")) + "\n")
    changed = sum(1 for c in corpus["review"] if c["expect"] is not None)
    unusable = sum(1 for c in corpus["review"] if c["expect"] and not c["expect"]["usable"])
    print(f"wrote {OUT}: {len(corpus['review'])} reviews ({changed} changed, {unusable} unusable), "
          f"{len(corpus['import'])} imports")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
