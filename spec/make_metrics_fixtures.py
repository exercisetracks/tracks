#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the metrics corpus: what the server computes for a dashboard.

## Where the expected values come from

The backend's own functions, run in-process: calculators/training_load.py,
readiness.py, user_stats.py, dashboard_stats.py, the pure helpers in
api/metrics/performance.py and api/coaching/helpers.py. The phone's port in
mobile/core (com.tracks.core.metrics) replays every case and must agree
exactly.

This corpus is **regenerable**, unlike the sport taxonomy's: the Python is not
generated from the Kotlin or from a shared table, so it stays an independent
oracle. Rerun this after changing any of those files, and the Kotlin suite
says whether the port still agrees.

## Inputs

Synthetic and deterministic — a seeded generator, never real activities. Real
histories are personal data, and they would also be worse at the job: the
cases worth pinning are the ones a real history rarely contains in one place.
So the generated history deliberately includes gaps of several weeks, zero
durations, missing heart rate, null sports and distances, device TSS on some
rows and a stored effective TSS on others, 29 February, both DST changeovers,
activities at 00:00 and 23:59, and values chosen so a rounding lands exactly on
a half.

Each history is read in an account zone (``timezone``), because every day here
is the activity's local day (calculators/local_day.py). The first two are in
UTC; the others are in a zone behind UTC and one ahead of it, each across a
DST change, so the 00:00 and 23:59 UTC starts fall on the neighbouring local
day.

## Running it

