# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Auto thresholds: manual, else the latest field test, else full history.

The server used to write these columns from two places — the history
recompute (every server start) and the matcher (when a test was completed) —
and whichever ran last won, so a restart silently undid a field test. The
phone applies the rule above; these pin the server to the same one.
"""
from datetime import date, datetime, timedelta, timezone

from app.calculators.user_stats import recalculate_auto_values
from app.models.activity import Activity, DataPoint, PowerBest
from app.models.training_plan import PlannedWorkout
from app.models.user_settings import UserSettings

_BASE = datetime.now(timezone.utc).replace(microsecond=0) - timedelta(days=30)


def _settings(db, user):
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        us = UserSettings(user_id=user.id)
        db.add(us)
    us.ftp_mode = "auto"
    us.threshold_hr_mode = "auto"
    db.flush()
    return us


def _ride_with_power(db, user, watts, start):
    """A 40-minute ride whose best 20 minutes average `watts`."""
    a = Activity(user_id=user.id, sport="cycling", started_at=start,
                 duration_seconds=2400, avg_power=watts, max_heart_rate=170)
    db.add(a)
    db.flush()
    for i in range(41):
        db.add(DataPoint(activity_id=a.id, recorded_at=start + timedelta(seconds=60 * i), power=watts))
    db.flush()
    return a


def _completed_ftp_test(db, user, best_20min_watts, start, max_hr=180):
    a = Activity(user_id=user.id, sport="cycling", started_at=start,
                 duration_seconds=1500, max_heart_rate=max_hr)
    db.add(a)
    db.flush()
    db.add(PowerBest(activity_id=a.id, duration_seconds=1200, avg_watts=best_20min_watts))
    db.add(PlannedWorkout(user_id=user.id, scheduled_date=start.date(), sport="cycling",
                          workout_type="field_test:ftp20", title="FTP test", steps=[],
                          is_complete=True, completed_activity_id=a.id))
    db.flush()
    return a


def test_history_fills_what_no_field_test_set(db, user):
    """With no test, auto FTP comes from the best 20 minutes in history."""
    _settings(db, user)
    _ride_with_power(db, user, 300, _BASE)
    recalculate_auto_values(db, user.id)
    assert db.query(UserSettings).filter_by(user_id=user.id).one().ftp_auto == 285


def test_a_field_test_survives_a_history_recompute(db, user):
    """A restart recomputes from history; it must not undo a measured FTP."""
    _settings(db, user)
    _ride_with_power(db, user, 300, _BASE)
    _completed_ftp_test(db, user, 250, _BASE + timedelta(days=1))
    recalculate_auto_values(db, user.id)
    us = db.query(UserSettings).filter_by(user_id=user.id).one()
    assert us.ftp_auto == 238           # round(250 * 0.95), not history's 285
    assert us.threshold_hr_auto == 167  # round(180 * 0.93)


def test_the_latest_field_test_wins(db, user):
    """Tests replay oldest first, so the most recent measurement stands."""
    _settings(db, user)
    _completed_ftp_test(db, user, 280, _BASE + timedelta(days=5))
    _completed_ftp_test(db, user, 250, _BASE)
    recalculate_auto_values(db, user.id)
    assert db.query(UserSettings).filter_by(user_id=user.id).one().ftp_auto == 266


def test_a_manual_ftp_is_never_overwritten(db, user):
    us = _settings(db, user)
    us.ftp_mode = "manual"
    us.ftp_auto = None
    db.flush()
    _completed_ftp_test(db, user, 250, _BASE)
    recalculate_auto_values(db, user.id)
    # History has no qualifying ride, and the test is gated on auto mode.
    assert db.query(UserSettings).filter_by(user_id=user.id).one().ftp_auto is None
