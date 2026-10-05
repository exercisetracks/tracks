# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import (
    Boolean, CheckConstraint, Column, Date, DateTime, Float, ForeignKey,
    Integer, String, Text, UniqueConstraint,
)
from sqlalchemy.sql import func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


# The goal types a training plan is generated for. A weekly-volume goal is a
# number to hit, tracked rather than planned.
PLAN_GOAL_TYPES = ("event", "fitness")


class TrainingGoal(Base, Synced):
    __tablename__ = "training_goals"

    id                    = Column(Integer, primary_key=True)
    user_id               = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    goal_type             = Column(String, nullable=False)  # event | fitness | volume_target
    is_active             = Column(Boolean, nullable=False, default=True)

    # event (event_sport is also a fitness goal's sport — the same list, and
    # the column every plan path already reads the sport from)
    event_name            = Column(String)
    event_sport           = Column(String)
    event_date            = Column(Date)
    event_distance_meters = Column(Float)

    # fitness: CTL points per week to gain (negative: shed), −2 … +4, 0 =
    # hold. A rate rather than a target CTL because nobody can say what CTL
    # they want — they can say "build, gently" (calculators/plan/generator/
    # fitness.py turns it into weekly load).
    ctl_ramp_per_week     = Column(Float)

    # fitness: the sports the goal trains, a JSON list of sport keys (e.g.
    # ["running", "cycling"]). Null or one entry = the single sport in
    # event_sport, which stays set to the first pick so every path that reads
    # a goal's sport (strength, stretch flows, the plan row) keeps working.
    # A list, not a join table: it is only ever read whole, with the goal.
    fitness_sports        = Column(PJson, nullable=True)

    # volume_target
    target_weekly_km      = Column(Float)
    volume_sport          = Column(String)

    days_per_week         = Column(Integer, nullable=True)
    plan_intensity        = Column(Float, nullable=True, default=1.0)

    # Mountain biking: discipline drives periodization, intensity distribution,
    # workout selection, and the TSS multiplier applied at activity import.
    # One of: xco | xcm | enduro | trail. Null for non-MTB goals.
    mtb_discipline        = Column(String, nullable=True)

    # Road cycling: discipline drives periodization (linear vs block vs
    # compressed Carmichael), workout selection (sprint-heavy for crit, TT
    # threshold blocks, climbing weight-aware), and race-plan strategy
    # (drafting model, fueling, W/kg). One of:
    # road_race | time_trial | hill_climb | criterium. Null for non-cycling goals.
    cycling_discipline    = Column(String, nullable=True)

    # When True, plan generation injects field tests (20-min FTP, 5-min Pmax,
    # 20-min FTP retest) at early-base / start-of-build / mid-peak positions.
    # Read by both the explicit /plan/generate endpoint and the background
    # auto-refresh that runs on activity import, so a single source of truth
    # avoids the create-then-regenerate race.
    schedule_tests        = Column(Boolean, nullable=False, default=False)

    # ── Strength training integration ────────────────────────────────────────
    # When include_strength=True, the plan generator adds strength workout
    # sessions alongside the endurance sessions.
    include_strength      = Column(Boolean, nullable=False, default=False)

    # Strength programming intensity tier — sessions/week ladder (this is the
    # semantics the generator implements; see strength_plan/generator.py):
    #   5 = 5×/week  dedicated strength athlete (PPL split)
    #   4 = 4×/week  strength-primary (upper/lower split)
    #   3 = 3×/week  balanced, the default (upper/lower split)
    #   2 = 2×/week  supplementary to an endurance plan
    #   1 = 1×/week  maintenance / minimal dose (full-body)
    # Tier also caps exercise difficulty (slots.TIER_MAX_DIFFICULTY), set
    # volume (periodization._TIER_SETS) and session duration.
    strength_tier         = Column(Integer, nullable=False, default=3)

    # Explicit sessions-per-week override. NULL = auto-detect from recovery days.
    strength_days_per_week = Column(Integer)

    # Max session duration in minutes (applies to strength, mobility sessions).
    # NULL = auto-select by tier: tier 1→15 min, tier 2→20 min, tier 3→50, etc.
    # When set, this overrides all tier-based caps.
    strength_session_minutes = Column(Integer, nullable=True)

    notes                 = Column(Text)
    created_at            = Column(DateTime(timezone=True), server_default=func.now())
    updated_at            = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (
        CheckConstraint(
            "goal_type IN ('event', 'fitness', 'volume_target')",
            name="training_goals_goal_type_check",
        ),
    )


