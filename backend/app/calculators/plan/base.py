# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training plan — shared base vocabulary for the ``plan`` package.

This module is the foundation the rest of the package builds on. It is a flat,
intentionally cohesive collection of small pure helpers and lookup tables, in
roughly the order a plan is assembled:

  - Sport-family classification        (``_sport_family`` and its sport sets)
  - VDOT running model                 (``calculate_vdot``, ``vdot_to_paces``)
  - CSS swim model                     (``_css_pace_sec_per_100m``)
  - Intensity-zone / HR / power note formatters
  - Volume + phase + 3:1 progression tables and helpers
  - Warm-up / cool-down and walk-break capacity
  - days_per_week template trimming/filling
  - Weekly day templates (running / cycling / MTB / generic)
  - Duration / distance estimation from workout steps
  - Workout title + description builders

Every public name here is consumed by the sibling modules
(``generator``, ``running``, ``cycling``, ``mtb``) and re-exported from the
package ``__init__``; the helpers are deliberately kept together rather than
split, since each sibling pulls a wide, overlapping cross-section of them.

References
----------
- Daniels, J. (2013). *Daniels' Running Formula* (3rd ed.). Human Kinetics.
  VDOT tables and pace-zone calculations.
- Seiler, S. (2010). What is best practice for training intensity and duration
  distribution in endurance athletes? *Int J Sports Physiol Perform*, 5(3), 276-291.
  Polarized 80/20 intensity distribution basis.
- Stöggl, T., & Sperlich, B. (2014). Polarized training has greater impact on
  key endurance variables than threshold, high intensity, or high volume training.
  *Front Physiol*, 5, 33. — POL outperforming all other distributions.
- Issurin, V. B. (2010). New horizons for the methodology and physiology of
  training periodization. *Sports Med*, 40(3), 189-206.
  Block periodization model: Accumulation → Transmutation → Realization.
- Bosquet, L., et al. (2007). Effects of tapering on performance: a meta-analysis.
  *Med Sci Sports Exerc*, 39(8), 1358-1365.
  Optimal taper: 2 weeks, exponential volume decrease to 40-60%, maintain intensity.
- Foster, C., et al. (2001). A new approach to monitoring exercise training.
  *J Strength Cond Res*, 15(1), 109-115.
  Training monotony and strain; monotony >2.0 predicts illness.
