# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
FIT workout / schedule file encoder.

Generates binary `.fit` files that match Garmin Connect's own emitted workout
files byte-for-message on the watch app side, so the Fenix 6X (and similar
hardware) lists, schedules, and runs them with the same affordances as a
Connect-pushed workout — including step-by-step structure, target ranges, and
animation playback for strength + yoga.

Reference set: 26 Garmin-Connect-emitted workouts decoded in audit, covering:
running, cycling, strength, HIIT, cardio, yoga, pilates, mobility.

──────────────────────────────────────────────────────────────────────────
Architecture
──────────────────────────────────────────────────────────────────────────

  ┌─ Public API ─────────────────────────────────────────────────────────┐
  │  generate_workout_fit(...)          endurance  (run / cycle / MTB)   │
  │  generate_strength_workout_fit(...) strength / flexibility / yoga    │
  │  generate_schedule_fit(...)         Schedule.fit (training calendar) │
  └──────────────────────────────────────────────────────────────────────┘

  ┌─ Workout-type registry ──────────────────────────────────────────────┐
  │  WORKOUT_TYPE_ROUTING maps a high-level "workout_type" string to     │
  │  (sport, sub_sport). Adding a new type (hiit, pilates, …) is a       │
  │  one-line entry — no encoder changes needed.                         │
  └──────────────────────────────────────────────────────────────────────┘

  ┌─ Step emitters ──────────────────────────────────────────────────────┐
  │  Each plan-step schema in the app has its own emitter that converts  │
  │  a single plan_step dict to one-or-more FIT workout_step messages:   │
  │    _emit_endurance_steps  — run/cycle/MTB blocks with pace/HR/power  │
  │    _emit_strength_steps   — rep-based + timed-rest + repeat loop     │
  │    _emit_yoga_steps       — timed pose holds, one loop per stretch   │
  │  Each returns (steps, exercise_titles) tuples so the caller can      │
  │  concatenate freely.                                                 │
  └──────────────────────────────────────────────────────────────────────┘

  ┌─ FIT library choice ─────────────────────────────────────────────────┐
  │  Uses Garmin's official `garmin_fit_sdk` (Profile 21.202) for ALL    │
  │  encoding. The previous mixed approach (fit-tool for endurance,      │
  │  SDK for strength) is consolidated here — the SDK ships full         │
  │  enum support for `exercise_category`, `exercise_name`,              │
  │  `memo_glob`, and `exercise_title` which are needed for the          │
  │  animation lookup.                                                   │
  └──────────────────────────────────────────────────────────────────────┘

──────────────────────────────────────────────────────────────────────────
Garmin Connect identity profile (reverse-engineered from references)
──────────────────────────────────────────────────────────────────────────

Every Connect-emitted workout file has these invariants — diverging from any
of them causes the Fenix UI's calendar / today's-workout widget to silently
filter the file out:

  file_id      manufacturer=GARMIN (1), product=65534 (connect sentinel),
               type=WORKOUT (5), serial_number=non-zero, time_created=now
  file_creator software_version=2609 (older) or 2610 (newer); both work
  workout      capabilities=32 (TCX), sub_sport set (≠ generic for fitness
               apps), wkt_name, num_valid_steps
  workout_step weight_display_unit=2 (POUND) on EVERY step (Connect always
               emits this, even for non-strength workouts);
               secondary_target_value=0 on every step.

──────────────────────────────────────────────────────────────────────────
Sport-routing decisions
──────────────────────────────────────────────────────────────────────────

  Flexibility / mobility → sport=training (10), sub_sport=yoga (43)
    Confirmed on the user's Fenix 6X: only yoga-routed workouts play
    stretch animations; the Training app's flexibility_training sub_sport
    accepts the file but shows no animation pack on this firmware.

  Strength → sport=training (10), sub_sport=strength_training (20)
  Running  → sport=running (1)
  Cycling, road, gravel → sport=cycling (2)
  MTB, trail → sport=cycling (2), sub_sport=mountain (8)
