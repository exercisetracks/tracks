# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, datetime
from pydantic import BaseModel, ConfigDict


class TrainingLoadPoint(BaseModel):
    """One day's entry in the CTL/ATL/TSB fitness chart."""
    date: date
    tss: float
    ctl: float
    atl: float
    tsb: float
    ctl_ramp: float | None = None  # CTL change over the past 7 days


class ActivityLoadPoint(BaseModel):
    """Per-activity TSS dot for overlaying on the fitness chart."""
    activity_id: int
    date: date
    sport: str | None = None
    tss: float


class PowerCurvePoint(BaseModel):
    """Best mean power at a given duration."""
    duration_seconds: int
    avg_watts: float


class PaceCurvePoint(BaseModel):
    """Best mean speed at a given distance."""
    distance_meters: int
    avg_speed_mps: float
    pace_per_km: str  # formatted mm:ss/km for display


class DailyMetricOut(BaseModel):
    """One day's health summary — sleep, HRV, resting HR, and manual entries."""
    model_config = ConfigDict(from_attributes=True)

    date: date
    resting_hr: float | None = None
    hrv: float | None = None
    sleep_hours: float | None = None
    sleep_score: float | None = None
    sleep_deep_hours: float | None = None
    sleep_light_hours: float | None = None
    sleep_rem_hours: float | None = None
    sleep_awake_hours: float | None = None
    # When the night ran from and to — see app.models.metrics for why these
    # are read off the stage timeline rather than stored. Both are absent for
    # a night whose totals exist but whose timeline was never kept.
    sleep_start: datetime | None = None
    sleep_end: datetime | None = None
    steps: int | None = None
    active_calories: int | None = None
    resting_calories: int | None = None
    avg_stress_level: float | None = None
    avg_respiration_rate: float | None = None
    spo2: float | None = None
    # Body Battery as the watch computed it — see app.models.metrics for why
    # these are five numbers and not one.
    body_battery_high: int | None = None
    body_battery_low: int | None = None
    body_battery_last: int | None = None
    body_battery_charged: int | None = None
    body_battery_drained: int | None = None
    weight_kg: float | None = None
    hydration_ml: int | None = None
    calories_in: int | None = None


class WeeklyVolumePoint(BaseModel):
    """One ISO week of aggregated distance + duration."""
    week_start: date
    distance_km: float | None = None
    duration_hours: float | None = None
    activity_count: int = 0


class MetricsSummaryOut(BaseModel):
    """Aggregate totals for the dashboard headline cards."""
    activity_count: int
    total_distance_km: float | None
    total_duration_hours: float | None
    sport_count: int
    device_count: int
    avg_distance_km: float | None  # GPS activities only
    avg_duration_minutes: float | None


class SportBreakdownOut(BaseModel):
    """Per-sport totals — one row per sport, for pie/bar charts."""
    sport: str
    activity_count: int
    total_distance_km: float | None
    total_duration_hours: float | None
    avg_duration_minutes: float | None


class TrendBucketOut(BaseModel):
    """One time bucket (week / month / year) of aggregated training data."""
    period_start: date
    activity_count: int
    total_distance_km: float | None
    total_duration_hours: float | None
    total_tss: float | None
    monotony: float | None = None   # mean_daily_tss / std_dev_daily_tss
    strain: float | None = None     # total_tss × monotony


class ReadinessHistoryPoint(BaseModel):
    """Daily readiness score breakdown for trend charting."""
    date: date
    score: float
    hrv_score: float
    sleep_score: float
    resting_hr_score: float
    training_score: float | None = None
    primary_driver: str = "default"
    confidence: str = "low"


class RacePredictionOut(BaseModel):
    """Predicted race time at a standard distance via the Riegel formula."""
    distance_meters: int
    distance_label: str
    predicted_time_seconds: int
    formatted_time: str           # h:mm:ss or m:ss
    reference_distance_meters: int | None = None  # the PaceBest used as input
    is_actual: bool = False       # True when we have a real effort at this distance


class ActivityCalendarPoint(BaseModel):
    date: date
    count: int


class Vo2MaxPoint(BaseModel):
    date: date
    value: float