"""

from __future__ import annotations

import math
from datetime import date, timedelta
from typing import Sequence

# ─────────────────────────────────────────
# Sport family classification
# ─────────────────────────────────────────

_RUNNING_SPORTS = {
    "running", "trail_running", "treadmill_running", "road_running", "virtual_running",
}
_CYCLING_SPORTS = {
    "cycling", "road_biking", "gravel_cycling",
    "virtual_cycling", "indoor_cycling", "e_biking",
}
_MTB_SPORTS = {"mountain_biking", "trail_biking"}
_SWIMMING_SPORTS = {"swimming", "open_water_swimming", "lap_swimming", "pool_swimming"}
_ROWING_SPORTS   = {"rowing", "indoor_rowing"}
_STRENGTH_SPORTS = {"strength_training", "strength", "bodybuilding", "powerlifting"}
_HIKING_SPORTS   = {"hiking", "walking"}
_CLIMBING_SPORTS = {
    "climbing", "rock_climbing", "sport_climbing", "indoor_climbing",
    "bouldering", "mountaineering",
}
# Skiing is two families, not one, because the two train for different things:
# cross-country and ski mountaineering are aerobic endurance sports (VO2max and
# threshold decide races), while alpine skiing is repeated 60-120 s bouts of
# eccentric and isometric leg work on a lift-served day, trained mostly off the
# snow. The sport string carries the discipline (the goal editors write
# `cross_country_skiing` / `backcountry_skiing` / `alpine_skiing`), so no
# separate discipline column is needed. Bare "skiing" — what goals were saved
# as before the split, under Ski race / Skimo / Backcountry tour presets — is
# the endurance family.
_NORDIC_SKI_SPORTS = {
    "skiing", "cross_country_skiing", "nordic_skiing", "skate_skiing", "classic_skiing",
    "roller_skiing", "backcountry_skiing", "ski_mountaineering", "skimo",
}
_ALPINE_SKI_SPORTS = {"alpine_skiing", "downhill_skiing", "resort_skiing", "snowboarding"}
_PADDLING_SPORTS = {
    "paddling", "kayaking", "kayak", "canoeing", "canoe",
    "stand_up_paddleboarding", "sup", "paddleboarding", "whitewater",
}


def _sport_family(sport: str) -> str:
    """Map a raw sport string to a parent family used by the planner."""
    s = sport.lower().replace(" ", "_")
    if s in _RUNNING_SPORTS:   return "running"
    if s in _MTB_SPORTS:       return "mountain_biking"
    if s in _CYCLING_SPORTS:   return "cycling"
    if s in _SWIMMING_SPORTS:  return "swimming"
    if s in _ROWING_SPORTS:    return "rowing"
    if s in _HIKING_SPORTS:    return "hiking"
    if s in _NORDIC_SKI_SPORTS: return "nordic_skiing"
    if s in _ALPINE_SKI_SPORTS: return "alpine_skiing"
    if s in _CLIMBING_SPORTS:  return "climbing"
    if s in _PADDLING_SPORTS:  return "paddling"
    if s in _STRENGTH_SPORTS:  return "strength"
    if "triathlon" in s:       return "triathlon"
    return "generic"


# ─────────────────────────────────────────
# VDOT system — Jack Daniels (running only)
# ─────────────────────────────────────────

def calculate_vdot(distance_m: float, time_sec: float) -> float:
    """Jack Daniels VDOT formula (Daniels' Running Formula, 3rd ed., Chapter 3)."""
    t = time_sec / 60
    v = distance_m / t
    vo2 = -4.60 + 0.182258 * v + 0.000104 * v ** 2
    pct = (0.8
           + 0.1894393 * math.exp(-0.012778 * t)
           + 0.2989558 * math.exp(-0.1932605 * t))
    return vo2 / pct


def _velocity_for_pct_vo2max(vdot: float, pct: float) -> float:
    a, b, c = 0.000104, 0.182258, -(4.60 + vdot * pct)
    return (-b + math.sqrt(b * b - 4 * a * c)) / (2 * a)


def vdot_to_paces(vdot: float) -> dict[str, float]:
    """
    Pace (sec/km) for each training zone at the given VDOT.

    Zone fractions are % of VO2max velocity, synthesised from:
      - Daniels (2013): recovery = ~59-66%, easy = ~66-74%, marathon = ~80-85%,
        threshold = ~88-92%, interval = ~95-100%
      - Pfitzinger, P., & Douglas, S. (2014). *Advanced Marathoning* (3rd ed.):
        paces for repetition work at 105% VO2max velocity.
    """
    zones = {
        "recovery":    0.62,
        "easy":        0.70,
        "marathon":    0.82,
        "threshold":   0.90,
        "interval":    0.98,
        "repetition":  1.05,
    }
    return {z: round(1000 / _velocity_for_pct_vo2max(vdot, p) * 60, 1) for z, p in zones.items()}


def _fmt_pace(sec_per_km: float, imperial: bool = False) -> str:
    if imperial:
        sec = sec_per_km * 1.60934
        m, s = int(sec // 60), int(sec % 60)
        return f"{m}:{s:02d}/mi"
    m, s = int(sec_per_km // 60), int(sec_per_km % 60)
    return f"{m}:{s:02d}/km"


def _fmt_dist_m(meters: int, imperial: bool = False) -> str:
    if imperial:
        if meters >= 1600:
            return f"{meters / 1609.344:.2f} mi"
        return f"{round(meters * 1.09361)} yd"
    if meters >= 1000:
        km = meters / 1000
        return f"{km:.0f} km" if meters % 1000 == 0 else f"{km:.1f} km"
    return f"{meters} m"


_DEFAULT_VDOT      = 42.0
_DEFAULT_RUN_PACES = vdot_to_paces(_DEFAULT_VDOT)


def _best_vdot_from_pace_bests(pace_bests: list[tuple[int, float]]) -> float | None:
    if not pace_bests:
        return None
    best = None
    for dist_m, speed_mps in pace_bests:
        if dist_m < 3000 or speed_mps <= 0:
            continue
        try:
            v = calculate_vdot(dist_m, dist_m / speed_mps)
            if v > 0 and (best is None or v > best):
                best = v
        except Exception:
            continue
    return best


# ─────────────────────────────────────────
# CSS (Critical Swim Speed)
# ─────────────────────────────────────────

def _css_pace_sec_per_100m(pace_bests: list[tuple[int, float]]) -> float | None:
    """CSS estimate from best 400 m and 200 m swim times (Wakayoshi et al., 1992)."""
    bests = {d: s for d, s in pace_bests}
    t400 = (400 / bests[400]) if 400 in bests else None
    t200 = (200 / bests[200]) if 200 in bests else None
    if t400 and t200:
        css_mps = (400 - 200) / (t400 - t200)
        return 100 / css_mps if css_mps > 0 else None
    return None


# ─────────────────────────────────────────
# Intensity zones — HR-based (non-running)
# ─────────────────────────────────────────

def _hr_zone_desc(zone: int, max_hr: int | None = None) -> str:
    pct_ranges = {1: (0.50, 0.60), 2: (0.60, 0.70), 3: (0.70, 0.80), 4: (0.80, 0.90), 5: (0.90, 1.00)}
    zone_names = {1: "Zone 1 (very easy)", 2: "Zone 2 (easy/aerobic)", 3: "Zone 3 (moderate)",
                  4: "Zone 4 (threshold)", 5: "Zone 5 (VO2max)"}
    if max_hr and zone in pct_ranges:
        lo, hi = pct_ranges[zone]
        return f"Zone {zone}, {int(max_hr*lo)}–{int(max_hr*hi)} bpm"
    return zone_names.get(zone, f"Zone {zone}")


# ─────────────────────────────────────────
# HR / power range formatters (shared by MTB and road cycling)
# ─────────────────────────────────────────

def _hr_zone(lo_pct: float, hi_pct: float, lthr: int | None) -> str:
    """Friel-style HR descriptor; falls back to RPE when LTHR is unset.
    Friel, J. (2018). *The Cyclist's Training Bible*, 5th ed. VeloPress.
    """
    if lthr and lthr > 0:
        return f"HR {int(lthr * lo_pct)}–{int(lthr * hi_pct)} bpm"
    return f"{int(lo_pct*100)}–{int(hi_pct*100)}% LTHR"


def _pwr_zone(lo_pct: float, hi_pct: float, ftp: int | None) -> str | None:
    """Coggan power descriptor; None when FTP is unset.
    Coggan, A., & Allen, H. (2019). *Training and Racing with a Power Meter*, 3rd ed. VeloPress.
    """
    if ftp and ftp > 0:
        return f"{int(ftp * lo_pct)}–{int(ftp * hi_pct)} W ({int(lo_pct*100)}–{int(hi_pct*100)}% FTP)"
    return None


def _target_note(lo_hr_pct: float, hi_hr_pct: float, lthr: int | None,
                 lo_ftp_pct: float | None, hi_ftp_pct: float | None, ftp: int | None,
                 rpe: str) -> str:
    """Compose 'HR X–Y · Z–W W (NN–MM% FTP) · RPE «label»' note."""
    parts = [_hr_zone(lo_hr_pct, hi_hr_pct, lthr)]
    if lo_ftp_pct is not None and hi_ftp_pct is not None:
        pw = _pwr_zone(lo_ftp_pct, hi_ftp_pct, ftp)
        if pw:
            parts.append(pw)
    parts.append(f"RPE {rpe}")
    return " · ".join(parts)


# ─────────────────────────────────────────
# Volume helpers
# ─────────────────────────────────────────

_SPORT_DEFAULT_WEEKLY_KM = {
    "running":         20.0,
    "cycling":         60.0,
    "mountain_biking": 50.0,
    "swimming":         5.0,
    "rowing":          20.0,
    "hiking":          15.0,
    "nordic_skiing":   30.0,
    "alpine_skiing":   20.0,
    "climbing":        20.0,
    "generic":         20.0,
    "triathlon":       20.0,
}

_SPORT_MAX_WEEKLY_KM = {
    "running":          80.0,
    "cycling":         250.0,
    "mountain_biking": 150.0,
    "swimming":         25.0,
    "rowing":           50.0,
    "hiking":           40.0,
    "nordic_skiing":    80.0,
    "alpine_skiing":    50.0,
    "climbing":         40.0,
    "generic":          60.0,
    "triathlon":        80.0,
}


def _current_weekly_km(activity_history: Sequence, sport_family: str,
                       today: date | None = None) -> float:
    """Median weekly distance over the last 8 weeks for the given sport family.

    `today` is the plan's own date. It used to be read from the wall clock
    here, which made a plan depend on when it was computed as well as on the
    date it was asked for — so no other device could reproduce it.
    """
    today = today or date.today()
    cutoff = today - timedelta(weeks=8)
    weekly: dict[int, float] = {}
    for act in activity_history:
        if not act.distance_meters or not act.started_at:
            continue
        if _sport_family(act.sport or "") != sport_family:
            continue
        d = act.started_at.date() if hasattr(act.started_at, "date") else act.started_at
        if d < cutoff:
            continue
        wk = (d - cutoff).days // 7
        weekly[wk] = weekly.get(wk, 0.0) + act.distance_meters / 1000
    if not weekly:
        return 0.0
    vals = sorted(weekly.values())
    return vals[len(vals) // 2]


def _max_weekly_km_for_race(race_distance_m: float, family: str) -> float:
    base = _SPORT_MAX_WEEKLY_KM.get(family, 60.0)
    if family == "running":
        if race_distance_m >= 42000: return 80.0
        if race_distance_m >= 21000: return 55.0
        if race_distance_m >= 10000: return 45.0
        return 35.0
    if family == "cycling":
        if race_distance_m >= 160000: return 250.0
        if race_distance_m >= 100000: return 180.0
        return 120.0
    if family == "mountain_biking":
        if race_distance_m >= 100000: return 150.0
        if race_distance_m >=  60000: return 120.0
        return 90.0
    return base


def _target_peak_long_km(race_distance_m: float, family: str) -> float:
    """
    Target peak long distance, backed by coaching literature.

    Running (Daniels 2013 / Pfitzinger 2014 / Powell-Moehl ultra coaching):
      ≤ 5 km  : 15 km — aerobic base > specificity at this distance
      ≤ 10 km : 18 km — Daniels' E-phase long-run recommendation
      ≤ half  : 85% of race distance (Higdon / Pfitzinger consensus)
      marathon: 80% ≈ 34 km (Pfitzinger peak of 20–22 miles)
      50–80 km ultra: 55%, cap 45 km (back-to-back weekends cover the rest)
      100 km+ ultra : 50 km cap (time-on-feet > peak distance)

    Cycling and swimming scale proportionally with their recovery profiles.
    MTB long-ride ceiling is lower than road because terrain raises load.
    """
    rd_km = race_distance_m / 1000
    if family == "running":
        if rd_km <= 5.0:   return 15.0
        if rd_km <= 10.0:  return 18.0
        if rd_km <= 21.1:  return max(rd_km * 0.85, 16.0)
        if rd_km <= 42.2:  return rd_km * 0.80
        if rd_km <= 80.0:  return min(rd_km * 0.55, 45.0)
        return 50.0
    if family == "cycling":
        if rd_km <= 60.0:  return rd_km * 0.85
        if rd_km <= 160.0: return min(rd_km * 0.75, 130.0)
        return min(rd_km * 0.60, 200.0)
    if family == "mountain_biking":
        if rd_km <= 40.0:  return rd_km * 0.85
        if rd_km <= 100.0: return min(rd_km * 0.75, 70.0)
        return min(rd_km * 0.55, 90.0)
    if family == "swimming":
        return min(rd_km * 0.85, 12.0)
    return min(rd_km * 0.75, _SPORT_MAX_WEEKLY_KM.get(family, 60.0) * 0.4)


# ─────────────────────────────────────────
# Phase logic
# ─────────────────────────────────────────

def _phase_for_week(week_num: int, total_weeks: int) -> str:
    """
    Block-periodisation phase assignment (Issurin 2010).

    Always opens with base work regardless of plan length, then proportionally
    compresses intermediate phases when time is short.  Jumping straight into
    high-intensity work without an aerobic base raises injury risk and does not
    improve race performance (Daniels 2013 / Friel 2018 / Pfitzinger 2024).

    Phase allocations by total_weeks:
      ≤ 1 w  : taper only
      2–4 w  : base + taper
      5–6 w  : base + build + taper
      ≥ 7 w  : full model (compressed to fit)
    """
    if total_weeks <= 1:
        return "taper"
    taper_w = min(3, max(1, total_weeks // 5))
    peak_w  = min(4, max(1, (total_weeks - taper_w) // 4)) if total_weeks > 6 else 0
    build_w = min(5, max(1, (total_weeks - taper_w - peak_w) // 3)) if total_weeks > 4 else 0
    base_w  = max(1, total_weeks - taper_w - peak_w - build_w)

    if week_num < base_w:                    return "base"
    if week_num < base_w + build_w:          return "build"
    if week_num < base_w + build_w + peak_w: return "peak"
    return "taper"


# ─────────────────────────────────────────
# Volume progression (3:1 cycle)
# ─────────────────────────────────────────

def _weekly_volume_km(week_num: int, total_weeks: int, start_km: float,
                      max_km: float, weeks_remaining: float,
                      cycle_len: int = 4) -> float:
    """
    Volume per week with 3:1 build/recovery cycling.

    cycle_len: 4 = 3:1 (Foster 2001 consensus for amateur athletes).
               3 = 2:1 (Issurin 2010 — used for MTB Enduro anaerobic blocks).

    Bosquet et al. (2007) taper: 75% / 60% / 40% at 3 / 2 / 1 weeks out.
    """
    cycle_len = max(2, cycle_len)
    recovery_pos = cycle_len - 1
    taper_w     = min(3, max(1, total_weeks // 5))
    non_taper_w = max(1, total_weeks - taper_w)
    last_bw     = non_taper_w - 1
    peak_km     = min(start_km * (1.10 ** (last_bw - last_bw // cycle_len)), max_km)
    if weeks_remaining <= 1: return max(peak_km * 0.40, 5.0)
    if weeks_remaining <= 2: return max(peak_km * 0.60, 10.0)
    if weeks_remaining <= 3: return max(peak_km * 0.75, 15.0)
    cycle_pos  = week_num % cycle_len
    build_week = week_num - (week_num // cycle_len)
    target = min(start_km * (1.10 ** build_week), max_km)
    if cycle_pos == recovery_pos:
        target *= 0.75
    return round(target, 1)


# ─────────────────────────────────────────
# Warm-up / cool-down
# ─────────────────────────────────────────

def _walk_warmup() -> dict:
    return {"type": "walk", "duration_min": 5, "note": "Brisk 5-min walk to warm up"}

def _walk_cooldown() -> dict:
    return {"type": "walk", "duration_min": 5, "note": "5-min walk to cool down and recover"}


# ─────────────────────────────────────────
# Running walk-break capacity model
# ─────────────────────────────────────────

def _capacity_km(effective_weeks: float) -> float:
    """
    Smooth continuous-run capacity in km.

    capacity = 1.5 × 1.15^effective_weeks

    effective_weeks combines VDOT-baseline and accumulated training, so a
    seasoned runner (high VDOT) starts with a high capacity from day one
    and walk breaks are never introduced. A new runner starts low and
    transitions to continuous running gradually as capacity grows.
    """
    return 1.5 * (1.15 ** max(0.0, effective_weeks))


# ─────────────────────────────────────────
# days_per_week template adjustment
# ─────────────────────────────────────────

_REMOVAL_PRIORITY: dict[str, int] = {
    "easy": 0, "endurance": 0, "aerobic": 0, "easy_spin": 0, "easy_recovery": 0,
    "short_quality": 1,
    "skills": 1,
    "fartlek": 2, "sweet_spot": 2,
    "tempo": 3,
    "over_unders": 3,
    "intervals": 4, "race_pace": 4, "vo2": 4,
    "threshold": 4, "micro_bursts": 4, "matchbook": 4,
    "standing_starts": 4, "descent_repeats": 4,
    "sustained_climb": 4, "tt_pace": 4, "sprint": 4, "anaerobic": 4,
    "long": 99, "rest": 99,
    # Swimming, rowing, hiking, skiing, climbing. The easy staples go first, the
    # technique and conditioning sessions next, the defining quality last.
    "ut2": 0, "arc": 0, "vert": 1, "pole_hike": 1, "technique": 1, "ut1": 1,
    "agility": 2, "descent": 2, "eccentric": 2, "back_to_back": 2,
    "css": 4, "incline_intervals": 4, "bounding": 4, "plyometrics": 3,
    "ski_intervals": 4, "hangboard": 3, "limit_bouldering": 4, "power_endurance": 4,
}

_EASY_FILL: dict[str, str] = {
    "running": "easy", "cycling": "easy_spin", "mountain_biking": "easy_spin",
    "swimming": "aerobic", "rowing": "easy", "hiking": "easy",
    "generic": "easy", "triathlon": "easy",
    "nordic_skiing": "endurance", "alpine_skiing": "aerobic", "climbing": "arc",
}

_QUALITY_TYPES = frozenset({
    "fartlek", "intervals", "tempo", "race_pace", "short_quality",
    "sweet_spot", "quality",
    "threshold", "micro_bursts", "over_unders", "matchbook",
    "standing_starts", "descent_repeats",
    "vo2", "sustained_climb", "tt_pace", "sprint", "anaerobic",
    "css", "incline_intervals", "bounding", "ski_intervals", "plyometrics",
    "limit_bouldering", "power_endurance", "hangboard",
})
_EASY_TYPES = frozenset({"easy", "aerobic", "endurance", "easy_spin", "easy_recovery", "skills",
                         "technique", "ut2", "ut1", "vert", "pole_hike", "arc"})


def _max_consecutive_workouts(template: list[str]) -> int:
    best = cur = 0
    for t in template:
        cur = cur + 1 if t != "rest" else 0
        best = max(best, cur)
    return best


def _adjacent_easy_quality_pairs(template: list[str]) -> int:
    count = 0
    for i in range(len(template) - 1):
        a, b = template[i], template[i + 1]
        if (a in _QUALITY_TYPES and b in _EASY_TYPES) or (a in _EASY_TYPES and b in _QUALITY_TYPES):
            count += 1
    return count


def _apply_days_per_week(template: list[str], days_per_week: int, family: str) -> list[str]:
    result = list(template)
    target = max(1, min(days_per_week, 7))

    while sum(1 for t in result if t != "rest") > target:
        candidates = [i for i in range(len(result))
                      if result[i] != "rest" and result[i] != "long"]
        if not candidates:
            break
        min_pri = min(_REMOVAL_PRIORITY.get(result[i], 1) for i in candidates)
        lowest  = [i for i in candidates if _REMOVAL_PRIORITY.get(result[i], 1) == min_pri]

        def _removal_score(i: int) -> tuple[int, int]:
            trial = list(result)
            trial[i] = "rest"
            return (_max_consecutive_workouts(trial), _adjacent_easy_quality_pairs(trial))

        best = min(lowest, key=_removal_score)
        result[best] = "rest"

    easy = _EASY_FILL.get(family, "easy")
    while sum(1 for t in result if t != "rest") < target:
        rest_slots = [i for i in range(1, len(result)) if result[i] == "rest"]
        if not rest_slots:
            rest_slots = [0] if result[0] == "rest" else []
        if not rest_slots:
            break

        def _insertion_score(i: int) -> tuple[int, int]:
            trial = list(result)
            trial[i] = easy
            return (_max_consecutive_workouts(trial), i)

        best = min(rest_slots, key=_insertion_score)
        result[best] = easy

    return result


# ─────────────────────────────────────────
# Weekly day templates
# ─────────────────────────────────────────

_TEMPLATES: dict[str, dict[str, list[str]]] = {
    "running": {
        "base":  ["rest", "easy", "rest", "fartlek",   "rest",          "easy",      "long"],
        "build": ["rest", "easy", "rest", "intervals", "easy_recovery", "tempo",     "long"],
        "peak":  ["rest", "easy", "rest", "intervals", "easy_recovery", "race_pace", "long"],
        "taper": ["rest", "easy", "rest", "short_quality", "rest",      "easy",      "long"],
    },
    "cycling": {
        "base":  ["rest", "endurance", "rest", "endurance",    "rest", "endurance", "long"],
        "build": ["rest", "endurance", "easy_spin", "sweet_spot", "rest", "intervals", "long"],
        "peak":  ["rest", "endurance", "easy_spin", "tempo",      "rest", "race_pace", "long"],
        "taper": ["rest", "easy_spin", "rest", "short_quality",   "rest", "endurance", "long"],
    },
    "generic": {
        "base":  ["rest", "easy", "rest", "aerobic",  "rest", "easy",    "long"],
        "build": ["rest", "easy", "easy", "quality",  "rest", "quality", "long"],
        "peak":  ["rest", "easy", "easy", "quality",  "rest", "quality", "long"],
        "taper": ["rest", "easy", "rest", "easy",     "rest", "easy",    "long"],
    },
}
_TEMPLATES["triathlon"] = _TEMPLATES["running"]

# Swimming. Pool and open water share one template: the slots are the same
# kinds of session, and the builders turn each into its open-water form when
# the goal's sport is open_water_swimming. Technique holds a slot in every
# phase because in swimming, unlike running, economy (distance per stroke) is
# the largest single determinant of speed below elite level (Barbosa et al.
# 2010), and it decays without practice. CSS threshold work is the core of the
# hard work for 400 m+ events (Wakayoshi 1992; Pyne & Sharp 2014); VO2 sets
# arrive in build and race-specific sets in peak. Order matters in build: the
# week generator's 25% polarisation cap turns the *first* surplus hard session
# into aerobic volume, and CSS is the one that should survive a short week.
_TEMPLATES["swimming"] = {
    "base":  ["rest", "technique", "aerobic", "rest", "css",       "aerobic",   "long"],
    "build": ["rest", "technique", "vo2",     "rest", "css",       "aerobic",   "long"],
    "peak":  ["rest", "technique", "vo2",     "rest", "race_pace", "aerobic",   "long"],
    "taper": ["rest", "technique", "rest",    "rest", "short_quality", "aerobic", "long"],
}

# Rowing, erg-first, in the British Rowing / Concept2 bands: UT2 (the bulk —
# ~75-80% of a rower's volume sits below the first threshold, Steinacker 1993;
# Fiskerstrand & Seiler 2004), UT1, AT, TR (2k-pace pieces) and AN (sprints).
# One hard session in base, per the planner's coupling rule; 2k-specific work
# from build on.
_TEMPLATES["rowing"] = {
    "base":  ["rest", "ut2", "technique", "threshold", "rest", "ut1",       "long"],
    "build": ["rest", "ut2", "threshold", "ut1",       "rest", "race_pace", "long"],
    "peak":  ["rest", "ut2", "race_pace", "technique", "rest", "sprint",    "long"],
    "taper": ["rest", "ut2", "rest",      "short_quality", "rest", "technique", "long"],
}

# Hiking. Vertical and time on feet under a pack are what a mountain day asks
# for, so the week is built around climbing sessions and one loaded long hike.
# Descent conditioning starts in base, small, because the soreness eccentric
# downhill work causes is largely prevented by a first, light exposure (the
# repeated-bout effect, Nosaka & Clarkson 1995); leaving it to peak is how
# quads get wrecked on the objective. Back-to-back days arrive in build: two
# consecutive long days train second-day fatigue for multi-day trips without
# the risk of one enormous day (Koop 2016).
_TEMPLATES["hiking"] = {
    "base":  ["rest", "easy", "vert",              "rest", "descent", "rest",         "long"],
    "build": ["rest", "easy", "incline_intervals", "rest", "vert",    "back_to_back", "long"],
    "peak":  ["rest", "easy", "incline_intervals", "descent", "rest", "back_to_back", "long"],
    "taper": ["rest", "easy", "rest",              "vert", "rest",    "easy",         "long"],
}

# Cross-country skiing and ski mountaineering. Base is the dry-land season for
# a winter race, so it carries ski-walking with poles and uphill bounding —
# the dry-land forms elite XC skiers use for specific work (Sandbakk &
# Holmberg 2017). Build brings threshold and uphill VO2 intervals, uphill
# because the climbs decide XC races (Sandbakk et al. 2011); peak turns to
# race pace.
_TEMPLATES["nordic_skiing"] = {
    "base":  ["rest", "endurance", "technique", "pole_hike", "rest", "bounding",  "long"],
    "build": ["rest", "endurance", "threshold", "technique", "rest", "intervals", "long"],
    "peak":  ["rest", "endurance", "intervals", "technique", "rest", "race_pace", "long"],
    "taper": ["rest", "endurance", "rest",      "short_quality", "rest", "technique", "long"],
}

# Alpine skiing: a dry-land conditioning block. A run is 60-120 s of mostly
# eccentric and isometric quadriceps work at high force (Hintermeister et al.
# 1995; Berg et al. 1995), repeated all day, so the plan builds an aerobic base
# for recovering between runs, eccentric strength for the loading, and in build
# the plyometric, agility and lactic work that transfers to turning. Heavy leg
# strength comes from the strength planner (Include strength) rather than
# being duplicated here.
_TEMPLATES["alpine_skiing"] = {
    "base":  ["rest", "aerobic", "eccentric",   "aerobic",   "rest", "agility",       "long"],
    "build": ["rest", "aerobic", "plyometrics", "agility",   "rest", "ski_intervals", "long"],
    "peak":  ["rest", "aerobic", "plyometrics", "eccentric", "rest", "ski_intervals", "long"],
    "taper": ["rest", "aerobic", "rest",        "agility",   "rest", "aerobic",       "long"],
}

# Climbing. Every finger-intensive session (hangboard, limit bouldering, power
# endurance) is at least 48 h from the next, because tendon and pulley collagen
# adapts over weeks and recovers more slowly than muscle (Magnusson et al.
# 2010; Schöffl et al. 2016). Base builds finger strength on the hangboard and
# a volume base with ARC; build adds limit bouldering (the strength-power a
# grade demands) and power endurance for routes.
_TEMPLATES["climbing"] = {
    "base":  ["rest", "hangboard",        "arc",       "rest", "technique",       "rest", "long"],
    "build": ["rest", "limit_bouldering", "arc",       "rest", "power_endurance", "rest", "long"],
    "peak":  ["rest", "limit_bouldering", "technique", "rest", "power_endurance", "rest", "long"],
    "taper": ["rest", "technique",        "rest",      "short_quality", "rest",   "rest", "long"],
}

# MTB discipline-specific templates
_TEMPLATES_MTB: dict[str, dict[str, list[str]]] = {
    "xco": {
        "base":  ["rest", "endurance", "skills",        "endurance",    "rest",      "tempo",        "long"],
        "build": ["rest", "endurance", "micro_bursts",  "sweet_spot",   "easy_spin", "intervals",    "long"],
        "peak":  ["rest", "endurance", "standing_starts","threshold",   "easy_spin", "race_pace",    "long"],
        "taper": ["rest", "endurance", "rest",           "short_quality","rest",     "easy_spin",    "long"],
    },
    "xcm": {
        "base":  ["rest", "endurance", "skills",        "endurance",    "rest",      "tempo",        "long"],
        "build": ["rest", "endurance", "skills",        "sweet_spot",   "easy_spin", "intervals",    "long"],
        "peak":  ["rest", "endurance", "skills",        "threshold",    "easy_spin", "race_pace",    "long"],
        "taper": ["rest", "endurance", "rest",          "short_quality","rest",      "easy_spin",    "long"],
    },
    "enduro": {
        "base":  ["rest", "endurance", "skills",     "endurance",       "skills",    "descent_repeats", "long"],
        "build": ["rest", "endurance", "matchbook",  "skills",          "easy_spin", "descent_repeats", "long"],
        "peak":  ["rest", "endurance", "skills",     "matchbook",       "easy_spin", "race_pace",       "long"],
        "taper": ["rest", "endurance", "skills",     "short_quality",   "rest",      "skills",          "long"],
    },
    "trail": {
        "base":  ["rest", "endurance", "skills",     "endurance",       "rest",      "tempo",          "long"],
        "build": ["rest", "endurance", "skills",     "sweet_spot",      "easy_spin", "intervals",      "long"],
        "peak":  ["rest", "endurance", "skills",     "threshold",       "easy_spin", "race_pace",      "long"],
        "taper": ["rest", "endurance", "rest",       "skills",          "rest",      "easy_spin",      "long"],
    },
}
_TEMPLATES["mountain_biking"] = _TEMPLATES_MTB["trail"]


def _mtb_template_for(discipline: str) -> dict[str, list[str]]:
    return _TEMPLATES_MTB.get(discipline.lower(), _TEMPLATES_MTB["trail"])


# Road-cycling discipline-specific templates
_TEMPLATES_CY: dict[str, dict[str, list[str]]] = {
    "road_race": {
        "base":  ["rest", "endurance", "tempo",       "endurance",  "rest",      "tempo",      "long"],
        "build": ["rest", "endurance", "sweet_spot",  "endurance",  "easy_spin", "threshold",  "long"],
        "peak":  ["rest", "endurance", "vo2",         "tempo",      "easy_spin", "race_pace",  "long"],
        "taper": ["rest", "endurance", "rest",        "short_quality", "rest",   "easy_spin",  "long"],
    },
    "time_trial": {
        "base":  ["rest", "endurance", "tempo",       "endurance",  "rest",      "sweet_spot", "long"],
        "build": ["rest", "endurance", "vo2",         "endurance",  "easy_spin", "threshold",  "long"],
        "peak":  ["rest", "endurance", "tt_pace",     "tempo",      "easy_spin", "threshold",  "long"],
        "taper": ["rest", "endurance", "rest",        "tt_pace",    "rest",      "easy_spin",  "long"],
    },
    "hill_climb": {
        "base":  ["rest", "endurance", "sweet_spot",       "endurance",  "rest",      "tempo",          "long"],
        "build": ["rest", "endurance", "sustained_climb",  "endurance",  "easy_spin", "threshold",      "long"],
        "peak":  ["rest", "endurance", "sustained_climb",  "tempo",      "easy_spin", "race_pace",      "long"],
        "taper": ["rest", "endurance", "rest",             "sustained_climb", "rest", "easy_spin",      "long"],
    },
    "criterium": {
        "base":  ["rest", "endurance", "sweet_spot",  "endurance",  "rest",      "tempo",       "long"],
        "build": ["rest", "endurance", "anaerobic",   "over_unders","easy_spin", "sprint",      "long"],
        "peak":  ["rest", "endurance", "anaerobic",   "over_unders","easy_spin", "race_pace",   "long"],
        "taper": ["rest", "endurance", "rest",        "sprint",     "rest",      "easy_spin",   "long"],
    },
}
_TEMPLATES["cycling"] = _TEMPLATES_CY["road_race"]


def _cy_template_for(discipline: str) -> dict[str, list[str]]:
    return _TEMPLATES_CY.get(discipline.lower(), _TEMPLATES_CY["road_race"])


# ─────────────────────────────────────────
# Duration / distance from steps
# ─────────────────────────────────────────

def _duration_from_steps(steps: list[dict], paces: dict | None) -> int:
    p = paces or _DEFAULT_RUN_PACES
    total = 0.0
    for s in steps:
        t = s.get("type")
        if t in ("run", "warmup", "cooldown", "walk", "ride", "swim", "activity"):
            total += s.get("duration_min", 0)
        elif t in ("interval_set", "effort_set"):
            reps = s.get("reps", 0)
            if "duration_min_each" in s:
                total += reps * s["duration_min_each"]
                total += reps * s.get("rest_min", 0)
            elif "duration_sec_each" in s:
                total += reps * (s["duration_sec_each"] + s.get("rest_sec", 0)) / 60
            elif "sec_per_km" in s:
                # A swim or erg piece carries its own pace: running's paces
                # would time a 100 m swim at 27 s.
                total += reps * (s["distance_m"] / 1000 * s["sec_per_km"] / 60)
                total += reps * s.get("rest_sec", 0) / 60
            else:
                zone = s.get("pace", "interval")
                pace_sec_km = p.get(zone, p.get("interval", 300))
                total += reps * (s["distance_m"] / 1000 * pace_sec_km / 60)
                total += reps * s.get("rest_sec", 0) / 60
        elif t == "fartlek":
            total += s.get("duration_min", 0)
    return max(1, int(total))


def _distance_from_steps(steps: list[dict], paces: dict | None) -> float:
    p = paces or _DEFAULT_RUN_PACES
    total = 0.0
    for s in steps:
        t = s.get("type")
        zone = s.get("pace") or s.get("intensity", "easy")
        pace_sec_km = p.get(zone) or p.get("easy", 360)
        if t in ("run", "warmup", "cooldown"):
            total += s.get("duration_min", 0) / (pace_sec_km / 1000) * 60
        elif t == "interval_set":
            total += s.get("reps", 0) * s.get("distance_m", 0)
    return total


# ─────────────────────────────────────────
# Title & description builders
# ─────────────────────────────────────────

_TYPE_LABEL = {
    "easy": "Easy", "easy_recovery": "Recovery Run", "endurance": "Endurance Ride",
    "aerobic": "Aerobic Swim", "long": "Long Session", "tempo": "Tempo",
    "intervals": "Intervals", "race_pace": "Race-Pace Work", "fartlek": "Fartlek",
    "short_quality": "Strides", "sweet_spot": "Sweet Spot",
    "easy_spin": "Recovery Spin", "quality": "Quality Session",
    "skills": "Skills Session", "threshold": "Threshold Intervals",
    "micro_bursts": "Micro-Bursts (30/15)", "over_unders": "Over-Unders",
    "matchbook": "Anaerobic Matchbook", "standing_starts": "Standing-Start Sprints",
    "descent_repeats": "Descent Repeats", "field_test": "Field Test",
    "vo2": "VO2max Intervals", "sustained_climb": "Sustained Climb",
    "tt_pace": "TT-Pace Block", "sprint": "Sprint Repeats", "anaerobic": "Anaerobic Capacity",
    "hill_sprints": "Hill Sprints",
    "brick_run": "Brick · Run off the bike",
    "technique": "Technique", "css": "CSS Threshold Set",
    "ut2": "UT2 Steady State", "ut1": "UT1 Steady State",
    "vert": "Vertical Hike", "incline_intervals": "Incline Intervals",
    "descent": "Descent Conditioning", "back_to_back": "Back-to-Back Day 1",
    "pole_hike": "Ski-Walking with Poles", "bounding": "Uphill Bounding",
    "eccentric": "Eccentric Leg Circuit", "plyometrics": "Plyometrics",
    "agility": "Agility & Balance", "ski_intervals": "Ski-Run Intervals",
    "arc": "ARC Endurance", "hangboard": "Finger Strength",
    "limit_bouldering": "Limit Bouldering", "power_endurance": "Power Endurance",
}

# The same slot is a different session in a different sport: "long" is a long
# ride on a bike and a volume day at the crag. Checked before _TYPE_LABEL.
_FAMILY_TYPE_LABEL: dict[tuple[str, str], str] = {
    ("swimming", "long"): "Long Swim", ("swimming", "vo2"): "VO2 Set",
    ("swimming", "race_pace"): "Race-Pace Set", ("swimming", "short_quality"): "Sharpening Set",
    ("rowing", "long"): "Long Row", ("rowing", "threshold"): "AT Pieces",
    ("rowing", "race_pace"): "2k-Pace Pieces", ("rowing", "sprint"): "AN Sprints",
    ("rowing", "short_quality"): "Race Sharpener", ("rowing", "easy"): "Easy Row",
    ("hiking", "long"): "Long Hike", ("hiking", "easy"): "Easy Hike",
    ("nordic_skiing", "endurance"): "Endurance Ski", ("nordic_skiing", "easy"): "Easy Ski",
    ("nordic_skiing", "long"): "Long Ski", ("nordic_skiing", "intervals"): "Uphill VO2 Intervals",
    ("nordic_skiing", "short_quality"): "Sprints & Race Pace",
    ("alpine_skiing", "aerobic"): "Aerobic Base", ("alpine_skiing", "easy"): "Aerobic Base",
    ("alpine_skiing", "long"): "Long Aerobic Day",
    ("climbing", "long"): "Volume Day", ("climbing", "easy"): "ARC Endurance",
    ("climbing", "short_quality"): "Short Power Session",
}

_FIELD_TEST_LABELS = {
    "ftp20": "FTP Test (20 min)",
    "pmax5": "5-Min Power Test",
    "rsa":   "RSA Sprint Test",
    "wprime1": "1-Min W' Test",
}


def _workout_title(workout_type: str, family: str, distance_m: float | None, duration_min: int,
                   imperial: bool = False) -> str:
    if workout_type.startswith("field_test:"):
        variant = workout_type.split(":", 1)[1]
        base = _FIELD_TEST_LABELS.get(variant, _TYPE_LABEL["field_test"])
    else:
        base = (_FAMILY_TYPE_LABEL.get((family, workout_type))
                or _TYPE_LABEL.get(workout_type, workout_type.replace("_", " ").title()))
    if family == "running" and distance_m and distance_m > 0:
        if imperial:
            mi = round(distance_m / 1609.344, 2)
            return f"{base} — {mi} mi"
        km = round(distance_m / 1000, 1)
        return f"{base} — {km} km"
    return f"{base} — {duration_min} min"


def _workout_description(steps: list[dict]) -> str:
    parts = []
    for s in steps:
        t = s.get("type")
        note = s.get("note", "")
        if t == "walk":
            parts.append(f"Walk: {s.get('duration_min')} min — {note}")
        elif t in ("run", "ride", "swim", "activity"):
            dur = s.get("duration_min")
            parts.append(f"{dur} min: {note}" if dur else note)
        elif t in ("warmup", "cooldown"):
            parts.append(f"{t.title()}: {s.get('duration_min')} min — {note}")
        elif t in ("interval_set", "effort_set"):
            parts.append(note)
        elif t == "fartlek":
            parts.append(note)
    return "\n".join(p for p in parts if p)
