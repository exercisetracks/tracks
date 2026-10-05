# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Dashboard and trend figures, as pure functions over activity rows.

## Why these are not SQL aggregates any more

They were — `func.sum`, `func.avg`, `GROUP BY sport` — and that was the right
call while the server was the only thing that ever drew a dashboard. It is not
now: the phone computes the same figures from its own rows with no server at
all (see docs/offline-first.md, "Porting the server logic"), and the two have
to agree. A figure that only exists as a SQL statement cannot be checked
against the Kotlin port, because nothing can run it outside Postgres. As pure
Python it runs in `spec/make_metrics_fixtures.py`, and the phone's suite
replays what it produced.

The row *selection* (date range, hidden sports, claimed devices) stays in SQL
in the endpoints, where it belongs; only the arithmetic moved.

## Semantics, stated because SQL used to decide them implicitly

- A sum over no non-null values is None, not 0 — as `SUM()` was.
- Averages are plain float means in the order rows arrive (started_at). Postgres
  averaged integer durations in exact `numeric`; the difference is below 1e-12
  of a minute, and every average here is rounded to one or two decimals.
- By-sport ties (equal activity counts) order by sport name. `ORDER BY count
  DESC` left them in whatever order the planner produced, which is not
  something two implementations can agree on.

- A day is the day in the account's zone (``tz_name``, the account's
  ``user_settings.timezone``), not the UTC day the start is stored in — an
  evening run in California is not tomorrow's. See calculators/local_day.py.
  The phone's port receives the day already worked out
  (``MetricActivity.date``), by the same rule.

