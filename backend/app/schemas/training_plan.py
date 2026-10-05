# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, datetime
from pydantic import BaseModel, ConfigDict


class PlannedWorkoutOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    # Null for a workout the user added themselves — it belongs to no
    # generated plan, which is also what stops regeneration deleting it.
    plan_id: int | None = None
    scheduled_date: date
    sport: str
    workout_type: str
    title: str
    description: str | None = None
    duration_minutes: int | None = None
    distance_meters: float | None = None
    steps: list[dict] = []
    is_complete: bool = False
    completed_activity_id: int | None = None
    # "plan" | "user". What the calendar labels as the user's own.
    origin: str = "plan"
    # Dragged to another day by the user; regeneration keeps it there.
    moved_by_user: bool = False
    # Hand-set gut-training carbs g/h; null = the progression's.
    fuel_carbs_per_hour: int | None = None


class PlannedWorkoutUpdate(BaseModel):
    """A patch to one planned workout, from a live client (the web app).

    `exclude_unset` semantics: a field left out is not touched, a field set to
    null is cleared. Each written field is stamped by the server as it lands —
    see app.sync.store — so it merges with phone edits field by field. Phones
    do not use this; they push through /sync/push with their own stamps.
    """

    scheduled_date: date | None = None
    title: str | None = None
    description: str | None = None
    duration_minutes: int | None = None
    distance_meters: float | None = None
    sport: str | None = None
    workout_type: str | None = None
    steps: list[dict] | None = None
    is_complete: bool | None = None
    completed_activity_id: int | None = None
    fuel_carbs_per_hour: int | None = None


class PlannedWorkoutCreate(BaseModel):
    """A workout somebody added themselves, rather than one a plan prescribed.

    Belongs to no plan: `plan_id` stays null, so regeneration — which deletes
    by plan — leaves it alone.
    """

    scheduled_date: date
    title: str
    sport: str = "running"
    workout_type: str = "easy"
    description: str | None = None
    duration_minutes: int | None = None
    distance_meters: float | None = None
    steps: list[dict] = []


class TrainingPlanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    goal_id: int
    sport: str
    vdot: float | None = None
    generated_at: datetime | None = None
    workouts: list[PlannedWorkoutOut] = []


class IcsTokenOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    goal_id: int
    token: str
    ics_url: str


class UserIcsTokenOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    token: str
    ics_url: str