"""
from __future__ import annotations

import logging
import struct
import time
from datetime import date, datetime, timezone
from typing import Iterable

from garmin_fit_sdk import Encoder, Profile

log = logging.getLogger(__name__)


# ── Profile extension for Garmin Connect's "modern" workout-msg fields ───────
#
# Garmin Connect's newer yoga-routed workouts (sw_version=2610) carry five
# numeric extra fields on the workout message that the bundled garmin_fit_sdk
# Profile doesn't know about:
#
#   field 9   uint32  always 0 in observed yoga files
#   field 10  uint32  total workout time in milliseconds
#   field 16  uint8[16] zone array, always [0]*16
#   field 21  uint32  total workout time in milliseconds (duplicate of 10)
#   field 23  uint32  always 0 in observed yoga files
#
# Without these fields the file decodes fine, but empirically the Fenix Yoga
# app's pose-animation engine doesn't fire — every Fenix-6X-confirmed-
# animating yoga workout in the audit set has them. We extend the SDK's
# Profile dict at import time so Encoder.write_mesg accepts these by name
# (the SDK silently drops fields it doesn't have a definition for).
def _extend_workout_msg_profile() -> None:
    """Adds fields 9/10/16/21/23 to the workout message definition in the
    garmin_fit_sdk Profile dict. Idempotent — safe to import multiple times."""
    _EXTRA = [
        (9,  "_unknown_9",          "uint32", "false"),
        (10, "_total_workout_time", "uint32", "false"),
        (16, "_zone_array",         "uint8",  "true"),
        (21, "_total_workout_time_b","uint32", "false"),
        (23, "_unknown_23",         "uint32", "false"),
    ]
    wkt_msg = Profile["messages"].get(26)
    if not wkt_msg:
        return
    fields = wkt_msg.setdefault("fields", {})
    for num, name, base, array in _EXTRA:
        if num in fields:
            continue
        fields[num] = {
            "num": num, "name": name, "type": base,
            "base_type": base, "array": array,
            "scale": [1], "offset": [0], "units": "",
            "bits": [], "components": [],
            "is_accumulated": False, "has_components": False,
            "sub_fields": [],
        }


_extend_workout_msg_profile()


# ── Garmin Connect identity constants ────────────────────────────────────────

_MFG_GARMIN          = 1       # Manufacturer.GARMIN
_PRODUCT_CONNECT     = 65534   # Garmin's "connect" product enum sentinel

# Schedule.fit identity, copied from a decoded Garmin Connect upload. Both are
# fixed constants in Connect's output rather than anything about the watch or
# the user, and the file the watch acted on carried exactly these.
_SCHEDULE_SERIAL           = 1
_SCHEDULE_SOFTWARE_VERSION = 26
_MANUFACTURER_GARMIN       = 1
_FILE_TYPE_SCHEDULES       = 7
_SCHEDULE_TYPE_WORKOUT     = 0
# message_index of the training plan in message 137, and the value each
# schedule entry carries in field 7 to point back at it. Connect used 1 for
# both; whether field 7 really is that link is unproven, but they matched.
_PLAN_MESSAGE_INDEX        = 1
_CAPS_TCX            = 32      # WorkoutCapabilities.TCX — firmware leniency flag
_FILE_CREATOR_SW_VER = 2609    # Matches sw shipped on the working reference set.

_FIT_EPOCH_OFFSET    = 631_065_600  # seconds 1970-01-01 → 1989-12-31

# NB: file_id.type (workout=5, schedules=7) and schedule.type (workout=1) are
# passed to the encoder as the string enum names ("workout", "schedules"),
# which garmin_fit_sdk resolves to these ints — so no int constants are needed.


# ── Sport routing registry ───────────────────────────────────────────────────
#
# Maps a high-level "workout_type" or sport string to the (sport, sub_sport)
# pair the watch needs to surface the workout under the right app and load the
# matching animation pack. Extending this table is the single point of change
# for adding a new workout flavour (HIIT, Pilates, Cardio, …).
#
# Strings (not ints) are used here because garmin_fit_sdk Encoder accepts the
# enum name and converts on serialization, which keeps this table readable.

WORKOUT_TYPE_ROUTING: dict[str, tuple[str, str]] = {
    # Strength + bodyweight conditioning
    "strength":         ("training", "strength_training"),
    # Flexibility / mobility — yoga sub_sport is the ONLY route that plays
    # stretch animations on Fenix 6X firmware (confirmed empirically).
    "mobility":         ("training", "yoga"),
    "flexibility":      ("training", "yoga"),
    "yoga":             ("training", "yoga"),
    "pilates":          ("training", "pilates"),
    # Cardio / HIIT (treadmill-style intervals)
    "hiit":             ("training", "hiit"),
    "cardio":           ("training", "cardio_training"),
}

# Sport routing for endurance workouts (used by generate_workout_fit). The
# `sport` arg there is the user's input sport, mapped to FIT's enum names.
_ENDURANCE_SPORT_ROUTING: dict[str, tuple[str, str]] = {
    "running":          ("running",  "generic"),
    "cycling":          ("cycling",  "generic"),
    "road_biking":      ("cycling",  "road"),
    "gravel_cycling":   ("cycling",  "gravel_cycling"),
    "mountain_biking":  ("cycling",  "mountain"),
    "trail_biking":     ("cycling",  "mountain"),
    "swimming":         ("swimming", "lap_swimming"),
    "lap_swimming":     ("swimming", "lap_swimming"),
    "pool_swimming":    ("swimming", "lap_swimming"),
    "open_water_swimming": ("swimming", "open_water"),
    "hiking":           ("hiking",   "generic"),
    "walking":          ("walking",  "generic"),
    # The rowing plan is erg-first, so a rowing goal's workouts open the
    # Indoor Row app; indoor_rowing is how Garmin files an erg session.
    "rowing":           ("rowing",   "indoor_rowing"),
    "indoor_rowing":    ("rowing",   "indoor_rowing"),
    "skiing":           ("cross_country_skiing", "generic"),
    "cross_country_skiing": ("cross_country_skiing", "generic"),
    "nordic_skiing":    ("cross_country_skiing", "generic"),
    "classic_skiing":   ("cross_country_skiing", "generic"),
    "roller_skiing":    ("cross_country_skiing", "generic"),
    "skate_skiing":     ("cross_country_skiing", "skate_skiing"),
    # Garmin records ski touring as alpine skiing / backcountry.
    "backcountry_skiing": ("alpine_skiing", "backcountry"),
    "ski_mountaineering": ("alpine_skiing", "backcountry"),
    "skimo":            ("alpine_skiing", "backcountry"),
    # An alpine plan is dry-land conditioning (plan/skiing.py): its sessions
    # are circuits and aerobic work done before the season, so they go to the
    # Cardio app rather than opening the ski app for a box-jump session.
    "alpine_skiing":    ("training", "cardio_training"),
    "downhill_skiing":  ("training", "cardio_training"),
    "resort_skiing":    ("training", "cardio_training"),
    "snowboarding":     ("training", "cardio_training"),
    "climbing":         ("rock_climbing", "indoor_climbing"),
    "rock_climbing":    ("rock_climbing", "indoor_climbing"),
    "indoor_climbing":  ("rock_climbing", "indoor_climbing"),
    "sport_climbing":   ("rock_climbing", "indoor_climbing"),
    "bouldering":       ("rock_climbing", "bouldering"),
}


_FIT_FAMILY: dict[str, str] = {
    "mountain_biking": "mountain_biking", "trail_biking": "mountain_biking",
    "running": "running",
    "cycling": "cycling", "road_biking": "cycling", "gravel_cycling": "cycling",
    "swimming": "swimming", "lap_swimming": "swimming", "pool_swimming": "swimming",
    "open_water_swimming": "swimming",
    "rowing": "rowing", "indoor_rowing": "rowing",
    "hiking": "hiking", "walking": "hiking",
    "skiing": "skiing", "cross_country_skiing": "skiing", "nordic_skiing": "skiing",
    "classic_skiing": "skiing", "roller_skiing": "skiing", "skate_skiing": "skiing",
    "backcountry_skiing": "skiing", "ski_mountaineering": "skiing", "skimo": "skiing",
    "alpine_skiing": "skiing", "downhill_skiing": "skiing", "resort_skiing": "skiing",
    "snowboarding": "skiing",
}


def _sport_family(sport_key: str) -> str:
    return _FIT_FAMILY.get(sport_key, "generic")


# ── HR / power zone tables (MTB + cycling intensity tags) ────────────────────
#
# Mapped from intensity tag → (% LTHR or % FTP) range. None means "no target of
# this kind for this intensity" — the encoder falls back to the next-best
# target type (power → HR → speed → open).

_MTB_HR_ZONE_PCT: dict[str, tuple[float, float] | None] = {
    "recovery":        (0.50, 0.81),
    "easy":            (0.65, 0.81),
    "endurance":       (0.81, 0.88),
    "tempo":           (0.89, 0.93),
    "sweet_spot":      (0.94, 0.99),
    "threshold":       (1.00, 1.02),
    "vo2":             (1.03, 1.06),
    "race_pace":       (0.92, 1.04),
    "skills":          (0.65, 0.85),
    "descent_repeats": (0.85, 1.00),
    "over_under":      None,
    "matchbook":       None,
    "micro_bursts":    None,
    "anaerobic":       None,
    "neuromuscular":   None,
    "test":            None,
}

_MTB_PWR_ZONE_PCT: dict[str, tuple[float, float] | None] = {
    "recovery":        (0.40, 0.55),
    "easy":            (0.50, 0.65),
    "endurance":       (0.65, 0.75),
    "tempo":           (0.76, 0.88),
    "sweet_spot":      (0.88, 0.93),
    "threshold":       (0.95, 1.00),
    "vo2":             (1.05, 1.20),
    "over_under":      (0.88, 1.08),
    "matchbook":       (1.20, 1.50),
    "anaerobic":       (1.20, 1.50),
    "neuromuscular":   (1.50, 3.00),
    "micro_bursts":    (1.05, 1.30),
    "race_pace":       (0.85, 1.00),
    "descent_repeats": None,
    "skills":          None,
    "test":            None,
}

# Heart-rate bands (fraction of LTHR) for the sports steered by feel and heart
# rate: rowing's aerobic bands, hiking, skiing. Short hard efforts (vo2,
# race_pace, sprint) are deliberately absent: over 1-5 min heart rate is still
# climbing when the rep ends, so a target would alarm "too low" through the
# part of the rep that matters. Those steps run on time, the note says how hard.
_ENDURANCE_HR_PCT: dict[str, tuple[float, float]] = {
    "easy":      (0.65, 0.81),
    "endurance": (0.75, 0.85),
    "ut2":       (0.75, 0.85),
    "ut1":       (0.85, 0.90),
    "threshold": (0.94, 1.00),
}

# Swim stroke and equipment enums (FIT swim_stroke, workout_equipment), which
# a pool workout carries on each step so the watch shows "Drill · Kickboard"
# rather than a bare distance.
_SWIM_STROKE: dict[str, int] = {
    "freestyle": 0, "backstroke": 1, "breaststroke": 2, "butterfly": 3,
    "drill": 4, "mixed": 5, "im": 6,
}
_SWIM_EQUIPMENT: dict[str, int] = {
    "swim_fins": 1, "swim_kickboard": 2, "swim_paddles": 3, "swim_pull_buoy": 4, "swim_snorkel": 5,
}

_PACE_STEP_NAMES: dict[str, str] = {
    "recovery":   "Recovery",
    "easy":       "Easy Run",
    "marathon":   "Marathon",
    "threshold":  "Threshold",
    "interval":   "Interval",
    "repetition": "Rep Pace",
}


def _mtb_targets(intensity: str, lthr: int | None, ftp: int | None
                 ) -> tuple[int, int, int, int]:
    """Resolve HR (bpm) and power (watts) absolute ranges for an MTB/cycling
    intensity tag. Returns (lo_hr, hi_hr, lo_w, hi_w); zero for unavailable."""
    lo_hr = hi_hr = lo_w = hi_w = 0
    hr_pct = _MTB_HR_ZONE_PCT.get(intensity)
    if hr_pct is not None and lthr and lthr > 0:
        lo_hr = int(lthr * hr_pct[0])
        hi_hr = int(lthr * hr_pct[1])
    pwr_pct = _MTB_PWR_ZONE_PCT.get(intensity)
    if pwr_pct is not None and ftp and ftp > 0:
        lo_w = int(ftp * pwr_pct[0])
        hi_w = int(ftp * pwr_pct[1])
    return lo_hr, hi_hr, lo_w, hi_w


def _pace_targets_mms(zone: str, paces: dict[str, float],
                      margin: int = 10) -> tuple[int, int]:
    """Return (lo, hi) speed bounds in mm/s for a ±margin sec/km window around
    the given VDOT pace zone. (0, 0) if zone unknown."""
    p = paces.get(zone)
    if p is None:
        return 0, 0
    lo = int(1_000_000 / (p + margin))
    hi = int(1_000_000 / max(1.0, p - margin))
    return lo, hi


# ── Exercise category lookup (snake_case → FIT enum int) ─────────────────────
#
# Used by step emitters to resolve the (category_int, exercise_name_int) pair
# the watch needs for animation lookup. Stays an explicit table for clarity —
# garmin_fit_sdk accepts either ints or the string enum names, but our DB
# stores the snake_case strings.

_CATEGORY_INT: dict[str, int] = {
    "bench_press": 0, "calf_raise": 1, "cardio": 2, "carry": 3, "chop": 4,
    "core": 5, "crunch": 6, "curl": 7, "deadlift": 8, "flye": 9,
    "hip_raise": 10, "hip_stability": 11, "hip_swing": 12,
    "hyperextension": 13, "lateral_raise": 14, "leg_curl": 15,
    "leg_raise": 16, "lunge": 17, "olympic_lift": 18, "plank": 19,
    "plyo": 20, "pull_up": 21, "push_up": 22, "row": 23,
    "shoulder_press": 24, "shoulder_stability": 25, "shrug": 26,
    "sit_up": 27, "squat": 28, "total_body": 29, "triceps_extension": 30,
    "warm_up": 31, "run": 32, "bike": 33, "cardio_sensors": 34,
    "move": 35, "pose": 36, "banded_exercises": 37, "battle_rope": 38,
    "elliptical": 39, "floor_climb": 40, "indoor_bike": 41, "indoor_row": 42,
    "ladder": 43, "sandbag": 44, "sled": 45, "sledge_hammer": 46,
    "stair_stepper": 47, "suspension": 49, "tire": 50,
}
_UNKNOWN_CATEGORY = 65534
_UNKNOWN_EXERCISE = 65535

# weight_display_unit (FIT fit_base_unit enum): 0=other, 1=kg, 2=pound.
# Garmin Connect emits 2 (POUND) on EVERY workout_step regardless of locale,
# even for non-strength workouts — we follow that convention to stay
# byte-compatible with Connect's identity profile.
_WEIGHT_UNIT_DEFAULT = 2


# ── Time helpers ─────────────────────────────────────────────────────────────

def _now_ms() -> int:
    return round(time.time() * 1000)


def _ms_to_datetime(ms: int | None) -> datetime:
    if ms is None:
        return datetime.now(timezone.utc)
    return datetime.fromtimestamp(ms / 1000.0, tz=timezone.utc)


def _date_to_fit_seconds_noon_utc(d: date) -> int:
    """Convert a date to FIT-epoch seconds at NOON UTC, for use as the value
    of schedule_msg.scheduled_time.

    The watch interprets this raw value as a local_date_time, so noon-UTC →
    noon local-wall-clock everywhere. Garmin Connect emits noon (not midnight)
    because midnight UTC can fall into the previous day's window in negative-
    UTC timezones, causing the "today's workout" widget to miss the entry."""
    dt = datetime(d.year, d.month, d.day, 12, 0, 0, tzinfo=timezone.utc)
    return max(0, int(dt.timestamp()) - _FIT_EPOCH_OFFSET)


def _ms_for(minutes: float = 0, seconds: float = 0) -> int:
    return int(minutes * 60_000 + seconds * 1000)


def _fit_str(value: str, max_bytes: int) -> str:
    """Truncate a string to fit inside a FIT string field.

    FIT string fields cap at 255 bytes on the wire, and garmin_fit_sdk
    enforces it strictly: a field over the limit raises
    `ValueError: Some field sizes are greater than 255` and aborts encoding
    the *entire file* — not just the long field. Slicing a Python `str` by
    character count is not a safe guard against that: `description[:250]`
    still handed the encoder well over 255 raw bytes once a generated
    blurb's em-dashes and curly punctuation (3 bytes each in UTF-8) were
    counted, and because one call encodes one workout, that one long
    description took the whole upload-list endpoint down — every other
    pending workout failed alongside it, with no FIT malformed at all.

    `errors="ignore"` on the decode drops a multi-byte sequence left
    dangling by the byte-level cut, rather than raising or emitting mojibake.
    """
    return value.encode("utf-8")[:max_bytes].decode("utf-8", errors="ignore")


# ── Step builder ─────────────────────────────────────────────────────────────
#
# Returns a workout_step dict ready for `Encoder.write_mesg`. Builds the FIT
# message as Garmin Connect does:
#   - Target priority: power > HR > speed > zone-based HR/power > open
#   - HR custom range encoding: bpm + 100 (FIT convention; 1-100 is % max_hr)
#   - Power custom range encoding: watts + 1000 (1-1000 is % FTP)
#   - Speed encoding: mm/s direct
#   - Every step carries weight_display_unit + secondary_target_value=0 per
#     Connect's emitted identity profile

def _build_step(idx: int, *,
                name: str | None,
                intensity: str,
                duration_type: str,
                duration_value: int,
                lo_speed_mms: int = 0, hi_speed_mms: int = 0,
                lo_hr_bpm: int = 0,    hi_hr_bpm: int = 0,
                lo_power_w: int = 0,   hi_power_w: int = 0,
                lo_cadence: int = 0,   hi_cadence: int = 0,
                swim_stroke: int | None = None,
                equipment: int | None = None,
                exercise_category: int | None = None,
                exercise_name: int | None = None,
                exercise_weight_kg: float | None = None
                ) -> dict:
    s: dict = {
        "mesg_num":              27,                    # workout_step
        "message_index":         idx,
        "intensity":             intensity,
        "duration_type":         duration_type,
        "duration_value":        int(duration_value),
        "secondary_target_value": 0,
        "weight_display_unit":   _WEIGHT_UNIT_DEFAULT,
    }
    if name:
        s["wkt_step_name"] = _fit_str(name, 50)

    if lo_power_w > 0 and hi_power_w > 0:
        s["target_type"]              = "power"
        s["target_value"]             = 0
        s["custom_target_value_low"]  = int(lo_power_w) + 1000
        s["custom_target_value_high"] = int(hi_power_w) + 1000
    elif lo_hr_bpm > 0 and hi_hr_bpm > 0:
        s["target_type"]              = "heart_rate"
        s["target_value"]             = 0
        s["custom_target_value_low"]  = int(lo_hr_bpm) + 100
        s["custom_target_value_high"] = int(hi_hr_bpm) + 100
    elif lo_speed_mms > 0 and hi_speed_mms > 0:
        s["target_type"]              = "speed"
        s["target_value"]             = 0
        s["custom_target_value_low"]  = int(lo_speed_mms)
        s["custom_target_value_high"] = int(hi_speed_mms)
    elif lo_cadence > 0 and hi_cadence > 0:
        # Stroke rate on the erg: FIT cadence is strokes per minute there,
        # carried as-is (no offset, unlike HR and power).
        s["target_type"]              = "cadence"
        s["target_value"]             = 0
        s["custom_target_value_low"]  = int(lo_cadence)
        s["custom_target_value_high"] = int(hi_cadence)
    elif swim_stroke is not None:
        s["target_type"]   = "swim_stroke"
        s["target_value"]  = int(swim_stroke)
    else:
        s["target_type"]   = "open"
        s["target_value"]  = 0

    if exercise_category is not None:
        s["exercise_category"] = int(exercise_category)
    if exercise_name is not None:
        s["exercise_name"] = int(exercise_name)
    if exercise_weight_kg is not None and exercise_weight_kg > 0:
        s["exercise_weight"] = float(exercise_weight_kg)
    if equipment is not None:
        s["equipment"] = int(equipment)
    return s


def _build_repeat_step(idx: int, *, from_idx: int, reps: int) -> dict:
    """Loop back to `from_idx`, run `reps` times total."""
    return {
        "mesg_num":              27,
        "message_index":         idx,
        "intensity":             "active",
        "duration_type":         "repeat_until_steps_cmplt",
        "duration_value":        int(from_idx),
        "target_type":           "open",
        "target_value":          int(reps),
        "secondary_target_value": 0,
    }


# ── Endurance step emitter (run / cycle / MTB) ───────────────────────────────

def _resolve_endurance_targets(ps: dict, *,
                               family: str,
                               paces: dict[str, float] | None,
                               lthr: int | None,
                               ftp: int | None,
                               pace_coaching: bool,
                               fallback_intensity: str = ""
                               ) -> tuple[int, int, int, int, int, int]:
    """Pick the right target type for a step based on its zone + family.

    Returns (lo_speed_mms, hi_speed_mms, lo_hr_bpm, hi_hr_bpm,
             lo_power_w, hi_power_w). Caller passes these into _build_step,
    which selects priority power > HR > speed > open."""
    if not pace_coaching:
        return 0, 0, 0, 0, 0, 0

    zone = ps.get("pace") or ""
    intensity = ps.get("intensity") or fallback_intensity

    if family == "running" and paces and zone:
        lo, hi = _pace_targets_mms(zone, paces)
        return lo, hi, 0, 0, 0, 0
    if family in ("mountain_biking", "cycling") and intensity:
        lo_hr, hi_hr, lo_w, hi_w = _mtb_targets(intensity, lthr, ftp)
        return 0, 0, lo_hr, hi_hr, lo_w, hi_w
    if family in ("rowing", "hiking", "skiing") and intensity and lthr and lthr > 0:
        # A rowing piece above UT1 steers by stroke rate alone (see
        # plan/rowing.py): heart rate there would override the rate target.
        if family == "rowing" and ps.get("spm_low") and intensity not in ("ut2", "ut1", "easy"):
            return 0, 0, 0, 0, 0, 0
        pct = _ENDURANCE_HR_PCT.get(intensity)
        if pct is not None:
            return 0, 0, int(lthr * pct[0]), int(lthr * pct[1]), 0, 0
    return 0, 0, 0, 0, 0, 0


def _step_extras(ps: dict, *, family: str, pace_coaching: bool) -> dict:
    """The per-step fields beyond the primary target: stroke rate (a
    coaching target, so only with coaching on) and the swim stroke and
    equipment (structure, not coaching — a kick set is a kick set either way).
    """
    kw: dict = {}
    if pace_coaching and family == "rowing" and ps.get("spm_low") and ps.get("spm_high"):
        kw["lo_cadence"] = int(ps["spm_low"])
        kw["hi_cadence"] = int(ps["spm_high"])
    if family == "swimming":
        stroke = _SWIM_STROKE.get(ps.get("stroke") or "")
        if stroke is not None:
            kw["swim_stroke"] = stroke
        equipment = _SWIM_EQUIPMENT.get(ps.get("equipment") or "")
        if equipment is not None:
            kw["equipment"] = equipment
    return kw


def _emit_endurance_steps(plan_steps: list[dict], *,
                          family: str,
                          paces: dict[str, float] | None,
                          lthr: int | None,
                          ftp: int | None,
                          pace_coaching: bool
                          ) -> list[dict]:
    """Convert endurance plan steps into a sequence of workout_step dicts.

    Recognised plan-step types:
      warmup         — TIME warmup w/ optional pace target
      cooldown       — TIME cooldown w/ optional pace target
      walk           — warm-up / rest / cool-down (positional)
      run            — single timed run block at a pace zone
      fartlek        — alternating hard/easy intervals (looped)
      interval_set   — N×(distance|time)-based intervals w/ rest
      effort_set     — N×timed effort blocks (MTB/cycling) w/ rest
      ride/swim/activity — single timed block
    """
    walk_pos   = [i for i, s in enumerate(plan_steps) if s.get("type") == "walk"]
    first_walk = walk_pos[0]  if walk_pos else -1
    last_walk  = walk_pos[-1] if walk_pos else -1

    out: list[dict] = []
    idx = 0

    def emit(name, intensity, duration_type, duration_value, **kw):
        nonlocal idx
        out.append(_build_step(idx, name=name, intensity=intensity,
                                duration_type=duration_type,
                                duration_value=duration_value, **kw))
        idx += 1

    for pi, ps in enumerate(plan_steps):
        stype = ps.get("type", "")
        targets = _resolve_endurance_targets(ps,
                                              family=family,
                                              paces=paces,
                                              lthr=lthr, ftp=ftp,
                                              pace_coaching=pace_coaching)
        lo_s, hi_s, lo_h, hi_h, lo_w, hi_w = targets
        extras = _step_extras(ps, family=family, pace_coaching=pace_coaching)
        label = ps.get("label") or None

        if stype == "walk":
            dur_min = ps.get("duration_min", 5)
            if pi == first_walk:
                emit("Warm Up", "warmup", "time", _ms_for(dur_min))
            elif pi == last_walk:
                emit("Cool Down", "cooldown", "time", _ms_for(dur_min))
            else:
                emit("Rest", "rest", "time", _ms_for(dur_min))

        elif stype == "run":
            zone = ps.get("pace", "")
            name = _PACE_STEP_NAMES.get(zone, "Run")
            emit(name, "active", "time", _ms_for(ps.get("duration_min", 30)),
                 lo_speed_mms=lo_s, hi_speed_mms=hi_s)

        elif stype == "fartlek":
            hard_min  = ps.get("hard_min", 3)
            easy_min  = ps.get("easy_min", 2)
            reps      = max(1, int(ps.get("reps", 1)))
            hard_zone = ps.get("pace", "threshold")
            h_lo, h_hi = _pace_targets_mms(hard_zone, paces or {}) if pace_coaching and paces else (0, 0)
            e_lo, e_hi = _pace_targets_mms("easy",     paces or {}) if pace_coaching and paces else (0, 0)
            loop_start = idx
            emit("Hard", "active", "time", _ms_for(hard_min),
                 lo_speed_mms=h_lo, hi_speed_mms=h_hi)
            emit("Easy", "active", "time", _ms_for(easy_min),
                 lo_speed_mms=e_lo, hi_speed_mms=e_hi)
            if reps > 1:
                out.append(_build_repeat_step(idx, from_idx=loop_start, reps=reps))
                idx += 1

        elif stype in ("warmup", "cooldown"):
            name, default = ("Warm Up", 15) if stype == "warmup" else ("Cool Down", 10)
            if ps.get("distance_m"):
                # A pool warm-up is a distance, as the watch counts lengths.
                duration = ("distance", int(ps["distance_m"]) * 100)
            else:
                duration = ("time", _ms_for(ps.get("duration_min", default)))
            emit(name, stype, duration[0], duration[1],
                 lo_speed_mms=lo_s, hi_speed_mms=hi_s,
                 lo_hr_bpm=lo_h, hi_hr_bpm=hi_h,
                 lo_power_w=lo_w, hi_power_w=hi_w, **extras)

        elif stype == "interval_set":
            reps         = max(1, int(ps.get("reps", 1)))
            dist_m       = ps.get("distance_m")
            dur_min_each = ps.get("duration_min_each")
            dur_sec_each = ps.get("duration_sec_each")
            _rs          = ps.get("rest_sec")
            rest_s       = _rs if _rs is not None else int((ps.get("rest_min") or 0) * 60)

            loop_start = idx
            iname = label or "Interval"
            if dist_m:
                # FIT distance is centimeters
                emit(iname, "active", "distance", int(dist_m) * 100,
                     lo_speed_mms=lo_s, hi_speed_mms=hi_s, **extras)
            elif dur_min_each:
                emit(iname, "active", "time", _ms_for(int(dur_min_each)),
                     lo_speed_mms=lo_s, hi_speed_mms=hi_s, **extras)
            elif dur_sec_each:
                emit(iname, "active", "time", _ms_for(seconds=int(dur_sec_each)),
                     lo_speed_mms=lo_s, hi_speed_mms=hi_s, **extras)
            else:
                emit(iname, "active", "time", _ms_for(5),
                     lo_speed_mms=lo_s, hi_speed_mms=hi_s, **extras)

            if reps > 1:
                if rest_s > 0:
                    emit("Rest", "rest", "time", _ms_for(seconds=rest_s))
                out.append(_build_repeat_step(idx, from_idx=loop_start, reps=reps))
                idx += 1

        elif stype == "effort_set":
            reps         = max(1, int(ps.get("reps", 1)))
            dur_min_each = ps.get("duration_min_each")
            dur_sec_each = ps.get("duration_sec_each")
            rest_s       = int((ps.get("rest_min") or 0) * 60) or (ps.get("rest_sec") or 0)
            intensity_str = ps.get("intensity", "")
            if not label:
                label = intensity_str.replace("_", " ").title() if intensity_str else "Effort"

            if dur_min_each:
                dur_ms = _ms_for(int(dur_min_each))
            elif dur_sec_each:
                dur_ms = _ms_for(seconds=int(dur_sec_each))
            else:
                dur_ms = _ms_for(5)

            loop_start = idx
            emit(label, "active", "time", dur_ms,
                 lo_hr_bpm=lo_h, hi_hr_bpm=hi_h,
                 lo_power_w=lo_w, hi_power_w=hi_w, **extras)

            if reps > 1:
                if rest_s > 0:
                    emit("Rest", "rest", "time", _ms_for(seconds=rest_s))
                out.append(_build_repeat_step(idx, from_idx=loop_start, reps=reps))
                idx += 1

        elif stype in ("ride", "swim", "activity"):
            dur_min       = ps.get("duration_min", 30)
            intensity_str = ps.get("intensity", "")
            name = label or (intensity_str.replace("_", " ").title() if intensity_str else stype.title())
            emit(name, "active", "time", _ms_for(dur_min),
                 lo_hr_bpm=lo_h, hi_hr_bpm=hi_h,
                 lo_power_w=lo_w, hi_power_w=hi_w, **extras)

        else:
            log.debug("fit_workout: unrecognised endurance plan step type %r — skipped", stype)

    return out


# ── Strength step emitter ────────────────────────────────────────────────────

def _garmin_official_name(cat_str: str, name_int: int) -> str | None:
    """Return Garmin's official human-readable name for a (category, subtype)
    pair, looked up from the animation manifest. Returns None when the pair
    isn't in the manifest.

    Why: the watch's Yoga app appears to look up animations by matching the
    workout step's `wkt_step_name` against the firmware's pose dictionary
    (NOT by the (cat, exercise_name) FIT integers alone — those integers
    survive the wrong-title case but the animation doesn't play). Sending
    "Kneeling Hip Flexor Stretch" with (pose, 41) gets the file accepted but
    skips the Low Lunge animation; sending "Low Lunge with Knee Down Pose"
    triggers playback. So we override the user's library display name with
    Garmin's canonical name when emitting yoga workouts.

    Reference (After-Work_Yoga.fit) exercise_title messages use names like:
       "Mountain Pose", "Downward Facing Dog Pose", "Child's Pose"
    which are Title Case + " Pose" suffix for pose-category entries, and
    Title Case without suffix for move/plank-category entries.
    """
    from app.calculators.garmin_animations import get_animation
    info = get_animation(_CATEGORY_INT.get((cat_str or "").lower(), -1), name_int)
    if info is None:
        return None
    # info.display is the UPPER_SNAKE_NAME form (e.g. LOW_LUNGE_WITH_KNEE_DOWN).
    # Convert to "Title Case" with conjunctions / articles / short
    # prepositions kept lowercase (matches Garmin's reference workouts
    # exactly — e.g. "Low Lunge with Knee Down Pose" not
    # "Low Lunge With Knee Down Pose"). The first word is always capitalised.
    _LOWERCASE_WORDS = {"with", "and", "to", "of", "on", "in", "the", "a", "an",
                         "or", "at", "by", "for"}
    raw_parts = info.display.lower().split("_")
    parts: list[str] = []
    for i, p in enumerate(raw_parts):
        if i > 0 and p in _LOWERCASE_WORDS:
            parts.append(p)
        else:
            parts.append(p.capitalize())
    title = " ".join(parts)
    # Heuristic: childs → Child's (the only common possessive in the pose enum).
    title = title.replace("Childs", "Child's")
    if info.cat == "pose" and not title.endswith(" Pose"):
        title = f"{title} Pose"
    return title


def _resolve_exercise_keys(ex: dict) -> tuple[int, int, str]:
    """(category_int, exercise_name_int, display_name) for a strength step.
    Falls back to UNKNOWN when no Garmin animation key is set — in that case
    the watch shows the display name without an animation."""
    cat_str   = (ex.get("garmin_category") or "")
    cat_int   = _CATEGORY_INT.get(cat_str.lower(), _UNKNOWN_CATEGORY)
    raw_sub   = ex.get("garmin_subtype")
    sub_int   = int(raw_sub) if raw_sub is not None else _UNKNOWN_EXERCISE
    user_name = (ex.get("name") or "Exercise").strip()
    return cat_int, sub_int, user_name


def _resolve_yoga_keys(ex: dict, own_names: dict[str, int]) -> tuple[int, int, str]:
    """(category_int, exercise_name_int, title) for a hold in a Yoga-app file.

    A step is either a Garmin pose or the stretch's own name, never both:

    - When the Yoga app animates the pair, the title is Garmin's canonical
      pose name, NOT the user's library name. The Fenix 6X Yoga app uses this
      title for its pose-animation lookup, so a non-matching custom name
      suppresses playback even when (cat, exercise_name) is correct.
    - Anything else — no mapping, or a pair only the strength app animates —
      goes out as the UNKNOWN category under its own name. The watch labels a
      step from the `exercise_title` keyed by its (category, exercise_name),
      so each distinct name gets its own exercise_name number (`own_names`,
      first seen first); one shared number would show every such step under
      the first one's name. This is the custom-exercise encoding third-party
      generators use for strength workouts (garmin-pt-workout-generator,
      tested on a Forerunner 970). It is unverified in the 6X Yoga app.

    Before, a pair the strength app animates kept Garmin's name too ("Stretch
    Calf" for a foot release), and a stretch with no mapping sent no title at
    all — just the pose-less step."""
    cat_str = (ex.get("garmin_category") or "")
    cat_int = _CATEGORY_INT.get(cat_str.lower())
    raw_sub = ex.get("garmin_subtype")
    if cat_int is not None and raw_sub is not None:
        from app.calculators.garmin_animations import is_yoga_animatable
        if is_yoga_animatable(cat_int, int(raw_sub)):
            official = _garmin_official_name(cat_str, int(raw_sub))
            if official:
                return cat_int, int(raw_sub), official
    name = (ex.get("name") or "Stretch").strip()
    return _UNKNOWN_CATEGORY, own_names.setdefault(name, len(own_names)), name


def workout_runs(steps: list[dict]) -> list[tuple[dict | None, list[dict]]]:
    """Split a step list into runs: (group, members) for each maximal run of
    consecutive steps sharing a `group.uid`, and (None, [step]) for the rest.

    Blocks are stored as fields on each member (spec/sync.yaml,
    workout_exercise/flow_stretch), so after two phones reorder concurrently a
    group's members can end up apart. Grouping by *consecutive* uid, with the
    first member's settings, is the rule every replica applies identically —
    a split group simply becomes two groups with the same settings, which is
    visible and fixable rather than a silent disagreement.
    """
    runs: list[tuple[dict | None, list[dict]]] = []
    for step in steps:
        group = step.get("group") or None
        uid = group.get("uid") if group else None
        if uid and runs and runs[-1][0] is not None and runs[-1][0].get("uid") == uid:
            runs[-1][1].append(step)
        else:
            runs.append((group if uid else None, [step]))
    return runs


def _group_rounds(group: dict) -> int:
    return max(1, int(group.get("rounds") or 1))


def _emit_strength_steps(exercises: list[dict]
                         ) -> tuple[list[dict], dict[tuple[int, int], str]]:
    """Convert strength_exercise plan steps to a sequence of FIT
    workout_step dicts plus the (category, name) → display-name lookup that
    needs to be emitted as `exercise_title` messages.

    Pattern per strength_exercise:
        [work step: reps + cat/name]
        [rest step: time]              ← only if sets > 1
        [repeat step: loop_start, sets]
    """
    out: list[dict] = []
    titles: dict[tuple[int, int], str] = {}
    idx = 0

    def rest(seconds: int) -> None:
        nonlocal idx
        if seconds <= 0:
            return
        out.append(_build_step(idx, name=None, intensity="rest",
                               duration_type="time", duration_value=int(seconds) * 1000))
        idx += 1

    def work(ex: dict) -> None:
        nonlocal idx
        cat, sub, disp = _resolve_exercise_keys(ex)
        if cat != _UNKNOWN_CATEGORY and sub != _UNKNOWN_EXERCISE:
            titles.setdefault((cat, sub), disp)
        weight_kg = float(ex.get("weight_kg") or 0.0)
        out.append(_build_step(
            idx, name=None, intensity="active",
            duration_type="reps", duration_value=max(1, int(ex.get("reps", 8))),
            exercise_category=cat, exercise_name=sub,
            exercise_weight_kg=weight_kg if weight_kg > 0 else None,
        ))
        idx += 1

    for group, members in workout_runs(exercises):
        if group is not None:
            # A circuit or superset: each member once per round, the group's
            # rest after the last, and one loop around the lot. Members' own
            # `sets` do not apply inside a group — rounds are the sets.
            lifts = [m for m in members if m.get("type") == "strength_exercise"]
            if not lifts:
                continue
            loop_start = idx
            for m in members:
                if m.get("type") == "strength_exercise":
                    work(m)
                elif m.get("type") == "rest":
                    rest(int(m.get("duration_seconds") or 0))
            if int(group.get("rest_seconds") or 0) > 0:
                rest(int(group["rest_seconds"]))
            if _group_rounds(group) > 1:
                out.append(_build_repeat_step(idx, from_idx=loop_start, reps=_group_rounds(group)))
                idx += 1
            continue

        ex = members[0]
        stype = ex.get("type", "")
        if stype == "rest":
            rest(int(ex.get("duration_seconds") or 0))
            continue
        if stype != "strength_exercise":
            continue

        sets      = max(1, int(ex.get("sets", 3)))
        reps      = max(1, int(ex.get("reps", 8)))
        weight_kg = float(ex.get("weight_kg") or 0.0)
        rest_s    = int(ex.get("rest_seconds", 90))
        cat, sub, disp = _resolve_exercise_keys(ex)

        if cat != _UNKNOWN_CATEGORY and sub != _UNKNOWN_EXERCISE:
            titles.setdefault((cat, sub), disp)

        loop_start = idx
        out.append(_build_step(
            idx,
            name=None,                      # Connect leaves the work step name empty
            intensity="active",
            duration_type="reps",
            duration_value=reps,
            exercise_category=cat,
            exercise_name=sub,
            exercise_weight_kg=weight_kg if weight_kg > 0 else None,
        ))
        idx += 1

        if sets > 1:
            out.append(_build_step(
                idx, name=None, intensity="rest",
                duration_type="time", duration_value=rest_s * 1000,
            ))
            idx += 1
            out.append(_build_repeat_step(idx, from_idx=loop_start, reps=sets))
            idx += 1

    return out, titles


# ── Yoga / flexibility step emitter ──────────────────────────────────────────

def _build_yoga_step(idx: int, *,
                     duration_ms: int,
                     exercise_category: int,
                     exercise_name: int) -> dict:
    """Yoga pose step. Distinct from `_build_step` because Garmin Connect
    OMITS `target_type` entirely for pose holds — empirically, this is what
    the Fenix 6X Yoga app needs to play the pose animation. Setting
    `target_type=open(2)` (as strength + endurance steps do) appears to keep
    the file valid but suppresses animation playback in the Yoga app's UI.
    """
    return {
        "mesg_num":              27,
        "message_index":         idx,
        "intensity":             "active",
        "duration_type":         "time",
        "duration_value":        int(duration_ms),
        "target_value":          0,
        "secondary_target_value": 0,
        "exercise_category":     int(exercise_category),
        "exercise_name":         int(exercise_name),
        "weight_display_unit":   _WEIGHT_UNIT_DEFAULT,
    }


def _emit_yoga_steps(exercises: list[dict]
                     ) -> tuple[list[dict], dict[tuple[int, int], str]]:
    """Convert mobility_exercise plan steps into timed pose holds for the
    watch's Yoga app.

    Differs from strength encoding: no rest step between sets, and
    `target_type` is omitted on a hold (see _build_yoga_step).

    A hold is one side for its per-side time. A one-sided stretch is held
    once per side, and each set does both sides, so the hold runs
    sets × sides times — written once, with a repeat_until_steps_cmplt loop
    after it (Couch Stretch, 2 sets each side: one 1:00 hold, repeat 4). The
    step change is the cue to switch sides.

    UNVERIFIED ON HARDWARE, and against an earlier note here. That note said
    the 6X Yoga app silently skips loop steps in its pose UI, so sets were
    written out one after another, and a one-sided stretch went out as a
    single hold of twice the per-side time so the watch showed one timer.
    Garmin Connect's yoga reference files (After-Work_Yoga, Alignment_101)
    have no loops in them at all, so they say nothing either way. The
    unrolled file was confusing on the watch: Couch Stretch showed as two
    2:00 Low Lunges with no cue to switch, which read as 2:00 a side. The
    loop is what the user asked for. If a watch does skip it, the visible
    symptom is one side held and the rest of the stretch missing, and the
    fix is to unroll again at the per-side time (count × one hold).

    Groups (repeat / superset, see `workout_runs`) are one pass of their
    members with a single loop around them, as strength does. A one-sided
    member is written out once per side inside the pass rather than looped
    again, so no loop ever sits inside another: whether the watch runs a
    nested repeat is unknown, and nothing here needs one. Members' own
    `sets` do not apply inside a group — rounds are the
    sets. Rest blocks are plain rest steps; whether the Yoga app shows them
    as a pause has not been checked on hardware.
    """
    out: list[dict] = []
    titles: dict[tuple[int, int], str] = {}
    own_names: dict[str, int] = {}
    idx = 0

    def rest(seconds: int) -> None:
        # A rest is the strength encoder's rest step, not a yoga hold: it
        # names no pose, so there is no animation for it to suppress.
        # Unverified on a watch's Yoga app — see the docstring.
        nonlocal idx
        if seconds <= 0:
            return
        out.append(_build_step(idx, name=None, intensity="rest",
                               duration_type="time", duration_value=int(seconds) * 1000))
        idx += 1

    def hold(ex: dict) -> None:
        nonlocal idx
        cat, sub, disp = _resolve_yoga_keys(ex, own_names)
        titles.setdefault((cat, sub), disp)
        out.append(_build_yoga_step(
            idx,
            duration_ms=int(ex.get("duration_seconds", 30)) * 1000,
            exercise_category=cat,
            exercise_name=sub,
        ))
        idx += 1

    def sides(ex: dict) -> int:
        return 2 if ex.get("each_side") else 1

    def loop(from_idx: int, reps: int) -> None:
        nonlocal idx
        if reps > 1:
            out.append(_build_repeat_step(idx, from_idx=from_idx, reps=reps))
            idx += 1

    for group, members in workout_runs(exercises):
        if group is not None:
            if not any(m.get("type") == "mobility_exercise" for m in members):
                continue
            loop_start = idx
            for m in members:
                if m.get("type") == "mobility_exercise":
                    for _ in range(sides(m)):
                        hold(m)
                elif m.get("type") == "rest":
                    rest(int(m.get("duration_seconds") or 0))
            rest(int(group.get("rest_seconds") or 0))
            loop(loop_start, _group_rounds(group))
            continue

        ex = members[0]
        stype = ex.get("type", "")
        if stype == "rest":
            rest(int(ex.get("duration_seconds") or 0))
        elif stype == "mobility_exercise":
            loop_start = idx
            hold(ex)
            loop(loop_start, max(1, int(ex.get("sets", 1))) * sides(ex))

    return out, titles


def _timed_total_ms(steps: list[dict]) -> int:
    """How long the timed steps take, a looped block counted once per run.

    A repeat step loops back over the steps from its `duration_value`, so its
    share is that block's time once more for every run past the first. A loop
    inside a loop needs nothing extra: the inner repeat's share is already
    part of the block the outer one counts."""
    share: list[int] = []
    for s in steps:
        if s.get("duration_type") == "time":
            share.append(int(s.get("duration_value", 0)))
        elif s.get("duration_type") == "repeat_until_steps_cmplt":
            block = sum(share[int(s["duration_value"]):])
            share.append(block * (max(1, int(s.get("target_value", 1))) - 1))
        else:
            share.append(0)
    return sum(share)


# ── Common workout-file assembly ─────────────────────────────────────────────

def _write_file_id(enc: Encoder, *, workout_id: int, time_created_ms: int | None) -> None:
    enc.write_mesg({
        "mesg_num":      0,
        "type":          "workout",
        "manufacturer":  "garmin",
        "product":       _PRODUCT_CONNECT,
        "serial_number": max(1, int(workout_id)),
        "time_created":  _ms_to_datetime(time_created_ms),
    })


# Software-version selector for file_creator. Strength uses the legacy build
# (= the verified-working default); yoga/flexibility need the newer one whose
# extra workout-msg fields gate the Fenix Yoga app's animation pack.
_FILE_CREATOR_SW_VER_LEGACY  = _FILE_CREATOR_SW_VER   # 2609 — works for strength.
_FILE_CREATOR_SW_VER_MODERN  = 2610                   # Newer Connect — yoga refs.


def _write_file_creator(enc: Encoder, *, software_version: int | None = None) -> None:
    enc.write_mesg({
        "mesg_num":         49,
        "software_version": software_version if software_version is not None
                              else _FILE_CREATOR_SW_VER,
        "hardware_version": 0,
    })


def _write_workout_mesg(enc: Encoder, *,
                       name: str, sport: str, sub_sport: str,
                       num_valid_steps: int,
                       description: str | None = None,
                       total_workout_time_ms: int = 0,
                       modern_format: bool = False) -> None:
    """Write the workout-msg.

    `modern_format=True` adds the extra workout-msg fields (9, 10, 16, 21,
    23) that newer Garmin Connect (sw=2610) emits and that all the
    Fenix-6X-animating yoga references in the audit set carry. The Yoga
    app's animation pack appears to be gated on this format — without these
    fields the file is accepted but the pose-animation engine doesn't fire.

    The numeric field IDs are not in the FIT Profile bundled with
    garmin_fit_sdk (which only knows fields up through 11/14/17 for the
    workout message), so we pass them in the dict using the integer keys
    the encoder forwards verbatim — same trick Connect uses to extend the
    message without a Profile update."""
    msg: dict = {
        "mesg_num":        26,
        "message_index":   0,                      # Connect always emits 0
        "wkt_name":        _fit_str(name, 50),
        "sport":           sport,
        "sub_sport":       sub_sport,
        "capabilities":    _CAPS_TCX,
        "num_valid_steps": int(num_valid_steps),
    }
    if description:
        msg["wkt_description"] = _fit_str(description, 250)
    if modern_format:
        # Names match the Profile-extension monkey-patch at top of module.
        msg["_unknown_9"]            = 0
        msg["_total_workout_time"]   = int(total_workout_time_ms)
        msg["_zone_array"]           = [0] * 16
        msg["_total_workout_time_b"] = int(total_workout_time_ms)
        msg["_unknown_23"]           = 0
    enc.write_mesg(msg)


def _write_exercise_titles(enc: Encoder,
                            titles: dict[tuple[int, int], str]) -> None:
    """Emit one `exercise_title` message per unique (cat, name) pair used in
    the workout. The watch resolves a step's displayed exercise label by
    looking up its (category, exercise_name) in this block."""
    for t_idx, ((cat, sub), disp) in enumerate(titles.items()):
        enc.write_mesg({
            "mesg_num":          264,
            "message_index":     t_idx,
            "exercise_category": int(cat),
            "exercise_name":     int(sub),
            "wkt_step_name":     _fit_str(disp, 50),
        })


def _write_steps(enc: Encoder, steps: Iterable[dict]) -> None:
    for s in steps:
        enc.write_mesg(s)


def _fallback_open_step() -> dict:
    """Defensive fallback: one open-ended active step so the watch can still
    accept and start an otherwise-empty workout file."""
    return _build_step(0, name=None, intensity="active",
                        duration_type="open", duration_value=0)


# ── Public API: endurance workout ────────────────────────────────────────────

def generate_workout_fit(name: str, sport: str, plan_steps: list[dict],
                         workout_id: int = 0,
                         time_created: int | None = None,
                         pace_coaching: bool = False,
                         paces: dict[str, float] | None = None,
                         lthr: int | None = None,
                         ftp: int | None = None,
                         description: str | None = None) -> bytes:
    """Return the bytes of a FIT workout file for an endurance workout.

    Output mirrors Garmin Connect's own emitted workout files: same
    file_id (manufacturer=GARMIN, product=connect-sentinel), file_creator,
    workout-msg capabilities + sub_sport, and per-step
    weight_display_unit + secondary_target_value invariants. The Fenix
    calendar / today's-workout widget treats it like a Connect-pushed file.

    name:          Display name on the watch's workout menu.
    sport:         'running', 'cycling', 'mountain_biking', 'trail_biking',
                   'road_biking', 'gravel_cycling', 'swimming', 'hiking',
                   'walking'. Unknown sports fall back to running.
    plan_steps:    PlannedWorkout.steps JSON list.
    workout_id:    file_id.serial_number (uint32, ≥ 1).
    time_created:  Unix-epoch MILLISECONDS for file_id.time_created. Persist
                   this so the matching schedule_msg can use it as FK.
    pace_coaching: Whether to attach coaching targets to active steps.
    paces:         VDOT-derived pace zones (sec/km) for running pace targets.
    lthr / ftp:    Threshold HR + FTP for MTB / cycling target ranges.
    description:   Optional pre-workout summary shown on the watch.
    """
    sport_key = (sport or "running").lower()
    sport_v, sub_sport_v = _ENDURANCE_SPORT_ROUTING.get(
        sport_key, _ENDURANCE_SPORT_ROUTING["running"]
    )
    family = _sport_family(sport_key)

    steps = _emit_endurance_steps(plan_steps or [],
                                   family=family,
                                   paces=paces,
                                   lthr=lthr, ftp=ftp,
                                   pace_coaching=pace_coaching)
    if not steps:
        steps = [
            _build_step(0, name="Warm Up", intensity="warmup",
                         duration_type="time", duration_value=_ms_for(5)),
            _build_step(1, name=None,       intensity="active",
                         duration_type="open", duration_value=0),
            _build_step(2, name="Cool Down", intensity="cooldown",
                         duration_type="time", duration_value=_ms_for(5)),
        ]

    enc = Encoder()
    _write_file_id(enc, workout_id=workout_id, time_created_ms=time_created)
    _write_file_creator(enc)
    _write_workout_mesg(enc, name=name, sport=sport_v, sub_sport=sub_sport_v,
                        num_valid_steps=len(steps), description=description)
    _write_steps(enc, steps)
    return finish_encoder(enc)


# ── Public API: strength / flexibility / yoga workout ────────────────────────

def generate_strength_workout_fit(name: str,
                                   exercises: list[dict],
                                   workout_id: int = 0,
                                   time_created: int | None = None,
                                   *,
                                   description: str | None = None,
                                   units: str = "metric",
                                   workout_type: str = "strength") -> bytes:
    """Return the bytes of a FIT workout file for a strength, mobility,
    flexibility, yoga, pilates, HIIT, or cardio session.

    Dispatch on `workout_type`:
      "strength" → rep-based work steps with rest-and-repeat loops, sport=
                   training / sub_sport=strength_training. Exercise animations
                   play via the (exercise_category, exercise_name) lookup +
                   `exercise_title` block.
      "mobility" | "flexibility" | "yoga" → timed holds, each looped over
                   its sets and sides, sport=training / sub_sport=yoga. The Yoga app's
                   pose-animation engine renders the (category, name) pair —
                   on Fenix 6X firmware this is the ONLY sport routing that
                   plays stretch animations.
      "pilates" → sport=training / sub_sport=pilates (Fenix 7+/Epix only).
      "hiit"    → sport=training / sub_sport=hiit.
      "cardio"  → sport=training / sub_sport=cardio_training.

    exercises:     Step dicts with `type` ∈ {strength_exercise,
                   mobility_exercise}. strength_exercise needs
                   sets/reps/weight_kg/rest_seconds; mobility_exercise needs
                   duration_seconds/sets/each_side. Both use
                   garmin_category (str) + garmin_subtype (int) for animations.
    workout_id:    file_id.serial_number (uint32, ≥ 1).
    time_created:  Unix-epoch milliseconds; persist so the matching
                   schedule_msg.time_created stays in sync as FK.
    description:   Optional pre-workout summary. Clipped to 250 bytes.
    units:         Reserved for future per-step weight display unit overrides
                   (Connect emits POUND universally so we follow suit).
    workout_type:  See dispatch table above.
    """
    wtype = (workout_type or "strength").lower()
    sport_v, sub_sport_v = WORKOUT_TYPE_ROUTING.get(
        wtype, WORKOUT_TYPE_ROUTING["strength"]
    )

    is_yoga_routing = wtype in ("flexibility", "mobility", "yoga", "pilates")

    if wtype == "strength":
        steps, titles = _emit_strength_steps(exercises or [])
    else:
        # Flexibility, mobility, yoga, pilates, hiit, cardio all use the
        # timed-hold pattern. Its pose naming is the Yoga app's (see
        # _resolve_yoga_keys); only flexibility and mobility are generated.
        steps, titles = _emit_yoga_steps(exercises or [])

    if not steps:
        steps = [_fallback_open_step()]

    # Yoga / pilates / flexibility workouts emit the modern file format
    # (sw=2610 + the extra workout-msg fields the Fenix Yoga app appears to
    # require for animation playback). Strength keeps the legacy sw=2609
    # format that's verified-working.
    sw_version = _FILE_CREATOR_SW_VER_MODERN if is_yoga_routing else _FILE_CREATOR_SW_VER_LEGACY
    # Connect's yoga references hold no loops, so they only show this is the
    # sum of the holds; with a loop, it is taken as the time the workout runs.
    total_time_ms = _timed_total_ms(steps)

    enc = Encoder()
    _write_file_id(enc, workout_id=workout_id, time_created_ms=time_created)
    _write_file_creator(enc, software_version=sw_version)
    _write_workout_mesg(enc, name=name, sport=sport_v, sub_sport=sub_sport_v,
                        num_valid_steps=len(steps), description=description,
                        total_workout_time_ms=total_time_ms,
                        modern_format=is_yoga_routing)
    _write_steps(enc, steps)
    _write_exercise_titles(enc, titles)
    return finish_encoder(enc)


# ── Public API: Schedule.fit (training calendar entries) ─────────────────────

_FIT_EPOCH_UNIX = 631065600  # 1989-12-31T00:00:00Z

_CRC_TABLE = (0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
              0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400)


# The profile version every Encoder-built file declares. Pinned rather than
# taken from garmin_fit_sdk, which stamps its own: the phone builds these same
# files byte for byte (mobile/core/.../fit/WorkoutFit.kt, held to the server by
# shared golden bytes), so an SDK upgrade must not change a header on one side
# only. The number only labels the profile; the messages written here exist,
# unchanged, in every profile since.
SDK_FILE_PROFILE_VERSION = 21208


def finish_encoder(enc) -> bytes:
    """Close a garmin_fit_sdk Encoder and return its bytes with the header's
    profile version set to SDK_FILE_PROFILE_VERSION, header and file CRCs
    recomputed to match."""
    data = bytearray(enc.close())
    struct.pack_into("<H", data, 2, SDK_FILE_PROFILE_VERSION)
    struct.pack_into("<H", data, 12, _fit_crc(bytes(data[:12])))
    struct.pack_into("<H", data, len(data) - 2, _fit_crc(bytes(data[:-2])))
    return bytes(data)


def _fit_crc(data: bytes, crc: int = 0) -> int:
    for byte in data:
        tmp = _CRC_TABLE[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ _CRC_TABLE[byte & 0xF]
        tmp = _CRC_TABLE[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc = crc ^ tmp ^ _CRC_TABLE[(byte >> 4) & 0xF]
    return crc


def _definition(local: int, global_num: int, fields) -> bytes:
    """A big-endian definition record. Fields are (number, size, base type)."""
    out = bytearray()
    out.append(0x40 | local)
    out.append(0)                                    # reserved
    out.append(1)                                    # architecture: big-endian
    out += struct.pack(">H", global_num)
    out.append(len(fields))
    for number, size, base in fields:
        out += bytes((number, size, base))
    return bytes(out)


def _data(local: int, values) -> bytes:
    """A data record. Values are (value, size); bytes are written as-is."""
    out = bytearray()
    out.append(local)
    for value, size in values:
        if isinstance(value, bytes):
            out += value.ljust(size, b"\x00")[:size]
        elif size == 1:
            out.append(value & 0xFF)
        else:
            out += struct.pack(">" + {2: "H", 4: "I"}[size],
                               value & (2 ** (size * 8) - 1))
    return bytes(out)


def _fit_file(records: bytes) -> bytes:
    """Wrap records in a FIT header and trailing CRC.

    Protocol 1.0 and profile 21213 — what Garmin Connect writes, rather than
    whatever the encoder library defaults to (2.0/21208). See
    generate_schedule_fit for where these constants come from.
    """
    header = bytearray()
    header.append(14)
    header.append(0x10)                              # protocol version 1.0
    header += struct.pack("<H", 21213)               # profile version
    header += struct.pack("<I", len(records))
    header += b".FIT"
    header += struct.pack("<H", _fit_crc(bytes(header)))
    body = bytes(header) + records
    return body + struct.pack("<H", _fit_crc(body))


def generate_schedule_fit(items: list[dict],
                          watch_serial: int | None = None,
                          watch_product: int | None = None,
                          plan_name: str = "Training Plan",
                          plan_start: date | None = None,
                          plan_end: date | None = None) -> bytes:
    """Return the bytes of a FIT type-7 schedule file placing each workout in
    the watch's training calendar.

    Hand-rolled rather than encoded through garmin_fit_sdk, because the file
    Garmin Connect actually sends contains a message the public FIT profile does
    not describe — global message 137, carrying a training plan's name and dates
    — and two undocumented fields on `schedule`. The SDK encoder addresses
    fields by profile name and so cannot write any of them.

    Every structural choice below was read off a Connect upload captured over
    BLE, down to the protocol version and the big-endian records:

      header        protocol 1.0, profile 21213
      file_id       type=schedules, manufacturer=GARMIN, product=connect(65534),
                    serial_number=1, number=1, time_created=now
      file_creator  software_version=26, hardware_version=0
      mesg 137      message_index, name, start, end, end, 1, 0, 1, n, 60
      schedule      manufacturer, product, serial_number=workout_id,
                    time_created (FK into the workout's own file_id),
                    type=workout, scheduled_time=noon UTC, completed=0,
                    field 10, field 7

    Fields 7 and 8 of message 137, and field 10 of `schedule`, are written
    because Connect writes them and the watch is the only thing that gets a
    vote. Their meaning is unread: field 10 took values 0-4 across eighteen
    entries and was 0 on all five Fridays, which reads like an intensity the
    plan assigns, and nothing in Tracks knows how to compute one.

    items:         [{"scheduled_date": date, "workout_id": int,
                     "time_created": int (ms)}]
                   time_created MUST equal each workout's file_id.time_created.
    watch_serial:  Ignored. The watch is named nowhere in Connect's file; an
                   earlier version wrote the watch's own serial here on the
                   theory that the watch matched it against itself.
    watch_product: Ignored, as above.
    """
    if not items:
        return b""

    now_fit = int(_now_ms() // 1000) - _FIT_EPOCH_UNIX
    dates = sorted(item["scheduled_date"] for item in items)
    start = plan_start or dates[0]
    finish = plan_end or dates[-1]
    name = (plan_name or "Training Plan").encode("utf-8")[:63] + b"\x00"

    records = bytearray()

    records += _definition(0, 0, [(0, 1, 0x00), (1, 2, 0x84), (2, 2, 0x84),
                                  (4, 4, 0x86), (3, 4, 0x8C), (5, 2, 0x84)])
    records += _data(0, [(_FILE_TYPE_SCHEDULES, 1), (_MANUFACTURER_GARMIN, 2),
                         (_PRODUCT_CONNECT, 2), (now_fit, 4),
                         (_SCHEDULE_SERIAL, 4), (1, 2)])

    records += _definition(0, 49, [(0, 2, 0x84), (1, 1, 0x02)])
    records += _data(0, [(_SCHEDULE_SOFTWARE_VERSION, 2), (0, 1)])

    records += _definition(0, 137, [(254, 2, 0x84), (0, len(name), 0x07),
                                    (1, 4, 0x86), (2, 4, 0x86), (3, 4, 0x86),
                                    (4, 1, 0x00), (5, 1, 0x00), (6, 1, 0x00),
                                    (7, 1, 0x02), (8, 1, 0x02)])
    records += _data(0, [(_PLAN_MESSAGE_INDEX, 2), (name, len(name)),
                         (_date_to_fit_seconds_noon_utc(start), 4),
                         (_date_to_fit_seconds_noon_utc(finish), 4),
                         (_date_to_fit_seconds_noon_utc(finish), 4),
                         (1, 1), (0, 1), (1, 1),
                         (min(255, max(0, len(items) - 1)), 1), (60, 1)])

    records += _definition(0, 28, [(0, 2, 0x84), (1, 2, 0x84), (2, 4, 0x8C),
                                   (3, 4, 0x86), (5, 1, 0x00), (6, 4, 0x86),
                                   (4, 1, 0x00), (10, 1, 0x02), (7, 2, 0x84)])
    for item in items:
        created = int(item["time_created"]) // 1000 - _FIT_EPOCH_UNIX
        records += _data(0, [
            (_MANUFACTURER_GARMIN, 2),
            (_PRODUCT_CONNECT, 2),
            (max(1, int(item["workout_id"])), 4),
            (created, 4),
            (_SCHEDULE_TYPE_WORKOUT, 1),
            (_date_to_fit_seconds_noon_utc(item["scheduled_date"]), 4),
            (0, 1),                                  # completed
            (0, 1),                                  # field 10, see docstring
            (_PLAN_MESSAGE_INDEX, 2),                # field 7 -> the plan above
        ])

    return _fit_file(bytes(records))
