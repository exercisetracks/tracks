# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training plan sub-package.

Re-exports all public symbols for backward compatibility with code
that imports from `app.calculators.training_plan`.
"""

__all__ = [
    # Base
    "calculate_vdot", "vdot_to_paces", "_sport_family",
    "_DEFAULT_VDOT", "_DEFAULT_RUN_PACES",
    "_best_vdot_from_pace_bests", "_css_pace_sec_per_100m",
    "_hr_zone_desc", "_hr_zone", "_pwr_zone", "_target_note",
    "_current_weekly_km", "_max_weekly_km_for_race",
    "_target_peak_long_km", "_phase_for_week",
    "_weekly_volume_km", "_capacity_km",
    "_fmt_pace", "_fmt_dist_m",
    "_apply_days_per_week", "_max_consecutive_workouts",
    "_EASY_FILL", "_EASY_TYPES", "_QUALITY_TYPES", "_REMOVAL_PRIORITY",
    "_TEMPLATES", "_cy_template_for", "_mtb_template_for",
    "_SPORT_DEFAULT_WEEKLY_KM", "_SPORT_MAX_WEEKLY_KM",
    "_TYPE_LABEL", "_FIELD_TEST_LABELS",
    "_workout_title", "_workout_description",
    "_duration_from_steps", "_distance_from_steps",
    # Generator
    "generate_training_plan", "generate_fitness_plan", "fitness_week_targets",
    "fitness_horizon_end", "_build_steps", "_generate_week",
    "_rotating_template", "_polarisation_check", "_monotony_scores",
    # Field tests
    "_mtb_field_test",
    # Running
    "_run_easy", "_run_recovery", "_run_long", "_run_tempo",
    "_run_intervals", "_run_race_pace", "_run_fartlek",
    "_run_short_quality", "_run_hill_sprints", "_run_with_breaks",
    # Cycling
    "_cy_endurance", "_cy_easy_spin", "_cy_tempo", "_cy_sweet_spot",
    "_cy_threshold", "_cy_vo2", "_cy_micro_bursts", "_cy_over_unders",
    "_cy_anaerobic", "_cy_sprint", "_cy_sustained_climb", "_cy_tt_pace",
    "_cy_long", "_cy_race_pace", "_cy_short_quality",
    # MTB
    "_mtb_endurance", "_mtb_tempo", "_mtb_sweet_spot", "_mtb_threshold",
    "_mtb_intervals", "_mtb_micro_bursts", "_mtb_over_unders",
    "_mtb_matchbook", "_mtb_standing_starts", "_mtb_descent_repeats",
    "_mtb_long", "_mtb_race_pace", "_mtb_skills", "_mtb_recovery",
    "_mtb_pick_skills",
    # Swimming
    "_swim_aerobic", "_swim_technique", "_swim_css", "_swim_vo2", "_swim_long",
    "_swim_race_pace", "_swim_short_quality",
    # Generic
    "_generic_easy", "_generic_aerobic", "_generic_quality", "_generic_long",
]

from app.calculators.plan.base import (
    _DEFAULT_VDOT,
    _DEFAULT_RUN_PACES,
    _apply_days_per_week,
    _best_vdot_from_pace_bests,
    _capacity_km,
    _css_pace_sec_per_100m,
    _current_weekly_km,
    _cy_template_for,
    _distance_from_steps,
    _duration_from_steps,
    _EASY_FILL,
    _EASY_TYPES,
    _fmt_dist_m,
    _fmt_pace,
    _hr_zone_desc,
    _hr_zone,
    _max_consecutive_workouts,
    _max_weekly_km_for_race,
    _mtb_template_for,
    _phase_for_week,
    _pwr_zone,
    _QUALITY_TYPES,
    _REMOVAL_PRIORITY,
    _SPORT_DEFAULT_WEEKLY_KM,
    _SPORT_MAX_WEEKLY_KM,
    _sport_family,
    _target_note,
    _target_peak_long_km,
    _TEMPLATES,
    _TYPE_LABEL,
    _FIELD_TEST_LABELS,
    _weekly_volume_km,
    _workout_description,
    _workout_title,
    calculate_vdot,
    vdot_to_paces,
)

from app.calculators.plan.generator import (
    _build_steps,
    _generate_week,
    _monotony_scores,
    _polarisation_check,
    _rotating_template,
    fitness_horizon_end,
    fitness_week_targets,
    generate_fitness_plan,
    generate_training_plan,
)

# Re-export field test builder (used by API layer)
from app.calculators.plan.mtb import _mtb_field_test

# Running workout builders
from app.calculators.plan.running import (
    _run_easy,
    _run_recovery,
    _run_long,
    _run_tempo,
    _run_intervals,
    _run_race_pace,
    _run_fartlek,
    _run_short_quality,
    _run_hill_sprints,
    _run_with_breaks,
)

# Cycling workout builders
from app.calculators.plan.cycling import (
    _cy_endurance,
    _cy_easy_spin,
    _cy_tempo,
    _cy_sweet_spot,
    _cy_threshold,
    _cy_vo2,
    _cy_micro_bursts,
    _cy_over_unders,
    _cy_anaerobic,
    _cy_sprint,
    _cy_sustained_climb,
    _cy_tt_pace,
    _cy_long,
    _cy_race_pace,
    _cy_short_quality,
)

# MTB workout builders
from app.calculators.plan.mtb import (
    _mtb_endurance,
    _mtb_tempo,
    _mtb_sweet_spot,
    _mtb_threshold,
    _mtb_intervals,
    _mtb_micro_bursts,
    _mtb_over_unders,
    _mtb_matchbook,
    _mtb_standing_starts,
    _mtb_descent_repeats,
    _mtb_long,
    _mtb_race_pace,
    _mtb_skills,
    _mtb_recovery,
    _mtb_pick_skills,
)

# Swimming workout builders
from app.calculators.plan.swimming import (
    _swim_aerobic,
    _swim_css,
    _swim_long,
    _swim_race_pace,
    _swim_short_quality,
    _swim_technique,
    _swim_vo2,
)

# Generic workout builders
from app.calculators.plan.generic import (
    _generic_easy,
    _generic_aerobic,
    _generic_quality,
    _generic_long,
)