Needs `app` importable and the app's settings present (importing the metrics
package reads config), but touches no database:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_metrics_fixtures.py
"""

from __future__ import annotations

import json
import random
import statistics
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.api.coaching.helpers import _acute_load_today, _ctl_atl_today  # noqa: E402
from app.api.metrics.helpers import _compute_tload_points, _effective_threshold_hr  # noqa: E402
from app.api.metrics.performance import (  # noqa: E402
    pace_curve_points,
    power_curve_points,
    race_predictions_from,
)
from app.calculators import dashboard_stats  # noqa: E402
from app.calculators.local_day import activity_local_date  # noqa: E402
from app.calculators.readiness import compute_readiness, readiness_history  # noqa: E402
from app.calculators.training_load import (  # noqa: E402
    calculate_ctl_atl_tsb,
    estimate_tss,
    load_calibration,
)
from app.calculators.user_stats import (  # noqa: E402
    best_rolling_avg_from,
    ftp_from,
    threshold_hr_from,
)

OUT = Path(__file__).resolve().parent / "fixtures" / "metrics.json"

SPORTS = ["running", "cycling", "swimming", "strength_training", "hiking", None]

# Raw FIT sport names reaching every sport type the no-heart-rate estimate
# distinguishes (training_load.estimated_tss), plus names it has never heard of.
ESTIMATE_SPORTS = [
    "running", "trail_running", "treadmill_running", "walking", "hiking", "cycling",
    "road_biking", "gravel_cycling", "mountain_biking", "indoor_cycling", "e_biking",
    "swimming", "open_water_swimming", "rowing", "cross_country_skiing", "alpine_skiing",
    "kayaking", "rock_climbing", "bouldering", "training", "strength_training", "yoga",
    "elliptical", "soccer", "golf", "triathlon", "horseback_riding", "made_up_sport", None,
]


# ── Inputs ───────────────────────────────────────────────────────────────────

def _activity(i: int, started_at: datetime, **kw) -> SimpleNamespace:
    base = dict(
        id=i, started_at=started_at, sport="running", distance_meters=None,
        duration_seconds=None, avg_heart_rate=None, max_heart_rate=None,
        training_stress_score=None, effective_tss=None, vo2max_estimate=None,
        avg_power=None, normalized_power=None, total_ascent=None,
    )
    base.update(kw)
    return SimpleNamespace(**base)


def _history(seed: int, start: date, days: int) -> list[SimpleNamespace]:
    """A seeded, deliberately awkward activity history, oldest first."""
    rng = random.Random(seed)
    rows = []
    i = 1
    d = start
    end = start + timedelta(days=days)
    while d < end:
        # A few multi-week gaps, so CTL has somewhere to decay to.
        if rng.random() < 0.02:
            d += timedelta(days=rng.randint(10, 30))
            continue
        for _ in range(rng.choice([0, 0, 1, 1, 1, 2])):
            hour, minute = rng.choice([(0, 0), (6, 30), (12, 15), (18, 45), (23, 59)])
            started = datetime(d.year, d.month, d.day, hour, minute, rng.randint(0, 59),
                               tzinfo=timezone.utc)
            sport = rng.choice(SPORTS)
            dur = rng.choice([0, None, rng.randint(600, 3 * 3600), rng.randint(1800, 5400)])
            dist = rng.choice([None, 0.0, round(rng.uniform(500, 42195), 1)])
            avg_hr = rng.choice([None, rng.randint(95, 175)])
            max_hr = rng.choice([None, (avg_hr or 140) + rng.randint(0, 40)])
            tss = rng.choice([None, None, None, round(rng.uniform(5, 250), 1)])
            eff = rng.choice([None, None, round(rng.uniform(5, 200), 3)])
            vo2 = rng.choice([None, None, round(rng.uniform(38, 58), 1)])
            # Climb, for the load estimated with no heart rate: none, flat, a
            # hill, and more than the climb-rate cap allows for.
            ascent = rng.choice([None, 0.0, round(rng.uniform(1, 400), 1), round(rng.uniform(400, 3000), 1)])
            rows.append(_activity(
                i, started, sport=sport, duration_seconds=dur, distance_meters=dist,
                avg_heart_rate=avg_hr, max_heart_rate=max_hr,
                training_stress_score=tss, effective_tss=eff, vo2max_estimate=vo2,
                total_ascent=ascent,
            ))
            i += 1
        d += timedelta(days=1)
    return rows


def _metrics(seed: int, start: date, days: int) -> list[SimpleNamespace]:
    rng = random.Random(seed)
    out = []
    for n in range(days):
        if rng.random() < 0.15:
            continue  # a night the watch was off
        out.append(SimpleNamespace(
            date=start + timedelta(days=n),
            # 0 is in the mix on purpose: the readiness code treats it as missing.
            hrv=rng.choice([None, 0.0, round(rng.uniform(35, 90), 1)]),
            resting_hr=rng.choice([None, 0.0, float(rng.randint(42, 62))]),
            sleep_hours=rng.choice([None, round(rng.uniform(4, 9.5), 2)]),
            sleep_score=rng.choice([None, None, float(rng.randint(30, 95))]),
        ))
    return out


# ── Serialisation ────────────────────────────────────────────────────────────

def _act_json(a) -> dict:
    return {
        "id": a.id, "started_at": a.started_at.isoformat(), "sport": a.sport,
        "distance_meters": a.distance_meters, "duration_seconds": a.duration_seconds,
        "avg_heart_rate": a.avg_heart_rate, "max_heart_rate": a.max_heart_rate,
        "training_stress_score": a.training_stress_score, "effective_tss": a.effective_tss,
        "vo2max_estimate": a.vo2max_estimate, "avg_power": a.avg_power,
        "normalized_power": a.normalized_power, "total_ascent": a.total_ascent,
    }


def _metric_json(m) -> dict:
    return {"date": m.date.isoformat(), "hrv": m.hrv, "resting_hr": m.resting_hr,
            "sleep_hours": m.sleep_hours, "sleep_score": m.sleep_score}


def _plain(v):
    """Dates to ISO strings, recursively, so the corpus is plain JSON."""
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def _readiness_json(r) -> dict:
    return _plain(dict(r.__dict__))


# ── Cases ────────────────────────────────────────────────────────────────────

def estimate_tss_cases() -> list[dict]:
    t = datetime(2026, 3, 1, 8, 0, tzinfo=timezone.utc)
    specs = [
        dict(training_stress_score=88.8),
        dict(training_stress_score=0.0, effective_tss=50.0),
        dict(effective_tss=42.125),
        dict(duration_seconds=0, avg_heart_rate=150),
        dict(duration_seconds=3600, avg_heart_rate=None),
        dict(duration_seconds=3600, avg_heart_rate=150, max_heart_rate=190),
        dict(duration_seconds=3600, avg_heart_rate=150, max_heart_rate=None),
        dict(duration_seconds=5400, avg_heart_rate=190, max_heart_rate=191),  # ratio clamps at 1.5
        dict(duration_seconds=900, avg_heart_rate=87, max_heart_rate=100),
        dict(duration_seconds=4500, avg_heart_rate=133, max_heart_rate=0),
        dict(duration_seconds=2700, avg_heart_rate=141, max_heart_rate=166),
    ]
    out = []
    for n, kw in enumerate(specs):
        a = _activity(n + 1, t, **kw)
        for thr in (None, 160.0, 150.0, 0.0, -5.0, 95.5):
            out.append({"activity": _act_json(a), "threshold_hr": thr,
                        "expect": estimate_tss(a, thr)})
    # A sweep that is certain to land some results on exact .x5 halves.
    for dur in range(60, 3601, 179):
        for hr in (100, 120, 125, 150):
            a = _activity(9000 + dur, t, duration_seconds=dur, avg_heart_rate=hr, max_heart_rate=200)
            out.append({"activity": _act_json(a), "threshold_hr": 100.0,
                        "expect": estimate_tss(a, 100.0)})
    # No heart rate at all: the load comes from sport, speed and climb. Every
    # sport type, both speed bands below/inside/above their ends, climb rates
    # either side of the cap, and the MTB and indoor multipliers on top.
    for sport in ESTIMATE_SPORTS:
        for dur, dist, ascent in (
            (3600, None, None), (3600, 0.0, 0.0), (3600, 3000.0, 0.0), (3600, 5400.0, 120.0),
            (3600, 10000.0, 50.0), (2700, 7920.0, None), (5400, 40000.0, 350.0),
            (14400, 12000.0, 900.0), (1800, 4000.0, 1200.0), (7200, 90000.0, 2400.0),
            (600, 2500.0, -3.0), (4321, 12345.6, 432.1),
        ):
            for disc in (None, "enduro"):
                a = _activity(8000, t, sport=sport, duration_seconds=dur,
                              distance_meters=dist, total_ascent=ascent)
                out.append({"activity": _act_json(a), "threshold_hr": 160.0, "mtb_discipline": disc,
                            "expect": estimate_tss(a, 160.0, disc)})
    # A sweep certain to land some estimates on exact .x5 halves.
    for dur in range(60, 7201, 353):
        for sport, dist, ascent in (("running", None, None), ("walking", 5000.0, 0.0),
                                    ("cycling", 30000.0, 100.0), ("trail_running", 9000.0, 450.0)):
            a = _activity(8500 + dur, t, sport=sport, duration_seconds=dur,
                          distance_meters=dist, total_ascent=ascent)
            out.append({"activity": _act_json(a), "threshold_hr": None,
                        "expect": estimate_tss(a, None)})
    # The sport multipliers, applied to the stored load when it is read.
    for sport in ("mountain_biking", "trail_biking", "indoor_cycling", "running"):
        for disc in (None, "xco", "enduro", "unknown"):
            for kw in (dict(effective_tss=47.35), dict(training_stress_score=60.0),
                       dict(duration_seconds=3600, avg_heart_rate=150, max_heart_rate=190),
                       dict(duration_seconds=3600)):
                a = _activity(7000, t, sport=sport, **kw)
                out.append({"activity": _act_json(a), "threshold_hr": 160.0, "mtb_discipline": disc,
                            "expect": estimate_tss(a, 160.0, disc)})
    # No heart rate and no distance: the sport alone, and stillness at zero.
    for sport in ("running", "walking", "hiking", "Yoga", "strength_training", "meditation",
                  "Breathwork", None, "rowing"):
        for dur in (600, 1799, 3600, 5423):
            a = _activity(8000 + dur, t, sport=sport, duration_seconds=dur)
            out.append({"activity": _act_json(a), "threshold_hr": None, "expect": estimate_tss(a, None)})
    return out


def calibration_cases() -> dict:
    """Histories built to hit each rule of load_calibration, and the
    no-heart-rate estimate scaled by what they give."""
    t0 = datetime(2026, 1, 5, 7, 0, tzinfo=timezone.utc)
    rng = random.Random(53)

    def measured(i, day, sport, tss, **kw):
        return _activity(i, t0 + timedelta(days=day, hours=kw.pop("hour", 0)), sport=sport,
                         duration_seconds=3600, distance_meters=kw.pop("dist", 10000.0),
                         total_ascent=kw.pop("ascent", 50.0), training_stress_score=tss, **kw)

    histories = {
        # Runs measuring well above the estimate; rides far below it (clamped).
        "above_and_clamped": [measured(i, i, "running", 80.0 + i) for i in range(8)]
        + [measured(100 + i, i, "cycling", 5.0) for i in range(6)],
        # Every ratio enormous: clamped at the top, pooled too.
        "clamped_high": [measured(i, i, "running", 400.0) for i in range(12)],
        # Four sessions of a sport: below its minimum, so only the pool speaks.
        "too_few": [measured(i, i, "running", 70.0) for i in range(4)]
        + [measured(10 + i, i, "swimming", 60.0) for i in range(4)]
        + [measured(20 + i, i, "hiking", 45.0) for i in range(3)],
        # Two sessions a day, differing only in ratio: the tie is broken by it.
        "same_day": [measured(i, i // 2, "running", 50.0 + 7 * (i % 2) + i, hour=i % 2)
                     for i in range(30)],
        # Thirty sessions whose load drifts: only the most recent twenty count.
        "recent_only": [measured(i, i, "running", 40.0 + 2.5 * i) for i in range(30)],
        # Heart rate but no stored load, and HR-less rows that must not count.
        "hr_measured": [_activity(i, t0 + timedelta(days=i), sport="running", duration_seconds=3000,
                                  distance_meters=9000.0, total_ascent=None,
                                  avg_heart_rate=rng.randint(135, 165), max_heart_rate=185)
                        for i in range(9)]
        + [_activity(50 + i, t0 + timedelta(days=i), sport="running", duration_seconds=3000,
                     distance_meters=9000.0) for i in range(9)],
        "empty": [],
    }
    out = {}
    for name, acts in histories.items():
        cal = {str(thr): load_calibration(acts, thr, None) for thr in (None, 160.0)}
        out[name] = {"activities": [_act_json(a) for a in acts], "expect": cal}
    scaled = []
    cals = [{}, {"running": 1.25}, {"*": 0.8}, {"running": 0.5, "*": 1.5}, {"cycling": 2.0},
            load_calibration(histories["above_and_clamped"], None, None)]
    for sport in ("running", "trail_running", "cycling", "mountain_biking", "indoor_cycling",
                  "walking", "swimming", "yoga", None):
        for dur, dist, ascent in ((3600, 10000.0, 50.0), (2700, None, None), (5400, 40000.0, 700.0),
                                  (0, 5000.0, 0.0), (4321, 12345.6, 432.1)):
            for cal in cals:
                for disc in (None, "enduro"):
                    a = _activity(9900, t0, sport=sport, duration_seconds=dur, distance_meters=dist,
                                  total_ascent=ascent)
                    scaled.append({"activity": _act_json(a), "calibration": cal, "mtb_discipline": disc,
                                   "expect": estimate_tss(a, None, disc, cal)})
    return {"histories": out, "scaled": scaled}


def ctl_cases() -> list[dict]:
    rng = random.Random(7)
    out = []
    for n in range(6):
        start = date(2024, 2, 20) + timedelta(days=n * 40)
        loads = []
        d = start
        for _ in range(rng.randint(1, 90)):
            d += timedelta(days=rng.choice([1, 1, 1, 2, 5]))
            loads.append({"date": d, "tss": rng.choice([0.0, 12.25, 37.5, rng.uniform(0, 300)])})
        out.append({"daily_loads": _plain(loads), "expect": _plain(calculate_ctl_atl_tsb(loads))})
    out.append({"daily_loads": [], "expect": []})
    one = [{"date": date(2026, 1, 1), "tss": 100.0}]
    out.append({"daily_loads": _plain(one), "expect": _plain(calculate_ctl_atl_tsb(one))})
    return out


def readiness_cases() -> list[dict]:
    rng = random.Random(11)
    M = lambda **kw: SimpleNamespace(**{"hrv": None, "resting_hr": None,
                                        "sleep_hours": None, "sleep_score": None, **kw})
    out = []
    hand = [
        (None, [], None, None, None),
        (M(hrv=60.0, resting_hr=50.0, sleep_hours=8.0), [M(hrv=60.0, resting_hr=50.0)] * 7, None, None, None),
        (M(hrv=40.0, resting_hr=60.0, sleep_hours=5.9), [M(hrv=60.0, resting_hr=50.0)] * 3, -35.0, None, None),
        (M(sleep_score=79.9), [], 5.0, None, None),
        (None, [], -130.0, None, None),
        (None, [], 25.0, None, None),
        (None, [], None, 0.0, 0.0),
        (M(hrv=66.0), [M(hrv=60.0)], None, 90.0, 60.0),
        (M(hrv=0.0, resting_hr=0.0), [M(hrv=0.0)], None, 30.0, 10.0),
        (M(resting_hr=55.5, sleep_hours=6.45), [M(resting_hr=50.0), M(resting_hr=51.0)], -10.0, None, None),
    ]
    for today, prior, tsb, acute, chronic in hand:
        out.append({
            "today": _metric_json(SimpleNamespace(date=date(2026, 1, 1), **today.__dict__)) if today else None,
            "recent": [_metric_json(SimpleNamespace(date=date(2025, 12, 31), **p.__dict__)) for p in prior],
            "tsb": tsb, "acute_load": acute, "chronic_load": chronic,
            "expect": _readiness_json(compute_readiness(today, prior, tsb=tsb,
                                                        acute_load=acute, chronic_load=chronic)),
        })
    for _ in range(120):
        def rnd():
            return M(hrv=rng.choice([None, 0.0, round(rng.uniform(30, 100), 1)]),
                     resting_hr=rng.choice([None, float(rng.randint(40, 70))]),
                     sleep_hours=rng.choice([None, round(rng.uniform(3, 10), 2)]),
                     sleep_score=rng.choice([None, float(rng.randint(20, 99))]))
        today = rng.choice([None, rnd(), rnd()])
        prior = [rnd() for _ in range(rng.randint(0, 7))]
        tsb = rng.choice([None, round(rng.uniform(-140, 40), 1)])
        acute = rng.choice([None, rng.uniform(0, 200)])
        chronic = rng.choice([None, rng.uniform(0, 120)])
        out.append({
            "today": _metric_json(SimpleNamespace(date=date(2026, 1, 1), **today.__dict__)) if today else None,
            "recent": [_metric_json(SimpleNamespace(date=date(2025, 12, 31), **p.__dict__)) for p in prior],
            "tsb": tsb, "acute_load": acute, "chronic_load": chronic,
            "expect": _readiness_json(compute_readiness(today, prior, tsb=tsb,
                                                        acute_load=acute, chronic_load=chronic)),
        })
    return out


def history_cases() -> dict:
    """Two awkward histories; every dashboard figure computed over each."""
    cases = []
    for seed, start, days, tz in ((1, date(2024, 1, 15), 320, "UTC"),
                                  (2, date(2025, 10, 20), 160, "UTC"),
                                  # Behind UTC, across the March change.
                                  (3, date(2026, 2, 1), 120, "America/Los_Angeles"),
                                  # Ahead of UTC, across the April change.
                                  (4, date(2026, 3, 1), 120, "Australia/Sydney")):
        acts = _history(seed, start, days)
        tl_rows = [a for a in acts]
        # The fitness series runs to "today", pinned here a fortnight past the
        # last day so the rest-day decay tail is part of what is compared.
        tl_today = start + timedelta(days=days + 14)
        case = {"seed": seed, "timezone": tz, "today": tl_today.isoformat(),
                "activities": [_act_json(a) for a in acts]}
        case["tload"] = {str(thr): _plain(_compute_tload_points(tl_rows, thr, tz_name=tz, today=tl_today))
                         for thr in (None, 160.0)}
        case["summary"] = dashboard_stats.summary(acts, 3)
        case["by_sport"] = dashboard_stats.by_sport(acts)
        case["calendar"] = dashboard_stats.activity_calendar(acts, tz)
        case["vo2max"] = dashboard_stats.vo2max_history(acts, tz)
        # The same history with no device VO₂max anywhere: the pace-based estimate.
        no_device = [SimpleNamespace(**{**vars(a), "vo2max_estimate": None}) for a in acts]
        case["vo2max_from_pace"] = dashboard_stats.vo2max_history(no_device, tz)
        case["weekly_volume"] = dashboard_stats.weekly_volume(acts, tz)
        case["activity_load"] = {str(thr): dashboard_stats.activity_load(acts, thr, tz_name=tz)
                                 for thr in (None, 160.0)}
        case["trends"] = {
            f"{bucket}:{thr}": dashboard_stats.trends(acts, thr, bucket, tz_name=tz)
            for bucket in ("week", "month", "year") for thr in (None, 160.0)
        }
        # Live readiness inputs (dashboard gauge), as of a few different days.
        calibration = load_calibration(acts, 160.0, None, tz)
        tss_by_date: dict[date, float] = {}
        for a in acts:
            d = activity_local_date(a.started_at, tz)
            tss_by_date[d] = tss_by_date.get(d, 0.0) + estimate_tss(a, 160.0, None, calibration)
        live = []
        for offset in (0, 1, 3, 30, days + 5):
            today = start + timedelta(days=offset)
            ctl, atl, ctl7 = _ctl_atl_today(tss_by_date, today)
            live.append({"today": today.isoformat(), "ctl": ctl, "atl": atl, "ctl_7d_ago": ctl7,
                         "acute": _acute_load_today(tss_by_date, today)})
        case["live_load"] = {"threshold_hr": 160.0, "points": live}
        # The athlete's calibration of the no-heart-rate estimate, from the
        # whole history, under each threshold and an MTB discipline.
        case["calibration"] = [
            {"threshold_hr": thr, "mtb_discipline": disc, "expect": load_calibration(acts, thr, disc, tz)}
            for thr in (None, 160.0) for disc in (None, "enduro")
        ]
        # Readiness history over the last 60 days of the span.
        mets = _metrics(seed + 100, start, days + 10)
        today = start + timedelta(days=days)
        load_from = today - timedelta(days=60 - 1) - timedelta(days=7)
        hist_metrics = [m for m in mets if m.date >= load_from]
        # The whole history up to today, as the live score sees it.
        hist_acts = [a for a in acts if activity_local_date(a.started_at, tz) <= today]
        case["readiness_history"] = {
            "metrics": [_metric_json(m) for m in hist_metrics],
            "activity_ids": [a.id for a in hist_acts],
            "today": today.isoformat(), "days": 60, "threshold_hr": 160.0,
            "expect": _plain(readiness_history(hist_metrics, hist_acts, today, 60, 160.0, tz_name=tz)),
        }
        cases.append(case)
    return {"histories": cases, "live_load_empty": _ctl_atl_today({}, date(2026, 1, 1))}


def threshold_cases() -> list[dict]:
    out = []
    S = lambda **kw: SimpleNamespace(**kw)
    for us, in [
        (None,),
        (S(threshold_hr_mode="manual", threshold_hr_manual=168, threshold_hr_auto=160),),
        (S(threshold_hr_mode="manual", threshold_hr_manual=0, threshold_hr_auto=160),),
        (S(threshold_hr_mode="manual", threshold_hr_manual=None, threshold_hr_auto=160),),
        (S(threshold_hr_mode="auto", threshold_hr_manual=168, threshold_hr_auto=160),),
        (S(threshold_hr_mode="auto", threshold_hr_manual=168, threshold_hr_auto=None),),
        (S(threshold_hr_mode=None, threshold_hr_manual=None, threshold_hr_auto=171),),
    ]:
        out.append({"settings": None if us is None else dict(us.__dict__),
                    "expect": _effective_threshold_hr(us)})
    return out


def performance_cases() -> list[dict]:
    rng = random.Random(23)
    out = []
    durations = [5, 15, 30, 60, 300, 600, 1200, 3600]
    distances = [400, 1000, 1609, 3000, 5000, 10000, 21097, 42195]
    for n in range(8):
        power = [[rng.choice(durations), round(rng.uniform(80, 1200), rng.choice([0, 3]))]
                 for _ in range(rng.randint(0, 25))]
        chosen = rng.sample(distances, rng.randint(0, len(distances)))
        pace = [[d, rng.uniform(2.0, 6.5)] for d in chosen for _ in range(rng.randint(1, 3))]
        grouped_p: dict = {}
        for dur, w in power:
            grouped_p[dur] = max(grouped_p.get(dur, w), w)
        grouped_s: dict = {}
        for dist, s in pace:
            grouped_s[dist] = max(grouped_s.get(dist, s), s)
        out.append({
            "power_bests": power, "pace_bests": pace,
            "power_curve": power_curve_points(sorted(grouped_p.items())),
            "pace_curve": pace_curve_points(sorted(grouped_s.items())),
            "race_predictions": race_predictions_from(grouped_s),
        })
    return out


def user_stats_cases() -> list[dict]:
    rng = random.Random(31)
    out = []
    for n in range(10):
        acts = []
        series = {}
        for i in range(rng.randint(0, 12)):
            avg = rng.randint(120, 175)
            a = SimpleNamespace(id=i + 1, avg_heart_rate=avg, max_heart_rate=avg + rng.choice([5, 20, 30, 45]),
                                avg_power=rng.choice([None, 0, rng.randint(120, 320)]),
                                normalized_power=rng.choice([None, rng.randint(150, 340)]))
            acts.append(a)
            t0 = 1_700_000_000.0 + i * 86400
            pts = []
            t = t0
            for _ in range(rng.choice([10, 29, 30, 400, 900])):
                t += rng.choice([1.0, 1.0, 1.0, 2.0, 7.5, 60.0])
                pts.append([t, float(rng.randint(90, 190)) if rng.random() > 0.01 else 250.0])
            series[a.id] = pts
        rolling = {aid: best_rolling_avg_from([tuple(p) for p in pts], 1200) for aid, pts in series.items()}
        out.append({
            "activities": [dict(a.__dict__) for a in acts],
            "points": {str(k): v for k, v in series.items()},
            "rolling": {str(k): v for k, v in rolling.items()},
            "threshold_hr": threshold_hr_from(list(acts), lambda a: rolling[a.id]),
            "ftp": ftp_from(list(acts), lambda a: rolling[a.id]),
        })
    return out


def py_math_cases() -> dict:
    """The Python numerics the port has to reproduce, pinned directly."""
    rng = random.Random(41)
    rounds = []
    values = [0.5, 1.5, 2.5, -0.5, -2.5, 2.675, 1.005, 0.125, 0.375, 12.25, 12.35,
              1e-7, 123456.789, 1e22, -0.0, 5e-324, 0.045, 1.0000000000000002]
    values += [rng.uniform(-1000, 1000) for _ in range(200)]
    values += [n / 8 for n in range(-40, 41)] + [n / 1000 + 0.0005 for n in range(0, 200, 7)]
    for v in values:
        for nd in (0, 1, 2, 4):
            rounds.append({"x": v, "n": nd, "round": round(v, nd), "fixed": f"{v:.{nd}f}"})
    for v in (0.5, 1.5, 2.5, -1.5, 3.4999999999999996, 1e16 + 2):
        rounds.append({"x": v, "n": None, "round": float(round(v)), "fixed": None})
    stats = []
    for _ in range(60):
        data = [rng.choice([0.0, 12.25, rng.uniform(0, 300), 1e-9, 1e9]) for _ in range(rng.randint(1, 30))]
        stats.append({"data": data, "mean": statistics.mean(data), "pstdev": statistics.pstdev(data)})
    for data in ([1.0], [0.1, 0.2, 0.3], [1e308, 1e308, -1e308], [2.5, 2.5, 2.5], [0.0, 0.0]):
        stats.append({"data": data, "mean": statistics.mean(data), "pstdev": statistics.pstdev(data)})
    medians = []
    for _ in range(30):
        data = [rng.randint(90, 190) for _ in range(rng.randint(1, 12))]
        medians.append({"data": data, "median": float(statistics.median(data))})
    return {"round": rounds, "stats": stats, "median": medians}


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_metrics_fixtures.py from the backend's own functions. "
                    "Regenerable; do not edit by hand.",
        "py_math": py_math_cases(),
        "estimate_tss": estimate_tss_cases(),
        "calibration": calibration_cases(),
        "ctl_atl_tsb": ctl_cases(),
        "threshold_hr": threshold_cases(),
        "readiness": readiness_cases(),
        "performance": performance_cases(),
        "user_stats": user_stats_cases(),
        **history_cases(),
    }
    OUT.write_text(json.dumps(corpus, separators=(",", ":"), allow_nan=False) + "\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
