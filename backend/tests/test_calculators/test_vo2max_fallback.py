# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""VO₂max for someone with no watch: estimated from running pace.

Only a watch's firmware writes a VO₂max, so a runner recording on the phone
alone had an empty gauge whatever they ran. The fallback reads each run's
Daniels VDOT and keeps the best of the last 90 days. The phone's port replays
the same histories from spec/fixtures/metrics.json.

Dates are fixed: nothing here depends on today.
"""

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from app.calculators import dashboard_stats
from app.calculators.plan.base import calculate_vdot

T0 = datetime(2026, 3, 2, 7, 0, tzinfo=timezone.utc)


def _run(days, distance, seconds, sport="running", vo2=None):
    return SimpleNamespace(started_at=T0 + timedelta(days=days), sport=sport, distance_meters=distance,
                           duration_seconds=seconds, vo2max_estimate=vo2)


def test_a_phone_only_runner_gets_a_vo2max_from_their_pace():
    history = dashboard_stats.vo2max_history([_run(0, 5000.0, 25 * 60)])
    assert history == [{"date": "2026-03-02", "value": round(calculate_vdot(5000.0, 1500), 1)}]
    assert 38 < history[0]["value"] < 39   # a 25-minute 5K is VDOT ~38.3


def test_an_easy_day_does_not_read_as_lost_fitness():
    """The best run of the last 90 days stands: a jog after a race keeps the race's figure."""
    history = dashboard_stats.vo2max_history([_run(0, 5000.0, 20 * 60), _run(2, 8000.0, 50 * 60)])
    assert history[1]["value"] == history[0]["value"]
    # ...until the race falls out of the window.
    later = dashboard_stats.vo2max_history([_run(0, 5000.0, 20 * 60), _run(95, 8000.0, 50 * 60)])
    assert later[1]["value"] < later[0]["value"]


def test_walks_short_runs_glitches_and_rides_are_not_read():
    rows = [
        _run(0, 1000.0, 5 * 60),                 # too short to be aerobic
        _run(1, 5000.0, 60 * 60),                # a walk, not a run
        _run(2, 40000.0, 60 * 60),               # 11 m/s: a GPS glitch or a ride logged as a run
        _run(3, 30000.0, 60 * 60, sport="cycling"),
    ]
    assert dashboard_stats.vo2max_history(rows) == []


def test_a_device_reading_replaces_the_estimate_entirely():
    """Heart-rate-based firmware beats pace; the line never mixes the two methods."""
    rows = [_run(0, 5000.0, 20 * 60), _run(1, 5000.0, 30 * 60, vo2=47.0)]
    assert dashboard_stats.vo2max_history(rows) == [{"date": "2026-03-03", "value": 47.0}]


@pytest.mark.parametrize("sport", ["running", "trail_running", "treadmill_running"])
def test_every_kind_of_run_counts(sport):
    assert dashboard_stats.vo2max_history([_run(0, 5000.0, 25 * 60, sport=sport)])
