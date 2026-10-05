# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, datetime
from pydantic import BaseModel, ConfigDict, Field


class InjuryCreate(BaseModel):
    body_part:   str
    injury_type: str
    severity:    int = Field(..., ge=1, le=10)
    start_date:  date
    end_date:    date | None = None
    notes:       str | None = None


class InjuryUpdate(BaseModel):
    body_part:   str | None = None
    injury_type: str | None = None
    severity:    int | None = Field(None, ge=1, le=10)
    start_date:  date | None = None
    end_date:    date | None = None
    notes:       str | None = None


class InjuryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:          int
    body_part:   str
    injury_type: str
    severity:    int
    start_date:  date
    end_date:    date | None = None
    notes:       str | None = None
    created_at:  datetime | None = None
    updated_at:  datetime | None = None


class DailyMetricPatch(BaseModel):
    """Manually-entered daily body / nutrition values."""
    weight_kg:   float | None = Field(None, ge=0, le=500)
    hydration_ml: int | None  = Field(None, ge=0, le=20000)
    calories_in: int | None   = Field(None, ge=0, le=30000)


# ─────────────────────────────────────────
# Sleep detail
# ─────────────────────────────────────────

class SleepStage(BaseModel):
    """One span of a single night at one stage."""

    start:   datetime
    end:     datetime
    level:   str
    seconds: int


class SleepNightOut(BaseModel):
    """
    One night, as the watch recorded it minute by minute.

    Separate from the daily metric rather than a field on it: the totals are
    seven numbers and this is a few dozen spans, so folding it into the daily
    list would multiply the size of the one request the health page makes on
    every load, for a chart that shows one night at a time.

    `stages` is empty for any night recorded before the importer began keeping
    the timeline, which is not an error — the summary numbers are still there.
    """

    date:   date
    stages: list[SleepStage] = []


# ─────────────────────────────────────────
# Stress detail
# ─────────────────────────────────────────

class StressDayOut(BaseModel):
    """One day of stress, reading by reading.

    ``points`` is a list of ``[minute_of_local_day, level]`` pairs — a pair
    rather than an object because a Fenix writes a reading every three
    minutes, so a day is some five hundred of them and the field names would
    be most of the payload.

    Separate from the daily metric for the same reason the sleep timeline is:
    the day's *average* is one number on a row that a month's chart wants
    thirty of, and this is the curve behind it, wanted only when somebody
    opens the stress history. Empty for any day recorded before the parser
    began keeping the series — the average is still there.
    """

    date:   date
    points: list[list[int]] = []
