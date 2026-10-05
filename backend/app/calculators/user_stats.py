# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging
import statistics

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.models.activity import Activity, DataPoint
from app.models.user_settings import UserSettings

log = logging.getLogger(__name__)

# Only scan the top N activities by avg HR/power to keep recalc fast.
_MAX_CANDIDATES = 20
# Minimum number of data points needed to trust a rolling-window result.
_MIN_POINTS = 30


def recalculate_auto_values(db: Session, user_id: int) -> None:
    """Update max_hr_auto, threshold_hr_auto, and ftp_auto from activity history."""
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    if us is None:
        return
    us.max_hr_auto       = _calc_max_hr(db, user_id)
    us.threshold_hr_auto = _calc_threshold_hr(db, user_id)
    us.ftp_auto          = _calc_ftp(db, user_id)
    # A completed field test is a direct measurement and beats the history
    # estimate. Applied here as well as at match time, so a recompute (every
    # server start) can no longer undo a test. Imported late to avoid a cycle.
    from app.api.training_plan.matching import apply_field_tests
    apply_field_tests(db, user_id, us)
    db.commit()
    log.info(
        f"Auto-values recalculated for user {user_id}: "
        f"max_hr={us.max_hr_auto}, threshold_hr={us.threshold_hr_auto}, ftp={us.ftp_auto}"
    )


# ─────────────────────────────────────────
# Max HR
# ─────────────────────────────────────────

def _calc_max_hr(db: Session, user_id: int) -> int | None:
    """Highest recorded max_heart_rate across all activities."""
    result = (
        db.query(func.max(Activity.max_heart_rate))
        .filter(
            Activity.user_id == user_id,
            Activity.max_heart_rate.isnot(None),
        )
        .scalar()
    )
    return int(result) if result else None


# ─────────────────────────────────────────
# Threshold HR
# ─────────────────────────────────────────

def _calc_threshold_hr(db: Session, user_id: int) -> int | None:
    """
    Estimate LTHR as the best 20-minute rolling-average HR from steady-state
    threshold efforts (same method as TrainingPeaks / intervals.icu).

    Steps:
      1. Collect running + cycling activities > 30 min with both avg and max HR.
      2. Discard easy/recovery days (keep upper half by avg HR).
      3. Discard interval sessions and races: keep only activities where
         max_hr - avg_hr < 25 bpm, indicating sustained rather than spikey effort.
         If no activities survive this filter, relax to < 35 bpm as a fallback.
      4. Scan the data_points time series for each candidate and find the highest
         20-minute rolling average HR.
      5. LTHR = the best value across all candidates.

    The max-avg spread filter is the key improvement over a naive best-20-min
    search: without it, interval sessions and crits produce 20-min windows well
    above true threshold, causing significant overestimation.
    """
    qualifying = (
        db.query(Activity)
        .filter(
            Activity.user_id == user_id,
            Activity.avg_heart_rate.isnot(None),
            Activity.max_heart_rate.isnot(None),
            Activity.duration_seconds > 1800,
            Activity.sport.in_(["running", "cycling"]),
        )
        .all()
    )
    return threshold_hr_from(
        qualifying,
        lambda a: _best_rolling_avg(db, a.id, DataPoint.heart_rate, window_seconds=1200),
    )


def threshold_hr_from(qualifying, rolling_hr) -> int | None:
    """LTHR from qualifying activities, given each one's best 20-min rolling HR.

    `qualifying` is the query result above, in its order; `rolling_hr(a)` is
    that activity's best rolling average. Pure so the phone's port can be
    checked against it — see spec/make_metrics_fixtures.py.
    """
    if not qualifying:
        return None

    # Discard easy days — only look at the upper half of the HR distribution.
    median_hr = statistics.median(a.avg_heart_rate for a in qualifying)
    hard = [a for a in qualifying if a.avg_heart_rate >= median_hr]

    # Keep steady-state efforts; prefer tight spread, fall back to wider if needed.
    steady = [a for a in hard if (a.max_heart_rate - a.avg_heart_rate) < 25]
    if not steady:
        steady = [a for a in hard if (a.max_heart_rate - a.avg_heart_rate) < 35]
    if not steady:
        steady = hard  # last resort: use all hard efforts

    steady.sort(key=lambda a: a.avg_heart_rate, reverse=True)
    candidates = steady[:_MAX_CANDIDATES]

    best = 0
    for activity in candidates:
        val = rolling_hr(activity)
        if val and val > best:
            best = val

    return round(best) if best > 0 else None


# ─────────────────────────────────────────
# FTP
# ─────────────────────────────────────────

def _calc_ftp(db: Session, user_id: int) -> int | None:
    """
    Estimate FTP as 95% of the best 20-minute rolling-average power from
    cycling activities with power meter data.

    Activities with normalized_power are preferred over avg_power because NP
    better reflects variable-effort rides. avg_power is used as a fallback for
    rides where per-second power data is absent.
    """
    qualifying = (
        db.query(Activity)
        .filter(
            Activity.user_id == user_id,
            Activity.duration_seconds > 1800,
            Activity.sport == "cycling",
        )
        .filter(
            # At least one of these must be present for the activity to be useful.
            (Activity.avg_power.isnot(None) & (Activity.avg_power > 0))
            | (Activity.normalized_power.isnot(None) & (Activity.normalized_power > 0))
        )
        .all()
    )
    return ftp_from(
        qualifying,
        lambda a: _best_rolling_avg(db, a.id, DataPoint.power, window_seconds=1200),
    )


def ftp_from(qualifying, rolling_power) -> int | None:
    """FTP from qualifying rides, given each one's best 20-min rolling power."""
    if not qualifying:
        return None

    qualifying.sort(
        key=lambda a: a.normalized_power or a.avg_power or 0,
        reverse=True,
    )
    candidates = qualifying[:_MAX_CANDIDATES]

    best = 0.0
    for activity in candidates:
        val = rolling_power(activity)
        if val and val > best:
            best = val

    return round(best * 0.95) if best > 0 else None


# ─────────────────────────────────────────
# Rolling window helper
# ─────────────────────────────────────────

def _best_rolling_avg(
    db: Session,
    activity_id: int,
    column,
    window_seconds: int,
) -> float | None:
    """
    Return the highest rolling average of `column` over any `window_seconds`-wide
    time window within the activity, using the actual recorded timestamps.

    Uses a two-pointer O(n) sliding window over the ordered time series.
    """
    points = (
        db.query(DataPoint.recorded_at, column)
        .filter(
            DataPoint.activity_id == activity_id,
            column.isnot(None),
            column > 0,
        )
        .order_by(DataPoint.recorded_at)
        .all()
    )
    return best_rolling_avg_from(
        [(p[0].timestamp(), p[1]) for p in points], window_seconds,
    )


def best_rolling_avg_from(points, window_seconds: int) -> float | None:
    """Best rolling average over (epoch seconds, value) points, in time order.

    The points are what the query above returns: non-null, positive values,
    ordered by timestamp.
    """
    if len(points) < _MIN_POINTS:
        return None

    times = [p[0] for p in points]
    values = [float(p[1]) for p in points]

    best = 0.0
    j = 0
    window_sum = 0.0

    for i in range(len(points)):
        window_sum += values[i]

        # Shrink window from the left until it fits within window_seconds.
        while times[i] - times[j] > window_seconds:
            window_sum -= values[j]
            j += 1

        avg = window_sum / (i - j + 1)
        if avg > best:
            best = avg

    return best if best > 0 else None
