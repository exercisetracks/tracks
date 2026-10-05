# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Race predictor and pacing calculator package.

Split out of a single ~1000-line module into one file per loosely-coupled
concern (kept behaviour numerically identical). Every public name is re-exported
here, so existing imports (`from app.calculators.race_predictor import …`) keep
working unchanged.

Modules:
  formatting.py  time / pace string formatters (shared by all sports)
  geo.py         haversine distance + bearing primitives
  grade.py       Minetti grade-adjusted running pace (+ GAP factor)
  weather.py     heat/humidity + headwind slowdown factor
  wind.py        course wind-exposure (running linear + cycling aerodynamic)
  course.py      GPX parsing, path-point sampling, technicality, totals
  running.py     VDOT race prediction (volume-corrected marathon), HR ceilings,
                 grade-aware lap paces
  cycling.py     Critical-Power prediction, power/speed solver, lap power targets
  swimming.py    CSS race prediction
  fit.py         FIT race-workout encoder
"""
from __future__ import annotations

from .course import (
    compute_technicality_factor,
    course_totals,
    extract_path_points,
    parse_gpx,
)
from .cycling import (
    compute_cycling_hr_ceilings,
    compute_cycling_lap_targets,
    predict_cycling_time_sec,
)
from .fit import generate_race_fit
from .formatting import format_swim_pace, format_time
from .grade import grade_adjustment_factor, grade_cost_multiplier
from .running import (
    compute_hr_ceilings,
    compute_lap_paces,
    predict_race_time_sec,
    predict_running_race_sec,
    training_indices,
)
from .swimming import predict_swim_time_sec
from .weather import weather_slowdown_factor
from .wind import compute_cycling_wind_course_exposure, compute_wind_course_exposure

__all__ = [
    # formatting
    "format_time",
    "format_swim_pace",
    # running
    "predict_race_time_sec",
    "predict_running_race_sec",
    "training_indices",
    "compute_hr_ceilings",
    "compute_lap_paces",
    # cycling
    "predict_cycling_time_sec",
    "compute_cycling_hr_ceilings",
    "compute_cycling_lap_targets",
    "compute_cycling_wind_course_exposure",
    # swimming
    "predict_swim_time_sec",
    # grade
    "grade_cost_multiplier",
    "grade_adjustment_factor",
    # weather / wind
    "weather_slowdown_factor",
    "compute_wind_course_exposure",
    # course / GPX
    "parse_gpx",
    "extract_path_points",
    "compute_technicality_factor",
    "course_totals",
    # FIT
    "generate_race_fit",
]
