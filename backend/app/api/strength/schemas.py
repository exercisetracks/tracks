# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared request/response schemas for the strength API.

Pydantic models and the equipment vocabulary used across more than one
sub-module live here. Schemas used by only a single sub-module are defined
alongside their endpoints (e.g. PreferenceBody, CustomExerciseBody).
"""
from __future__ import annotations

from datetime import date
from typing import Any

from pydantic import BaseModel

from app.spec.strength import VALID_EQUIPMENT


class ExerciseOut(BaseModel):
    """An exercise as returned by GET /exercises — a library row or a user's
    custom exercise, enriched with the caller's preference + animation state."""
    model_config = {"from_attributes": True}

    name: str
    garmin_category: str | None
    garmin_subtype: int | None
    # has_animation: legacy boolean. True iff (garmin_category, garmin_subtype)
    # is in Garmin's animation manifest. Kept for backwards-compat.
    has_animation: bool = False
    # animation_state: 4-state for the badge.
    #   "unknown" / "likely" / "confirmed_yes" / "confirmed_no"
    # Resolved per request from the user's primary device + the
    # animation_confirmations table. See garmin_animations.state_from_lookup.
    animation_state: str = "unknown"
    primary_muscles: list[str]
    secondary_muscles: list[str]
    equipment: list[str]
    movement_pattern: str | None
    sport_relevance: dict[str, int]
    difficulty: int
    is_compound: bool
    description: str | None
    instructions: str | None = None
    # Short coaching cues (imperative reminders) surfaced in the detail modal,
    # step rows, and the session runner.
    cues: list[str] = []
    # Bundled movement-animation slug (custom exercises only; library rows match
    # by name in the frontend registry).
    viewer_slug: str | None = None
    # Preference fields — populated when returning the enriched exercise list.
    preference: str | None = None
    is_custom: bool = False
    custom_id: int | None = None
    default_sets: int | None = None
    default_reps: int | None = None


class ProgressEntry(BaseModel):
    exercise_name: str
    estimated_1rm_kg: float | None
    last_weight_kg: float | None
    last_reps: int | None
    last_session_volume_kg: float | None
    sessions_completed: int
    progression_stage: str
    last_updated: Any | None


class SetHistoryEntry(BaseModel):
    activity_id: int
    activity_date: date
    sport: str
    set_number: int
    exercise_name: str | None
    exercise_category: str | None
    weight_kg: float | None
    repetitions: int | None
    duration_seconds: float | None


class WeeklySummaryEntry(BaseModel):
    muscle_group: str
    sets_this_week: int
    volume_kg: float


class OneRMOverride(BaseModel):
    estimated_1rm_kg: float
    last_weight_kg: float | None = None
    last_reps: int | None = None


class EquipmentUpdate(BaseModel):
    equipment: list[str]


# Equipment vocabulary — validated by both the equipment and custom-exercise
# endpoints, so it lives here rather than in either sub-module. Sourced from
# spec/strength.yaml (see app.spec.strength) so this set and the frontend's
# richer EQUIPMENT_OPTIONS (label/description per value) are provably the same
# values — re-exported under its original name so existing importers
# (app.api.strength.equipment, app.api.strength.custom_exercises,
# app.schemas.user_settings) don't need to change.
