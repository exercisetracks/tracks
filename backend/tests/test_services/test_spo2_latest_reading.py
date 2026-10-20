# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The day's SpO2 is the latest reading, and the chart has every one.

It used to be the day's average, which the lower readings of a night's sleep
dragged a couple of points under what the watch's glance shows (93 on the page
beside 95 on the wrist).
"""
from datetime import date

from app.models.metrics import DailyMetric
from app.services.fit_import import _write_daily_metrics


def _write(db, user, **day):
    _write_daily_metrics(db, {"type": "daily", "date": date(2026, 7, 17), **day},
                         device_id=None, user_id=user.id)
    db.flush()
    return db.query(DailyMetric).filter_by(user_id=user.id).one()


def test_the_day_shows_the_latest_reading_not_the_average(db, user):
    row = _write(db, user, metrics={"spo2": 93.0},
                 spo2_series=[[60, 91], [180, 92], [1000, 95]])
    assert row.spo2 == 95.0
    assert row.extra["spo2_series"] == [[60, 91], [180, 92], [1000, 95]]


def test_a_later_file_with_a_later_reading_moves_the_day(db, user):
    _write(db, user, metrics={}, spo2_series=[[60, 91], [1000, 95]])
    row = _write(db, user, metrics={}, spo2_series=[[1200, 97], [60, 91]])
    assert row.spo2 == 97.0
    assert [m for m, _ in row.extra["spo2_series"]] == [60, 1000, 1200]


def test_an_older_file_arriving_late_does_not_pull_the_day_back(db, user):
    _write(db, user, metrics={}, spo2_series=[[1000, 95]])
    row = _write(db, user, metrics={}, spo2_series=[[60, 90]])
    assert row.spo2 == 95.0