Rows are anything with the attributes each function reads (ORM rows, named
tuples, SimpleNamespace in the fixture generator). Dates come out as ISO
strings, the shape the dashboard cache has always stored.
"""

from __future__ import annotations

from datetime import date, timedelta
from statistics import mean, pstdev

from app.calculators.local_day import activity_local_date
from app.calculators.plan.base import _sport_family, calculate_vdot
from app.calculators.training_load import estimate_tss, load_calibration


def _sum_or_none(values) -> float | int | None:
    """SQL SUM(): None when there is nothing to add, else the total."""
    present = [v for v in values if v is not None]
    if not present:
        return None
    total = present[0]
    for v in present[1:]:
        total = total + v
    return total


def _avg_or_none(values) -> float | None:
    """SQL AVG() as a float mean, skipping nulls."""
    present = [v for v in values if v is not None]
    if not present:
        return None
    return _sum_or_none(present) / len(present)


def summary(rows, device_count: int) -> dict:
    """Headline stats for the dashboard: counts, totals, averages."""
    rows = list(rows)
    total_dist_m = _sum_or_none(r.distance_meters for r in rows)
    total_dur_s = _sum_or_none(r.duration_seconds for r in rows)
    # Average distance only over activities that have GPS (distance > 0).
    avg_dist_m = _avg_or_none(
        r.distance_meters for r in rows
        if r.distance_meters is not None and r.distance_meters > 0
    )
    avg_dur_s = _avg_or_none(r.duration_seconds for r in rows)
    sports = {r.sport for r in rows if r.sport is not None}
    return {
        "activity_count":       len(rows),
        "total_distance_km":    round(total_dist_m / 1000, 2) if total_dist_m else None,
        "total_duration_hours": round(total_dur_s / 3600, 2) if total_dur_s else None,
        "sport_count":          len(sports),
        "device_count":         device_count,
        "avg_distance_km":      round(avg_dist_m / 1000, 2) if avg_dist_m else None,
        "avg_duration_minutes": round(avg_dur_s / 60, 1) if avg_dur_s else None,
    }


def by_sport(rows) -> list[dict]:
    """Per-sport totals, most-practised sport first (ties by name)."""
    groups: dict[str, list] = {}
    for r in rows:
        if r.sport is None:
            continue
        groups.setdefault(r.sport, []).append(r)
    ordered = sorted(groups.items(), key=lambda kv: (-len(kv[1]), kv[0]))
    out = []
    for sport, members in ordered:
        dist = _sum_or_none(m.distance_meters for m in members)
        dur = _sum_or_none(m.duration_seconds for m in members)
        avg_dur = _avg_or_none(m.duration_seconds for m in members)
        out.append({
            "sport":                sport,
            "activity_count":       len(members),
            "total_distance_km":    round(dist / 1000, 2) if dist else None,
            "total_duration_hours": round(dur / 3600, 2) if dur else None,
            "avg_duration_minutes": round(avg_dur / 60, 1) if avg_dur else None,
        })
    return out


def activity_calendar(rows, tz_name: str | None = None) -> list[dict]:
    """Activities per calendar day, for the contribution heatmap."""
    counts: dict[date, int] = {}
    for r in rows:
        d = activity_local_date(r.started_at, tz_name)
        counts[d] = counts.get(d, 0) + 1
    return [{"date": d.isoformat(), "count": c} for d, c in sorted(counts.items())]


def vo2max_history(rows, tz_name: str | None = None) -> list[dict]:
    """One VO₂max point per day: the last reading that day, in row order.

    With no device reading anywhere in ``rows``, an estimate from running pace
    instead (``running_vo2max_history``) — a phone-only runner otherwise has no
    VO₂max at all, because only a watch's firmware writes one. A device's
    figure always wins: it has heart rate to work from, and the two are not
    mixed, so the line never jumps between methods.
    """
    seen: dict[date, float] = {}
    for r in rows:
        if r.vo2max_estimate is None:
            continue
        seen[activity_local_date(r.started_at, tz_name)] = r.vo2max_estimate
    if not seen:
        return running_vo2max_history(rows, tz_name)
    return [{"date": d.isoformat(), "value": v} for d, v in sorted(seen.items())]


# The runs a pace-based VO₂max is read from: long enough that the effort is
# aerobic (Daniels' formula is fitted on races from 1500 m up, and its %VO₂max
# term assumes several minutes of running), and fast or slow enough to be
# running — past 6.5 m/s is a GPS glitch or a bike logged as a run, under
# 1.5 m/s is a walk.
_RUN_VO2_MIN_M = 1500.0
_RUN_VO2_MIN_S = 480
_RUN_VO2_MIN_MPS = 1.5
_RUN_VO2_MAX_MPS = 6.5
# The best run of the last 90 days stands for current fitness: fitness moves
# over weeks, and one easy day should not read as a collapse.
_RUN_VO2_WINDOW_DAYS = 90


def running_vo2max_history(rows, tz_name: str | None = None) -> list[dict]:
    """VO₂max estimated from running pace, one point per day with a run.

    Each run's Daniels & Gilbert VDOT (the VO₂max its pace and duration would
    demand run as a race), and each day's value is the best of the trailing
    ``_RUN_VO2_WINDOW_DAYS``. Without heart rate there is no telling a race
    from a jog, so the best recent effort is the honest reading: it can only
    under-state fitness (no run is faster than the runner), and races and
    hard sessions — the runs closest to maximal — are exactly what it keeps.
    Rough, and labelled so by where it comes from; a watch replaces it.
    """
    runs: list[tuple[date, float]] = []
    for r in rows:
        if _sport_family(getattr(r, "sport", None) or "") != "running":
            continue
        dist = getattr(r, "distance_meters", None)
        dur = getattr(r, "duration_seconds", None)
        if not dist or not dur or dist < _RUN_VO2_MIN_M or dur < _RUN_VO2_MIN_S:
            continue
        speed = dist / dur
        if speed < _RUN_VO2_MIN_MPS or speed > _RUN_VO2_MAX_MPS:
            continue
        runs.append((activity_local_date(r.started_at, tz_name), calculate_vdot(dist, dur)))
    out = []
    for d in sorted({d for d, _ in runs}):
        start = d - timedelta(days=_RUN_VO2_WINDOW_DAYS)
        best = max(v for day, v in runs if start < day <= d)
        out.append({"date": d.isoformat(), "value": round(best, 1)})
    return out


def weekly_volume(rows, tz_name: str | None = None) -> list[dict]:
    """Distance and duration per ISO week (Monday start)."""
    buckets: dict[date, dict] = {}
    for r in rows:
        if r.started_at is None:
            continue
        d = activity_local_date(r.started_at, tz_name)
        ws = d - timedelta(days=d.weekday())
        b = buckets.setdefault(ws, {"dist": 0.0, "dur": 0.0, "count": 0})
        b["dist"] += r.distance_meters or 0.0
        b["dur"] += r.duration_seconds or 0.0
        b["count"] += 1
    return [
        {
            "week_start":     ws.isoformat(),
            "distance_km":    round(b["dist"] / 1000, 2) if b["dist"] else None,
            "duration_hours": round(b["dur"] / 3600, 2) if b["dur"] else None,
            "activity_count": b["count"],
        }
        for ws, b in sorted(buckets.items())
    ]


def activity_load(rows, threshold_hr: float | None, mtb_discipline: str | None = None,
                  calibration: dict[str, float] | None = None,
                  tz_name: str | None = None) -> list[dict]:
    """Per-activity TSS dots for the fitness-chart overlay.

    ``calibration``: the whole history's (see training_load.load_calibration);
    by default the one ``rows`` give, right only when ``rows`` are all of it.
    """
    if calibration is None:
        calibration = load_calibration(rows, threshold_hr, mtb_discipline, tz_name)
    return [
        {
            "activity_id": r.id,
            "date":        activity_local_date(r.started_at, tz_name).isoformat(),
            "sport":       r.sport,
            "tss":         estimate_tss(r, threshold_hr, mtb_discipline, calibration),
        }
        for r in rows
        if r.started_at
    ]


_TREND_BUCKETS = {"week", "month", "year"}


def _period_start(d: date, bucket: str) -> date:
    if bucket == "week":
        return d - timedelta(days=d.weekday())  # ISO Monday
    if bucket == "month":
        return d.replace(day=1)
    return d.replace(month=1, day=1)  # year


def trends(rows, threshold_hr: float | None, bucket: str, mtb_discipline: str | None = None,
           calibration: dict[str, float] | None = None, tz_name: str | None = None) -> list[dict]:
    """Volume and TSS per week/month/year, with Foster's monotony and strain.

    Monotony = mean(daily TSS) / stdev(daily TSS) over the bucket's span, rest
    days counted as 0; strain = total TSS × monotony.
    """
    if bucket not in _TREND_BUCKETS:
        raise ValueError(f"bucket must be one of: {', '.join(sorted(_TREND_BUCKETS))}")
    if calibration is None:
        calibration = load_calibration(rows, threshold_hr, mtb_discipline, tz_name)

    daily_tss: dict[date, float] = {}
    buckets: dict[date, dict] = {}
    for r in rows:
        d = activity_local_date(r.started_at, tz_name)
        key = _period_start(d, bucket)
        tss = estimate_tss(r, threshold_hr, mtb_discipline, calibration)
        daily_tss[d] = daily_tss.get(d, 0.0) + tss
        b = buckets.setdefault(key, {"count": 0, "dist": 0.0, "dur": 0.0, "tss": 0.0, "days": set()})
        b["count"] += 1
        b["dist"] += r.distance_meters or 0.0
        b["dur"] += r.duration_seconds or 0.0
        b["tss"] += tss
        b["days"].add(d)

    def _monotony_strain(b: dict) -> tuple[float | None, float | None]:
        if not b["days"] or b["tss"] == 0:
            return None, None
        # Fill in rest days with 0 TSS for a correct standard deviation.
        period_days = sorted(b["days"])
        start, end = period_days[0], period_days[-1]
        all_days_tss = []
        cur = start
        while cur <= end:
            all_days_tss.append(daily_tss.get(cur, 0.0))
            cur += timedelta(days=1)
        if len(all_days_tss) < 2:
            return None, None
        avg = mean(all_days_tss)
        std = pstdev(all_days_tss)
        if std == 0:
            return None, None
        mono = round(avg / std, 2)
        strain = round(b["tss"] * mono, 1)
        return mono, strain

    out = []
    for key, b in sorted(buckets.items()):
        mono, strain = _monotony_strain(b)
        out.append({
            "period_start":         key.isoformat(),
            "activity_count":       b["count"],
            "total_distance_km":    round(b["dist"] / 1000, 2) if b["dist"] else None,
            "total_duration_hours": round(b["dur"] / 3600, 2) if b["dur"] else None,
            "total_tss":            round(b["tss"], 1) if b["tss"] else None,
            "monotony":             mono,
            "strain":               strain,
        })
    return out
