# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pydantic schemas for the workout builder API."""
from datetime import datetime
from pydantic import BaseModel, Field, model_validator


# ── Exercise within a workout ────────────────────────────────────────────────

class WorkoutExerciseIn(BaseModel):
    # Null only on a rest block (item_kind "rest"); see spec/sync.yaml.
    exercise_name: str | None = None
    exercise_source: str = "library"  # "library" | "custom"
    # One number: the watch's step has a single reps target (see the model).
    target_reps: int = Field(8, ge=1, le=50)
    target_sets: int = Field(3, ge=1, le=15)
    rir_target: int = Field(2, ge=0, le=5)
    rest_seconds: int = Field(90, ge=0, le=600)
    weight_method: str = "percentage_e1rm"  # "fixed" | "percentage_e1rm" | "rpe"
    weight_value: float | None = None
    # A position in the list. Stored as a fractional key (app.sync.order) so
    # phones can reorder without renumbering; the web app keeps positions.
    order_index: int = 0
    notes: str | None = None
    # Blocks — see spec/sync.yaml, workout_exercise. A rest block has
    # item_kind "rest" and its length in rest_seconds; members of a repeat
    # group or superset repeat the group_* values.
    item_kind: str | None = Field(None, pattern="^rest$")
    group_uid: str | None = Field(None, max_length=64)
    group_kind: str | None = Field(None, pattern="^(repeat|superset)$")
    group_rounds: int | None = Field(None, ge=1, le=50)
    group_rest_seconds: int | None = Field(None, ge=0, le=900)

    @model_validator(mode="after")
    def _named_unless_rest(self):
        if self.item_kind != "rest" and not self.exercise_name:
            raise ValueError("an exercise needs an exercise_name")
        return self


class WorkoutExerciseOut(WorkoutExerciseIn):
    id: int
    workout_id: int
    created_at: datetime


# ── Workout ──────────────────────────────────────────────────────────────────

class WorkoutIn(BaseModel):
    name: str
    description: str | None = None
    tags: list[str] = []
    include_in_plan: bool = True
    sync_to_watch: bool = False
    exercises: list[WorkoutExerciseIn] = []


class WorkoutUpdate(BaseModel):
    name: str | None = None
    description: str | None = None
    tags: list[str] | None = None
    include_in_plan: bool | None = None
    sync_to_watch: bool | None = None
    exercises: list[WorkoutExerciseIn] | None = None


class WorkoutOut(BaseModel):
    id: int
    user_id: int
    name: str
    description: str | None = None
    tags: list[str] = []
    include_in_plan: bool = True
    sync_to_watch: bool = False
    exercises: list[WorkoutExerciseOut] = []
    created_at: datetime
    updated_at: datetime | None = None

    model_config = {"from_attributes": True}


# ── Progressive overload calculation ─────────────────────────────────────────

class ProgressionRequest(BaseModel):
    exercise_name: str
    weight_method: str = "percentage_e1rm"  # "fixed" | "percentage_e1rm" | "rpe"
    weight_value: float | None = None
    target_reps: int = 10
    rir_target: int = 2


class ProgressionResponse(BaseModel):
    exercise_name: str
    suggested_weight_kg: float | None = None
    suggested_weight_lb: float | None = None
    estimated_1rm_kg: float | None = None
    progression_stage: str = "linear"
    sessions_completed: int = 0
    # Whether the user is ready for a weight increase
    ready_to_progress: bool = False
    # Recommended increment in kg (0 if not ready)
    recommended_increment_kg: float = 0.0
    # Warm-up sets to perform
    warmup_sets: list[dict] = []


# ── Workout session logging ──────────────────────────────────────────────────

class LoggedSet(BaseModel):
    weight_kg: float
    reps: int
    rpe: int | None = None


class LoggedExercise(BaseModel):
    exercise_name: str
    sets: list[LoggedSet] = []


class WorkoutSessionIn(BaseModel):
    workout_id: int | None = None
    # A generated plan session that this log completes (the guided runner sets
    # this). Marks the PlannedWorkout complete and feeds the progression loop.
    planned_workout_id: int | None = None
    exercises: list[LoggedExercise] = []
    session_rpe: int | None = None
    notes: str | None = None


class WorkoutSessionOut(BaseModel):
    id: int
    workout_id: int | None = None
    planned_workout_id: int | None = None
    user_id: int
    session_data: list = []
    total_volume_kg: float | None = None
    session_rpe: int | None = None
    notes: str | None = None
    completed_at: datetime
    created_at: datetime

    model_config = {"from_attributes": True}