sync_indexes(TrainingGoal)


class RacePlan(Base, Synced):
    __tablename__ = "race_plans"

    id           = Column(Integer, primary_key=True)
    goal_id      = Column(Integer, ForeignKey("training_goals.id", ondelete="CASCADE"),
                          nullable=False, unique=True)
    user_id      = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)

    # User-configurable inputs
    split_type   = Column(String, nullable=False, default="even")   # deprecated; use split_spread
    split_spread = Column(Float,  nullable=False, default=0.0)      # −1 (pos.split) … 0 … +1 (neg.split)
    course_type  = Column(String, nullable=False, default="flat")   # flat | rolling | hilly | mountainous
    pace_hr_mode = Column(String, nullable=False, default="pace")   # pace | pace_hr
    pin_lat      = Column(Float, nullable=True)
    pin_lon      = Column(Float, nullable=True)

    # Parsed course data
    use_gpx_distance    = Column(Boolean, nullable=False, default=False)
    course_segments     = Column(PJson, nullable=True)   # [{distance_m, elevation_gain_m, gradient}, ...]
    course_path         = Column(PJson, nullable=True)   # [[lat, lon, ele|null], ...] sampled for map + chart
    technicality_factor = Column(Float, nullable=True)  # ≥ 1.0 time penalty from course roughness
    technicality_label  = Column(String, nullable=True) # Smooth | Rolling | Technical | Very technical

    # Generated outputs (saved after "Generate Plan" click)
    predicted_seconds = Column(Float, nullable=True)
    weather_snapshot  = Column(PJson, nullable=True)
    lap_paces         = Column(PJson, nullable=True)

    # Triathlon: per-leg distances in metres {"swim_m": 1900, "bike_m": 90000, "run_m": 21097}
    triathlon_splits_m = Column(PJson, nullable=True)

    # MTB-specific (generalizable to other HR-ceiling sports later):
    #   target_hr_ceiling   bpm cap during the race (LTHR × discipline factor)
    #   fueling_plan        {"carbs_g_per_h": int, "reminder_min": int, "notes": str}
    target_hr_ceiling   = Column(Integer, nullable=True)
    fueling_plan        = Column(PJson, nullable=True)

    # Watch sync
    fit_b64          = Column(String, nullable=True)   # base64 FIT bytes for Garmin upload
    watch_filename   = Column(String, nullable=True)
    watch_uploaded_at = Column(DateTime(timezone=True), nullable=True)

    # The user's own fuelling: per-hour targets (null = calculated default,
    # see calculators/fuel_plan.py), how often to fuel, and which of their
    # fuel_products the timeline may use, in order of preference.
    fuel_carbs_per_hour     = Column(Integer, nullable=True)
    fuel_fluid_ml_per_hour  = Column(Integer, nullable=True)
    fuel_sodium_mg_per_hour = Column(Integer, nullable=True)
    fuel_interval_min       = Column(Integer, nullable=True)
    fuel_product_uids       = Column(PJson, nullable=True)

    generated_at = Column(DateTime(timezone=True), nullable=True)
    updated_at   = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(RacePlan)


class CoachingRecommendation(Base, Synced):
    __tablename__ = "coaching_recommendations"

    id                   = Column(Integer, primary_key=True)
    user_id              = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    date                 = Column(Date, nullable=False)
    readiness_score      = Column(Float)
    readiness_breakdown  = Column(PJson)
    training_signal      = Column(PJson)
    recommendations      = Column(PJson)
    goal_id              = Column(Integer, ForeignKey("training_goals.id", ondelete="SET NULL"))
    ai_enhanced          = Column(Boolean, nullable=False, default=False)
    ai_response          = Column(Text)
    generated_at         = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (UniqueConstraint("user_id", "date"),)


sync_indexes(CoachingRecommendation)
