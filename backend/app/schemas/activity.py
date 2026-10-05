# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime
from typing import Generic, TypeVar
from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    """Generic paginated response wrapper, reusable across any list endpoint."""
    total: int
    page: int
    page_size: int
    items: list[T]


class ActivitySummary(BaseModel):
    """Returned in list views — enough to render a table row or summary card."""
    model_config = ConfigDict(from_attributes=True)

    id: int
    sport: str | None = None
    sub_sport: str | None = None
    name: str | None = None
    started_at: datetime | None = None
    duration_seconds: int | None = None
    distance_meters: float | None = None
    avg_heart_rate: int | None = None
    max_heart_rate: int | None = None
    total_calories: int | None = None
    total_ascent: float | None = None
    avg_speed: float | None = None
    device_id: int | None = None
    is_merged: bool = False   # a "merged trip" summary (excluded from all metrics)


class LapOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:               int
    lap_number:       int
    start_time:       datetime | None = None
    duration_seconds: float   | None = None
    distance_meters:  float   | None = None
    avg_heart_rate:   int     | None = None
    max_heart_rate:   int     | None = None
    avg_speed:        float   | None = None
    max_speed:        float   | None = None
    avg_cadence:      int     | None = None
    total_ascent:     float   | None = None
    total_descent:    float   | None = None
    avg_power:        int     | None = None
    total_calories:   int     | None = None
    total_grit:       float   | None = None
    avg_flow:         float   | None = None
    # Golf-specific fields — None for non-golf activities
    total_strokes:       int   | None = None
    total_putts:         int   | None = None
    avg_stroke_distance: float | None = None
    hole_time_in_zone:   float | None = None


class GolfHoleSummary(BaseModel):
    """Per-hole summary derived from the laps table for a golf activity.

    Mirrors the golf columns on LapOut but presents them with golf-friendly
    field names so callers don't have to filter or rename the generic LapOut
    fields themselves.
    """
    model_config = ConfigDict(from_attributes=True, populate_by_name=True)

    id:               int
    hole_number:      int          = Field(alias="lap_number")
    start_time:       datetime | None = None
    duration_seconds: float   | None = None
    # distance_meters is metres from tee to hole (Garmin stores it in the
    # standard total_distance lap field for golf rounds)
    distance_meters:  float   | None = None
    total_strokes:    int     | None = None
    total_putts:      int     | None = None
    avg_stroke_distance: float | None = None   # metres per stroke
    hole_time_in_zone:   float | None = None


class ActivityDetail(ActivitySummary):
    """Full activity record — returned for a single activity view."""
    notes: str | None = None
    training_stress_score: float | None = None
    intensity_factor: float | None = None
    aerobic_training_effect: float | None = None
    anaerobic_training_effect: float | None = None
    max_speed: float | None = None
    avg_cadence: int | None = None
    total_descent: float | None = None
    avg_power: int | None = None
    normalized_power: int | None = None
    vo2max_estimate: float | None = None
    training_load_peak: float | None = None
    total_grit: float | None = None
    avg_flow: float | None = None
    workout_feel: int | None = None
    workout_rpe: int | None = None
    efficiency_factor: float | None = None
    aerobic_decoupling: float | None = None
    effective_tss: float | None = None
    user_id: int | None = None
    lap_count: int | None = None
    # For merged trips: {custom_track_id, source_activity_ids, source_track_ids}.
    extra: dict | None = None


class TrackPoint(BaseModel):
    """A single time-series sample from an activity — used for maps and charts."""
    model_config = ConfigDict(from_attributes=True)

    recorded_at: datetime
    lat: float | None = None
    lng: float | None = None
    altitude: float | None = None
    heart_rate: int | None = None
    power: int | None = None
    cadence: int | None = None
    speed: float | None = None
    grit: float | None = None
    flow: float | None = None


class HeatmapPoint(BaseModel):
    """Minimal GPS point for heatmap rendering — lat/lng only."""
    lat: float
    lng: float


class ClimbSplitOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:               int
    split_number:     int
    split_type:       str   | None = None
    start_time:       datetime | None = None
    end_time:         datetime | None = None
    duration_seconds: float | None = None
    total_ascent:     float | None = None
    avg_vert_speed:   float | None = None
    total_calories:   int   | None = None
    min_heart_rate:   int   | None = None
    max_heart_rate:   int   | None = None
    difficulty_score: int   | None = None
    grade_level:      int   | None = None
    climb_result:     int   | None = None
    user_grade:       str   | None = None


class StrengthSetOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:                int
    set_number:        int
    set_type:          str | None = None    # 'active' or 'rest'
    exercise_category: str | None = None
    exercise_name:     str | None = None
    weight_kg:         float | None = None
    repetitions:       int   | None = None
    duration_seconds:  float | None = None
    start_time:        datetime | None = None


class BackfillResult(BaseModel):
    """Summary of a metrics backfill operation."""
    processed: int
    skipped: int  # activities with no data points
    errors: int
