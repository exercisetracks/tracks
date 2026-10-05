# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Workout-builder dispatch.

Maps an abstract ``workout_type`` (e.g. ``"intervals"``, ``"long"``) plus the
sport family onto the concrete step-list builder in the per-sport modules
(``running``/``cycling``/``mtb``/``swimming``/``rowing``/``hiking``/``skiing``/
``climbing``), with a generic fallback. Where a sport has kinds (pool or open
water, cross-country or skimo) the builder reads the kind off ``sport``. This is
pure glue: it contains no scheduling or periodisation logic, only the routing
table. Per-sport builders are imported lazily so importing the planner does not
pull in every sport module up front.

Also defines the low/high intensity session-type sets used for polarised 80/20
enforcement (Seiler 2010; Stöggl & Sperlich 2014) across the generator.
"""

from __future__ import annotations

from app.calculators.plan.base import _DEFAULT_RUN_PACES

# ─────────────────────────────────────────
# Session type classification
# ─────────────────────────────────────────
# Used for polarised 80/20 enforcement and Foster RPE estimation. A session
# type absent from both sets is treated as moderate intensity.

#
# Swimming, rowing, hiking, skiing and climbing add their own types. Low:
# technique, UT2/UT1 (below the first threshold by definition), vertical hikes,
# ski-walking, ARC, and the first day of a back-to-back (long but easy). High:
# CSS, incline, bounding, ski-run and power-endurance intervals — all at or
# above threshold, all glycolytic. Deliberately in neither: plyometrics,
# eccentric and agility work, descent repeats, hangboarding and limit
# bouldering. They are hard on tissue (tendon, eccentric muscle damage) but
# short and low in metabolic load, which is what the 80/20 rule rations; their
# spacing is handled in the templates instead, and counting them as high would
# make the week generator delete the finger-strength and plyometric sessions
# those sports are built around.

_LOW_INTENSITY_TYPES = frozenset({
    "easy", "easy_recovery", "easy_spin", "endurance", "aerobic",
    "skills", "rest",
    "technique", "ut2", "ut1", "vert", "pole_hike", "arc", "back_to_back",
})

_HIGH_INTENSITY_TYPES = frozenset({
    "intervals", "tempo", "race_pace", "fartlek", "short_quality",
    "sweet_spot", "threshold", "vo2", "micro_bursts", "over_unders",
    "matchbook", "standing_starts", "descent_repeats",
    "sustained_climb", "tt_pace", "sprint", "anaerobic",
    "quality",
    "css", "incline_intervals", "bounding", "ski_intervals", "power_endurance",
})


# ─────────────────────────────────────────
# Workout builder dispatch
# ─────────────────────────────────────────

def _build_steps(
    workout_type: str,
    family: str,
    sport: str,
    phase: str,
    duration_min: int,
    build_idx: int,
    paces: dict | None,
    ftp: int | None,
    css: float | None,
    race_distance_m: float,
    capacity_km: float | None = None,
    variation: int = 0,
    structural_min: int | None = None,
    imperial: bool = False,
    lthr: int | None = None,
    mtb_discipline: str = "trail",
    cycling_discipline: str = "road_race",
) -> list[dict]:
    """Dispatch to the correct sport-family workout builder."""

    if family == "running":
        from app.calculators.plan.running import (
            _run_easy, _run_recovery, _run_long, _run_tempo,
            _run_intervals, _run_race_pace, _run_fartlek,
            _run_short_quality, _run_hill_sprints, _run_brick,
        )
        p = paces or _DEFAULT_RUN_PACES
        if workout_type == "easy":
            return _run_easy(duration_min, p, capacity_km=capacity_km, variation=variation,
                             structural_min=structural_min, imperial=imperial)
        if workout_type == "easy_recovery":
            return _run_recovery(duration_min, p, variation=variation, imperial=imperial)
        if workout_type == "long":
            return _run_long(duration_min, phase, p, capacity_km=capacity_km,
                             structural_min=structural_min, imperial=imperial,
                             race_distance_m=race_distance_m)
        if workout_type == "tempo":         return _run_tempo(duration_min, p, variation=variation, imperial=imperial)
        if workout_type == "intervals":     return _run_intervals(build_idx, p, variation=variation, imperial=imperial)
        if workout_type == "race_pace":     return _run_race_pace(p, race_distance_m, imperial=imperial)
        if workout_type == "fartlek":       return _run_fartlek(duration_min, p, variation=variation, imperial=imperial)
        if workout_type == "short_quality": return _run_short_quality(p, imperial=imperial)
        if workout_type == "hill_sprints":  return _run_hill_sprints(p, variation=variation, imperial=imperial)
        # A triathlon brick's run; its race distance is the run leg's.
        if workout_type == "brick_run":     return _run_brick(duration_min, phase, p, race_distance_m, imperial=imperial)

    elif family == "cycling":
        from app.calculators.plan.cycling import (
            _cy_endurance, _cy_easy_spin, _cy_tempo, _cy_sweet_spot,
            _cy_threshold, _cy_vo2, _cy_micro_bursts, _cy_over_unders,
            _cy_anaerobic, _cy_sprint, _cy_sustained_climb, _cy_tt_pace,
            _cy_long, _cy_race_pace, _cy_short_quality,
        )
        from app.calculators.plan.mtb import _mtb_field_test
        ih = int(lthr) if lthr else None
        if workout_type in ("easy", "endurance"):
            return _cy_endurance(duration_min, lthr=ih, ftp=ftp, variation=variation)
        if workout_type in ("easy_spin", "easy_recovery"):
            return _cy_easy_spin(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "tempo":          return _cy_tempo(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "sweet_spot":     return _cy_sweet_spot(duration_min, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "threshold":      return _cy_threshold(build_idx, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "intervals":      return _cy_vo2(build_idx, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "vo2":            return _cy_vo2(build_idx, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "micro_bursts":   return _cy_micro_bursts(lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "over_unders":    return _cy_over_unders(lthr=ih, ftp=ftp)
        if workout_type == "anaerobic":      return _cy_anaerobic(lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "sprint":         return _cy_sprint(lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "sustained_climb": return _cy_sustained_climb(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "tt_pace":        return _cy_tt_pace(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "long":           return _cy_long(duration_min, phase, lthr=ih, ftp=ftp)
        if workout_type == "race_pace":      return _cy_race_pace(lthr=ih, ftp=ftp, discipline=cycling_discipline)
        if workout_type == "short_quality":  return _cy_short_quality(lthr=ih, ftp=ftp)
        if workout_type.startswith("field_test"):
            test_type = workout_type.split(":", 1)[1] if ":" in workout_type else "ftp20"
            return _mtb_field_test(test_type, lthr=ih)

    elif family == "mountain_biking":
        from app.calculators.plan.mtb import (
            _mtb_endurance, _mtb_tempo, _mtb_sweet_spot, _mtb_threshold,
            _mtb_intervals, _mtb_micro_bursts, _mtb_over_unders,
            _mtb_matchbook, _mtb_standing_starts, _mtb_descent_repeats,
            _mtb_long, _mtb_race_pace, _mtb_skills, _mtb_recovery,
            _mtb_field_test,
        )
        ih = int(lthr) if lthr else None
        if workout_type in ("easy", "easy_spin", "endurance"):
            return _mtb_endurance(duration_min, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "easy_recovery": return _mtb_recovery(duration_min)
        if workout_type == "tempo":         return _mtb_tempo(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "sweet_spot":    return _mtb_sweet_spot(duration_min, lthr=ih, ftp=ftp)
        if workout_type == "threshold":     return _mtb_threshold(build_idx, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "intervals":     return _mtb_intervals(build_idx, lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "micro_bursts":  return _mtb_micro_bursts(lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "over_unders":   return _mtb_over_unders(lthr=ih, ftp=ftp)
        if workout_type == "matchbook":     return _mtb_matchbook(lthr=ih, ftp=ftp, variation=variation)
        if workout_type == "standing_starts": return _mtb_standing_starts(lthr=ih, ftp=ftp)
        if workout_type == "descent_repeats": return _mtb_descent_repeats(duration_min, lthr=ih)
        if workout_type == "long":          return _mtb_long(duration_min, phase, lthr=ih, ftp=ftp)
        if workout_type == "race_pace":     return _mtb_race_pace(lthr=ih, ftp=ftp, discipline=mtb_discipline)
        if workout_type == "skills":        return _mtb_skills(duration_min, discipline=mtb_discipline, variation=variation)
        if workout_type == "short_quality": return _mtb_standing_starts(lthr=ih, ftp=ftp)
        if workout_type.startswith("field_test"):
            test_type = workout_type.split(":", 1)[1] if ":" in workout_type else "ftp20"
            return _mtb_field_test(test_type, lthr=ih)

    elif family == "swimming":
        from app.calculators.plan.swimming import (
            _swim_aerobic, _swim_css, _swim_long, _swim_race_pace,
            _swim_short_quality, _swim_technique, _swim_vo2,
        )
        # The sport says pool or open water; see swimming.py.
        ow = sport.lower().replace(" ", "_") == "open_water_swimming"
        if workout_type in ("easy", "aerobic", "easy_recovery"):
            return _swim_aerobic(duration_min, css, variation=variation, open_water=ow)
        if workout_type == "technique":         return _swim_technique(duration_min, css, variation=variation, open_water=ow)
        if workout_type in ("css", "tempo", "threshold"):
            return _swim_css(build_idx, css, variation=variation, open_water=ow)
        if workout_type in ("vo2", "intervals"): return _swim_vo2(build_idx, css, variation=variation, open_water=ow)
        if workout_type == "long":              return _swim_long(duration_min, phase, css, open_water=ow)
        if workout_type == "race_pace":         return _swim_race_pace(race_distance_m, css, open_water=ow)
        if workout_type == "short_quality":     return _swim_short_quality(css, open_water=ow)

    elif family == "rowing":
        from app.calculators.plan.rowing import (
            _row_long, _row_race_pace, _row_short_quality, _row_sprint,
            _row_technique, _row_threshold, _row_ut1, _row_ut2,
        )
        if workout_type in ("ut2", "easy", "easy_recovery", "endurance", "aerobic"):
            return _row_ut2(duration_min, variation=variation)
        if workout_type == "ut1":               return _row_ut1(duration_min, variation=variation)
        if workout_type == "technique":         return _row_technique(duration_min, variation=variation)
        if workout_type in ("threshold", "tempo"): return _row_threshold(build_idx, variation=variation)
        if workout_type in ("race_pace", "intervals", "vo2"): return _row_race_pace(build_idx)
        if workout_type == "sprint":            return _row_sprint(variation=variation)
        if workout_type == "long":              return _row_long(duration_min)
        if workout_type == "short_quality":     return _row_short_quality()

    elif family == "hiking":
        from app.calculators.plan.hiking import (
            _hike_back_to_back, _hike_descent, _hike_easy, _hike_incline_intervals,
            _hike_long, _hike_vert,
        )
        ih = int(lthr) if lthr else None
        if workout_type in ("easy", "easy_recovery", "aerobic", "endurance"):
            return _hike_easy(duration_min)
        if workout_type == "vert":              return _hike_vert(duration_min, build_idx, imperial=imperial)
        if workout_type in ("incline_intervals", "intervals", "tempo", "quality", "short_quality", "race_pace"):
            return _hike_incline_intervals(build_idx, variation=variation, lthr=ih)
        if workout_type == "descent":           return _hike_descent(duration_min, build_idx, variation=variation)
        if workout_type == "back_to_back":      return _hike_back_to_back(duration_min, phase, build_idx, imperial=imperial)
        if workout_type == "long":              return _hike_long(duration_min, phase, build_idx, imperial=imperial)

    elif family == "nordic_skiing":
        from app.calculators.plan.skiing import (
            _ski_bounding, _ski_endurance, _ski_intervals, _ski_long, _ski_pole_hike,
            _ski_race_pace, _ski_short_quality, _ski_technique, _ski_threshold,
        )
        ih = int(lthr) if lthr else None
        if workout_type in ("endurance", "easy", "easy_recovery", "aerobic"):
            return _ski_endurance(duration_min, sport, variation=variation, lthr=ih)
        if workout_type == "technique":         return _ski_technique(duration_min, sport, variation=variation)
        if workout_type == "pole_hike":         return _ski_pole_hike(duration_min, build_idx, imperial=imperial, lthr=ih)
        if workout_type == "bounding":          return _ski_bounding(build_idx, variation=variation, lthr=ih)
        if workout_type in ("intervals", "vo2"): return _ski_intervals(build_idx, sport, lthr=ih)
        if workout_type in ("threshold", "tempo"): return _ski_threshold(build_idx, sport, lthr=ih)
        if workout_type == "race_pace":         return _ski_race_pace(sport, lthr=ih)
        if workout_type == "long":              return _ski_long(duration_min, sport, imperial=imperial)
        if workout_type == "short_quality":     return _ski_short_quality()

    elif family == "alpine_skiing":
        from app.calculators.plan.skiing import (
            _alp_aerobic, _alp_agility, _alp_eccentric, _alp_long,
            _alp_plyometrics, _alp_ski_intervals,
        )
        ih = int(lthr) if lthr else None
        if workout_type in ("aerobic", "easy", "easy_recovery", "endurance"):
            return _alp_aerobic(duration_min, variation=variation, lthr=ih)
        if workout_type == "eccentric":         return _alp_eccentric(build_idx, variation=variation)
        if workout_type == "plyometrics":       return _alp_plyometrics(build_idx, variation=variation)
        if workout_type == "agility":           return _alp_agility(duration_min, variation=variation)
        if workout_type in ("ski_intervals", "intervals", "quality", "short_quality", "race_pace"):
            return _alp_ski_intervals(build_idx, lthr=ih)
        if workout_type == "long":              return _alp_long(duration_min)

    elif family == "climbing":
        from app.calculators.plan.climbing import (
            _climb_arc, _climb_hangboard, _climb_limit_bouldering, _climb_long,
            _climb_power_endurance, _climb_short_quality, _climb_technique,
        )
        if workout_type in ("arc", "easy", "easy_recovery", "endurance", "aerobic"):
            return _climb_arc(duration_min, build_idx, variation=variation)
        if workout_type == "technique":         return _climb_technique(duration_min, variation=variation)
        if workout_type == "hangboard":         return _climb_hangboard(build_idx, variation=variation)
        if workout_type == "limit_bouldering":  return _climb_limit_bouldering(build_idx)
        if workout_type in ("power_endurance", "intervals", "quality", "race_pace"):
            return _climb_power_endurance(build_idx, variation=variation)
        if workout_type == "long":              return _climb_long(duration_min)
        if workout_type == "short_quality":     return _climb_short_quality()

    # Generic fallback
    from app.calculators.plan.generic import (
        _generic_easy, _generic_aerobic, _generic_quality, _generic_long,
    )
    if workout_type in ("easy", "endurance"): return _generic_easy(duration_min, sport)
    if workout_type in ("aerobic",):           return _generic_aerobic(duration_min, sport)
    if workout_type in ("intervals", "tempo", "quality", "race_pace", "short_quality"):
        return _generic_quality(duration_min, sport)
    if workout_type == "long":                 return _generic_long(duration_min, sport)
    return _generic_easy(duration_min, sport)
