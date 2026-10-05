# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, datetime
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field


# ─────────────────────────────────────────
# Readiness
# ─────────────────────────────────────────

class ReadinessOut(BaseModel):
    score: float
    hrv_score: float
    sleep_score: float
    resting_hr_score: float
    hrv_today: float | None = None
    hrv_baseline: float | None = None
    sleep_hours: float | None = None
    garmin_sleep_score: float | None = None
    resting_hr_today: float | None = None
    resting_hr_baseline: float | None = None
    training_score: float | None = None
    primary_driver: str = "default"
    confidence: str = "low"
    notes: list[str]


# ─────────────────────────────────────────
# Training signal
# ─────────────────────────────────────────

class TrainingSignalOut(BaseModel):
    ctl: float
    atl: float
    tsb: float
    ctl_ramp: float | None = None
    injury_risk_warning: bool
    phase: str | None = None      # event: base | build | peak | taper; fitness: build | maintain | recovery
    goal_note: str | None = None


# ─────────────────────────────────────────
# Workout recommendations
# ─────────────────────────────────────────

class WorkoutRecommendationOut(BaseModel):
    sport: str
    intensity: str
    duration_minutes: int
    distance_km: float | None = None
    hr_min: int | None = None
    hr_max: int | None = None
    description: str
    reasoning: str
    projected_tss: float
    # Recommender v2 (defaults keep older cached rows valid)
    modality: str = "cardio"          # cardio | strength | mobility | rest
    title: str | None = None
    focus: str | None = None
    situation: str | None = None


# ─────────────────────────────────────────
# Daily coaching response
# ─────────────────────────────────────────

class DailyCoachingOut(BaseModel):
    date: date
    readiness: ReadinessOut
    signal: TrainingSignalOut
    recommendations: list[WorkoutRecommendationOut]
    ai_enhanced: bool = False
    ai_response: str | None = None
    generated_at: datetime | None = None


# ─────────────────────────────────────────
# 7-day plan
# ─────────────────────────────────────────

class PlanDayOut(BaseModel):
    date: date
    ctl: float
    atl: float
    tsb: float
    recommendation: WorkoutRecommendationOut | None = None


# ─────────────────────────────────────────
# Training goals
# ─────────────────────────────────────────

class TrainingGoalOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    # The sync identity. A phone knows its goals only by uid (spec/sync.yaml)
    # and needs this to find the integer id the few server-only goal
    # endpoints (plan generation) still take.
    uid: str | None = None
    goal_type: str
    is_active: bool
    event_name: str | None = None
    event_sport: str | None = None
    event_date: date | None = None
    event_distance_meters: float | None = None
    ctl_ramp_per_week: float | None = None
    fitness_sports: list[str] | None = None
    target_weekly_km: float | None = None
    volume_sport: str | None = None
    days_per_week: int | None = None
    plan_intensity: float | None = None
    mtb_discipline: str | None = None
    cycling_discipline: str | None = None
    schedule_tests: bool | None = None
    include_strength: bool = False
    strength_tier: int = 3
    strength_days_per_week: int | None = None
    notes: str | None = None
    created_at: datetime | None = None


class CoachingHistoryOut(BaseModel):
    """A cached coaching recommendation from a past day."""
    date: date
    readiness_score: float | None = None
    signal: TrainingSignalOut | None = None
    recommendations: list[WorkoutRecommendationOut] | None = None
    ai_enhanced: bool = False
    ai_response: str | None = None
    generated_at: datetime | None = None


MtbDiscipline = Literal["xco", "xcm", "enduro", "trail"]
CyclingDiscipline = Literal["road_race", "time_trial", "hill_climb", "criterium"]

# A fitness goal's CTL change per week. The bounds are the slider's: below −2
# is stopping, not a plan. The top follows what the generator can deliver.
# It was cut from +8 to +4 on 2026-09-28 when, measured, planned load stopped
# following the ramp past +3.5–4 (session caps, an 80 km/week running cap, a
# flat 55 TSS/h). With the week sized to its load in TSS (calculators/plan/
# generator/week.py), measured again over CTL 30–60 at 4–6 days a week, a +6
# week is planned at 99–101% of its load in running, cycling and MTB; +8
# still holds 92–100% there but falls below 90% at CTL 50+ on 3–4 days.
# +6 is the top because past it the first week's daily load is about twice
# CTL at the CTLs most people train at (acute:chronic ≈ 2; Gabbett 2016 puts
# the injury risk up sharply past 1.5) — deliverable is not the same as wise.
# The slider's red band (> +3) says so before anyone gets there.
CtlRamp = Annotated[float, Field(ge=-2.0, le=6.0)]

# A fitness goal's sports: a short list of sport keys. Bounded so a bad
# client cannot plan across a hundred "sports"; the planner itself keeps one
# per sport family.
FitnessSports = Annotated[list[Annotated[str, Field(min_length=1, max_length=40)]],
                          Field(max_length=8)]


