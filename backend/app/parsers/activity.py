# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import fitdecode
from pathlib import Path
from app.parsers.base import BaseParser
from app.parsers.utils import get, get_by_iter, get_enhanced, as_str, as_int, parse_file_id
from app.parsers.exercise_names import resolve_exercise_name, decode_category
from app.parsers.smoother import smooth, recompute_summary
from app.calculators.activity_metrics import (
    _CYCLING_SPORTS,
    _RUNNING_SPORTS,
    compute_efficiency_factor,
    compute_aerobic_decoupling,
    compute_power_curve,
    compute_pace_curve,
)

_SEMICIRCLES_TO_DEGREES = 180.0 / (2 ** 31)


def _to_degrees(semicircles) -> float | None:
    if semicircles is None:
        return None
    return semicircles * _SEMICIRCLES_TO_DEGREES


# Sedentary to world-class. See ActivityParser._plausible_vo2max.
_VO2MAX_MIN = 15.0
_VO2MAX_MAX = 90.0


class ActivityParser(BaseParser):
    """Parses activity FIT files (runs, rides, swims, etc.)"""

    def can_parse(self, fit_path: Path) -> bool:
        try:
            with fitdecode.FitReader(fit_path) as fit:
                for frame in fit:
                    if isinstance(frame, fitdecode.FitDataMessage) and frame.name == "file_id":
                        return as_str(get(frame, "type")) == "activity"
        except Exception:
            pass
        return False

    @staticmethod
    def _plausible_vo2max(raw, divisor: float) -> float | None:
        """A candidate VO2max, or None if it cannot be one.

        ## Why the bound is not paranoia

        This parser spent its whole life reading field 10 of message 79 as
        VO2max. Message 79 is `user_metrics` and field 10 is `minimal_hr` — the
        day's lowest heart rate. It reported 46–48 for a user whose resting
        heart rate is 52, which is exactly what a minimum heart rate looks like
        and exactly what a mid-range VO2max looks like, so nothing ever
        appeared wrong. The tell was elsewhere: the figure was identical on
        rock climbing, hiking and cycling, and Garmin does not compute VO2max
        for two of those.

        A range check is what turns the next such mix-up into a missing
        reading instead of a plausible lie. 15–90 ml/kg/min spans the sedentary
        to the world-class; a heart rate in bpm lands inside it, which is why
        this alone would not have caught the original bug — but a raw
        fixed-point field, a percentage or a duration does not.
        """
        if raw is None:
            return None
        try:
            value = float(raw) / divisor
        except (TypeError, ValueError):
            return None
        return round(value, 1) if _VO2MAX_MIN <= value <= _VO2MAX_MAX else None

    def parse(self, fit_path: Path) -> dict:
        device_info = {}
        activity_data = {}
        data_points = []
        laps = []
        strength_sets = []
        climb_splits = []
        vo2max = None
        _plausible_vo2max = self._plausible_vo2max

        with fitdecode.FitReader(fit_path) as fit:
            for frame in fit:
                if not isinstance(frame, fitdecode.FitDataMessage):
                    continue

                if frame.name == "file_id":
                    device_info = parse_file_id(frame)

                elif frame.name == "session":
                    activity_data = self._parse_session(frame)

                elif frame.name == "record":
                    point = self._parse_record(frame)
                    if point:
                        data_points.append(point)

                elif frame.name == "lap":
                    lap = self._parse_lap(frame, len(laps) + 1)
                    if lap:
                        laps.append(lap)

                elif frame.name == "set":
                    s = self._parse_set(frame, len(strength_sets) + 1)
                    if s:
                        strength_sets.append(s)

                elif frame.name == "split":
                    cs = self._parse_climb_split(frame, len(climb_splits) + 1)
                    if cs:
                        climb_splits.append(cs)

                elif frame.name == "unknown_229":
                    # `max_met_data`: the per-sport VO2max the watch itself
                    # displays, at scale 10. The authoritative source, and the
                    # only one that is a VO2max rather than something a VO2max
                    # can be derived from.
                    vo2max = _plausible_vo2max(get_by_iter(frame, "unknown_2"), 10.0) or vo2max

                elif frame.name == "unknown_140":
                    # `physiological_metrics.met_max`, at scale 65536. One MET
                    # is 3.5 ml/kg/min by definition, which is the relation
                    # Firstbeat's own model uses — so this is the same number
                    # by a different unit, and it is present on files that
                    # carry no message 229.
                    met = get_by_iter(frame, "unknown_7")
                    if met:
                        vo2max = _plausible_vo2max(met, 65536.0 / 3.5) or vo2max

        if vo2max is not None:
            activity_data["vo2max_estimate"] = vo2max

        # Smooth the raw track points and recompute summary stats from cleaned data.
        if data_points:
            data_points = smooth(data_points)
            activity_data = recompute_summary(activity_data, data_points)

        sport = activity_data.get("sport")
        activity_data["efficiency_factor"] = compute_efficiency_factor(
            sport=sport,
            avg_hr=activity_data.get("avg_heart_rate"),
            normalized_power=activity_data.get("normalized_power"),
            avg_speed=activity_data.get("avg_speed"),
        )
        activity_data["aerobic_decoupling"] = compute_aerobic_decoupling(
            sport=sport,
            data_points=data_points,
        )

        sport_lower = (sport or "").lower()
        power_curve = compute_power_curve(data_points) if sport_lower in _CYCLING_SPORTS else {}
        pace_curve  = compute_pace_curve(data_points)  if sport_lower in _RUNNING_SPORTS  else {}

        return {
            "type":          "activity",
            "device":        device_info,
            "activity":      activity_data,
            "data_points":   data_points,
            "laps":          laps,
            "strength_sets": strength_sets,
            "climb_splits":  climb_splits,
            "power_curve":   power_curve,
            "pace_curve":    pace_curve,
        }

    def _parse_session(self, frame) -> dict:
        elapsed = get(frame, "total_elapsed_time")
        return {
            "name":                      get(frame, "sport_profile_name"),
            "sport":                     self._resolve_sport(frame),
            "sub_sport":                 self._resolve_sub_sport(frame),
            "started_at":                get(frame, "start_time"),
            "duration_seconds":          int(elapsed) if elapsed is not None else None,
            "distance_meters":           get(frame, "total_distance"),
            "avg_heart_rate":            get(frame, "avg_heart_rate"),
            "max_heart_rate":            get(frame, "max_heart_rate"),
            "total_calories":            get(frame, "total_calories"),
            "training_stress_score":     get(frame, "training_stress_score"),
            "intensity_factor":          get(frame, "intensity_factor"),
            "aerobic_training_effect":   get(frame, "total_training_effect"),
            "anaerobic_training_effect": get(frame, "total_anaerobic_training_effect"),
            "training_load_peak":        get(frame, "training_load_peak"),
            "avg_speed":                 get_enhanced(frame, "avg_speed"),
            "max_speed":                 get_enhanced(frame, "max_speed"),
            "avg_cadence":               get(frame, "avg_cadence"),
            "total_ascent":              get(frame, "total_ascent"),
            "total_descent":             get(frame, "total_descent"),
            "avg_power":                 get(frame, "avg_power"),
            "normalized_power":          get(frame, "normalized_power"),
            "total_grit":                get(frame, "total_grit"),
            "avg_flow":                  get(frame, "avg_flow"),
            # The two questions the watch asks when a workout is saved: "how
            # did you feel" (0/25/50/75/100, very weak to very strong) and
            # "how hard was it" (perceived effort ×10, so 70 is 7/10). Kept as
            # recorded; the scales are the display's business.
            "workout_feel":              get(frame, "workout_feel"),
            "workout_rpe":               get(frame, "workout_rpe"),
        }

    def _resolve_sport(self, frame) -> str | None:
        sport = as_str(get(frame, "sport"))
        # fitdecode returns the raw integer as a string when the sport enum value
        # isn't in its protocol tables. Fall back to sport_profile_name (e.g. "Safety").
        if sport is not None and sport.isdigit():
            profile = get(frame, "sport_profile_name")
            if profile:
                return profile.strip().lower()
        return sport

    def _resolve_sub_sport(self, frame) -> str | None:
        sub = as_str(get(frame, "sub_sport"))
        if sub is not None and sub.isdigit():
            return None  # unknown sub-sport — omit rather than expose a raw number
        return sub

    def _parse_lap(self, frame, lap_number: int) -> dict | None:
        elapsed = get(frame, "total_elapsed_time")
        if elapsed is None:
            return None

        # Golf-specific fields.  Garmin golf FIT files store per-hole stats
        # directly in the standard lap message using dedicated field names.
        # These are None for all non-golf activities.
        raw_strokes        = get(frame, "total_strokes")
        raw_putts          = get(frame, "total_putts")
        raw_stroke_dist    = get(frame, "avg_stroke_distance")
        raw_time_in_zone   = get(frame, "hole_time_in_zone")

        total_strokes       = as_int(raw_strokes)       if raw_strokes       is not None else None
        total_putts         = as_int(raw_putts)         if raw_putts         is not None else None
        avg_stroke_distance = float(raw_stroke_dist)    if raw_stroke_dist   is not None else None
        hole_time_in_zone   = float(raw_time_in_zone)   if raw_time_in_zone  is not None else None

        return {
            "lap_number":          lap_number,
            "start_time":          get(frame, "start_time"),
            "duration_seconds":    float(elapsed),
            "distance_meters":     get(frame, "total_distance"),
            "avg_heart_rate":      get(frame, "avg_heart_rate"),
            "max_heart_rate":      get(frame, "max_heart_rate"),
            "avg_speed":           get_enhanced(frame, "avg_speed"),
            "max_speed":           get_enhanced(frame, "max_speed"),
            "avg_cadence":         get(frame, "avg_cadence"),
            "total_ascent":        get(frame, "total_ascent"),
            "total_descent":       get(frame, "total_descent"),
            "avg_power":           get(frame, "avg_power"),
            "total_calories":      get(frame, "total_calories"),
            "total_grit":          get(frame, "total_grit"),
            "avg_flow":            get(frame, "avg_flow"),
            # Golf columns — None for non-golf sports
            "total_strokes":       total_strokes,
            "total_putts":         total_putts,
            "avg_stroke_distance": avg_stroke_distance,
            "hole_time_in_zone":   hole_time_in_zone,
        }

    def _parse_set(self, frame, set_number: int) -> dict | None:
        set_type = as_str(get(frame, "set_type"))
        if set_type is None:
            return None

        # category and category_subtype are array fields — take first element.
        raw_cat = get(frame, "category")
        raw_sub = get(frame, "category_subtype")
        category    = (raw_cat[0] if isinstance(raw_cat, (list, tuple)) else raw_cat)
        subtype     = (raw_sub[0] if isinstance(raw_sub, (list, tuple)) else raw_sub)
        # decode_category handles both fitdecode enum objects and raw ints, and
        # returns None for unknown/65534 sentinels.
        cat_str     = decode_category(category)
        subtype_int = int(subtype) if subtype is not None else None

        duration = get(frame, "duration")

        return {
            "set_number":        set_number,
            "set_type":          set_type,
            "exercise_category": cat_str,
            "exercise_name":     resolve_exercise_name(cat_str, subtype_int) if set_type == "active" else None,
            "weight_kg":         get(frame, "weight"),
            "repetitions":       as_int(get(frame, "repetitions")),
            "duration_seconds":  float(duration) if duration is not None else None,
            "start_time":        get(frame, "start_time"),
        }

    def _parse_climb_split(self, frame, split_number: int) -> dict | None:
        split_type = as_str(get(frame, "split_type"))
        if split_type not in ("climb_active", "climb_rest"):
            return None
        duration = get(frame, "total_elapsed_time")
        calories = get(frame, "total_calories")
        return {
            "split_number":     split_number,
            "split_type":       split_type,
            "start_time":       get(frame, "start_time"),
            "end_time":         get(frame, "end_time"),
            "duration_seconds": float(duration) if duration is not None else None,
            "total_ascent":     get(frame, "total_ascent"),
            "avg_vert_speed":   get(frame, "avg_vert_speed"),
            "total_calories":   int(calories) if calories is not None else None,
            # fitdecode doesn't name these per-split fields; read by iteration
            "min_heart_rate":   get_by_iter(frame, "unknown_15"),
            "max_heart_rate":   get_by_iter(frame, "unknown_16"),
            "difficulty_score": get_by_iter(frame, "unknown_80"),
            # Grade and result only present on climb_active splits
            # unknown_70 = V-grade integer (0=V0, 1=V1, …)
            # unknown_71 = climb result (3=send/topped, 2=attempt/fell)
            "grade_level":      get_by_iter(frame, "unknown_70"),
            "climb_result":     get_by_iter(frame, "unknown_71"),
        }

    def _parse_record(self, frame) -> dict | None:
        timestamp = get(frame, "timestamp")
        if timestamp is None:
            return None

        return {
            "recorded_at": timestamp,
            "lat":         _to_degrees(get(frame, "position_lat")),
            "lng":         _to_degrees(get(frame, "position_long")),
            "altitude":    get_enhanced(frame, "altitude"),
            "heart_rate":  get(frame, "heart_rate"),
            "power":       get(frame, "power"),
            "cadence":     get(frame, "cadence"),
            "speed":       get_enhanced(frame, "speed"),
            "grit":        get(frame, "grit"),
            "flow":        get(frame, "flow"),
        }
