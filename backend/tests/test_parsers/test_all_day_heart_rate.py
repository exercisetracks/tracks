# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""All-day heart rate from the watch's monitoring files.

It was in every monitoring record and read by nothing, so the Health page
had a resting heart rate and no heart rate. The watch's 0 means "not
measured" and a reading no person has is a sensor glitch; neither is kept.
"""
from datetime import timedelta
from pathlib import Path

from app.models.metrics import DailyMetric
from app.parsers.daily_health import DailyHealthParser
from app.calculators.local_day import user_today

SAMPLE = Path("/spec/fixtures/fit/monitoring.fit")


def test_the_monitoring_records_heart_rate_is_kept_by_the_minute():
    out = DailyHealthParser().parse(SAMPLE)
    series = [p for day in out["days"] for p in day["hr_series"]]
    assert series, "the sample carries heart rate on its monitoring records"
    assert all(25 <= bpm <= 240 for _, bpm in series), "0 and 250 are dropped"
    for day in out["days"]:
        minutes = [m for m, _ in day["hr_series"]]
        assert minutes == sorted(minutes), "by minute of the local day"


def test_the_day_reports_its_heart_rate_and_serves_the_curve(client, db, user):
    today = user_today(db, user.id)
    db.add(DailyMetric(user_id=user.id, date=today - timedelta(days=1),
                       extra={"hr_series": [[600, 60], [601, 90], [602, 75]]}))
    db.flush()
    row = db.query(DailyMetric).filter_by(user_id=user.id).one()
    assert (row.avg_hr, row.min_hr, row.max_hr) == (75.0, 60, 90)
    days = client.get("/health/heart-rate").json()
    assert days[0]["points"] == [[600, 60], [601, 90], [602, 75]]