class TrainingGoalCreate(BaseModel):
    goal_type: Literal["event", "fitness", "volume_target"]
    event_name: str | None = None
    event_sport: str | None = None
    event_date: date | None = None
    event_distance_meters: float | None = None
    ctl_ramp_per_week: CtlRamp | None = None
    fitness_sports: FitnessSports | None = None
    target_weekly_km: float | None = None
    volume_sport: str | None = None
    days_per_week: int | None = None
    plan_intensity: float | None = None
    mtb_discipline: MtbDiscipline | None = None
    cycling_discipline: CyclingDiscipline | None = None
    schedule_tests: bool | None = None
    include_strength: bool = False
    # 5=5×/wk dedicated … 3=balanced default … 1=minimal dose (see models/coaching.py)
    strength_tier: int = Field(3, ge=1, le=5)
    strength_days_per_week: int | None = Field(None, ge=1, le=7)
    notes: str | None = None


class TrainingGoalUpdate(BaseModel):
    """Partial update — any subset of fields, including is_active to toggle focus."""
    is_active: bool | None = None
    event_name: str | None = None
    event_sport: str | None = None
    event_date: date | None = None
    event_distance_meters: float | None = None
    ctl_ramp_per_week: CtlRamp | None = None
    fitness_sports: FitnessSports | None = None
    target_weekly_km: float | None = None
    volume_sport: str | None = None
    days_per_week: int | None = None
    plan_intensity: float | None = None
    mtb_discipline: MtbDiscipline | None = None
    cycling_discipline: CyclingDiscipline | None = None
    schedule_tests: bool | None = None
    include_strength: bool | None = None
    strength_tier: int | None = Field(None, ge=1, le=5)
    strength_days_per_week: int | None = Field(None, ge=1, le=7)
    notes: str | None = None


# ─────────────────────────────────────────
# Race plan
# ─────────────────────────────────────────

class RacePlanUpdate(BaseModel):
    """Config inputs that can be changed before or after generating the plan."""
    split_spread:     float | None = None   # −1 (positive split) … 0 (even) … +1 (negative split)
    course_type:      str | None = None     # flat | rolling | hilly | mountainous
    pace_hr_mode:     str | None = None     # pace | pace_hr
    pin_lat:          float | None = None
    pin_lon:          float | None = None
    use_gpx_distance: bool | None = None    # use GPX actual distance instead of goal distance
    # Fuelling overrides; null = the calculated default (calculators/fuel_plan.py).
    fuel_carbs_per_hour:     int | None = None
    fuel_fluid_ml_per_hour:  int | None = None
    fuel_sodium_mg_per_hour: int | None = None
    fuel_interval_min:       int | None = None
    fuel_product_uids:       list[str] | None = None


class LapPaceOut(BaseModel):
    lap:               int
    distance_m:        int
    target_sec_per_km: float
    target_pace:       str
    gradient:          float
    grade_adj_sec:     float
    grade_adj_pace:    str
    cumulative_km:     float
    hr_ceiling:        int | None = None
    # Cycling-specific (None for non-cycling sports)
    target_watts:          int   | None = None
    target_watts_pct_ftp:  int   | None = None


class TriathlonLegOut(BaseModel):
    """Predicted time + target for a single triathlon leg."""
    leg:               str          # "swim" | "bike" | "run"
    distance_m:        float
    predicted_seconds: float
    predicted_time:    str
    target_pace:       str | None = None   # run: min/km; swim: /100m; bike: avg km/h
    target_watts:      int | None = None   # bike leg when FTP available
    target_watts_pct_ftp: int | None = None
    lap_paces:         list | None = None  # bike + run get km splits


class RacePlanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:                int
    goal_id:           int
    split_spread:      float = 0.0
    course_type:       str
    pace_hr_mode:      str
    pin_lat:           float | None = None
    pin_lon:           float | None = None
    has_course:          bool = False
    use_gpx_distance:    bool = False
    course_distance_m:   int | None = None
    technicality_factor: float | None = None
    technicality_label:  str | None = None
    course_gain_m:     int | None = None
    course_path:       list | None = None
    predicted_seconds: float | None = None
    predicted_time:    str | None = None
    weather_snapshot:  dict | None = None
    lap_paces:         list | None = None
    watch_uploaded_at: datetime | None = None
    generated_at:      datetime | None = None
    # Multi-sport fields
    sport:             str | None = None        # running | cycling | swimming | triathlon
    ftp:               int | None = None        # cycling: FTP in watts used for this plan
    css_sec_per_100m:  float | None = None      # swimming: CSS used for this plan
    swim_target_pace:  str | None = None        # swimming: formatted target pace
    triathlon_legs:    list[TriathlonLegOut] | None = None  # triathlon: per-leg breakdown
    triathlon_splits_m: dict | None = None      # {"swim_m", "bike_m", "run_m"}
    # MTB / road-cycling extras (None for other sports)
    target_hr_ceiling:   int  | None = None     # bpm cap during the race
    fueling_plan:        dict | None = None     # {"carbs_g_per_h", "reminder_min", "notes", ...}
    fuel_carbs_per_hour:     int | None = None
    fuel_fluid_ml_per_hour:  int | None = None
    fuel_sodium_mg_per_hour: int | None = None
    fuel_interval_min:       int | None = None
    fuel_product_uids:       list[str] | None = None
    mtb_discipline:      str  | None = None     # mirror of goal.mtb_discipline
    cycling_discipline:  str  | None = None     # mirror of goal.cycling_discipline


class PredictedTimeOut(BaseModel):
    predicted_seconds: float | None = None
    predicted_time:    str | None = None
    vdot:              float | None = None
    has_race_plan:     bool = False
