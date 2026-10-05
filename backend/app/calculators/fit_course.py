# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""FIT *course* file encoder.

Generates binary `.fit` course files (file type 6) that Garmin watches list under
Navigation → Courses and can follow turn-by-turn or as a breadcrumb line. This is
the map-side counterpart to fit_workout.py (which emits workout/schedule files):

  generate_course_fit(name, coords, distance_m, sport=..., course_points=...) -> bytes

Message layout mirrors a Garmin-Connect-emitted course:

  file_id      type=course, manufacturer=garmin, product=connect-sentinel,
               serial_number=track id, time_created=now
  file_creator software_version
  course       name, sport, capabilities (position|distance|... , +navigation when
               turn points are present)
  lap          one lap spanning the whole course: total_distance, total_ascent/
               descent, start/end position, timer/elapsed time
  event        timer start … (records) … timer stop
  record[]     one per geometry vertex: position (semicircles), altitude (m),
               cumulative distance (m), synthetic monotonic timestamp
  course_point[] turn prompts (only when turn_by_turn course_points supplied)

Field VALUES are passed in real-world units (metres, m/s, datetimes) — the SDK
Encoder unapplies each field's scale/offset on write (see garmin_fit_sdk encoder
_transform_value). The one exception is position_lat/long, which are stored as raw
sint32 semicircles (scale 1), so we convert degrees → semicircles ourselves.
"""
from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone

from garmin_fit_sdk import Encoder

_PRODUCT_CONNECT = 65534          # Garmin "connect" product sentinel (see fit_workout)
_FILE_CREATOR_SW_VER = 2609
_SEMI = 2 ** 31 / 180.0           # degrees → semicircles

# course capabilities bitfield (course_capabilities): processed|valid|time|distance|position
_CAP_BASE = 1 | 2 | 4 | 8 | 16
_CAP_NAVIGATION = 512

# Assumed travel speed (m/s) per sport family — only used to synthesise the
# monotonic record timestamps a course file needs; the watch navigates by
# position/distance, not these times.
_SPEED_MPS = {
    "running": 3.0, "trail_running": 2.6, "walking": 1.3, "hiking": 1.1,
    "cycling": 5.5, "road_cycling": 7.0, "mountain_biking": 3.5, "gravel_cycling": 5.5,
}
_DEFAULT_SPEED = 2.0

# Map our sport strings to FIT sport enum names the SDK understands.
_SPORT_ENUM = {
    "running": "running", "trail_running": "running", "road_running": "running",
    "walking": "walking", "hiking": "hiking",
    "cycling": "cycling", "road_cycling": "cycling", "gravel_cycling": "cycling",
    "mountain_biking": "cycling",
}

# Valid FIT course_point type names (the rest fall back to "generic").
COURSE_POINT_TYPES = {
    "generic", "summit", "valley", "water", "food", "danger", "left", "right",
    "straight", "first_aid", "sprint", "left_fork", "right_fork", "middle_fork",
    "slight_left", "sharp_left", "slight_right", "sharp_right", "u_turn",
    "segment_start", "segment_end", "campsite", "aid_station", "rest_area",
    "general_distance",
}


def _semicircles(deg: float) -> int:
    return int(round(deg * _SEMI))


def _haversine_m(a, b) -> float:
    dx = (b[0] - a[0]) * math.cos(math.radians((a[1] + b[1]) / 2)) * 111320.0
    dy = (b[1] - a[1]) * 110540.0
    return math.hypot(dx, dy)


def _cumulative(coords: list[list[float]]) -> list[float]:
    cum = [0.0]
    for i in range(1, len(coords)):
        cum.append(cum[-1] + _haversine_m(coords[i - 1], coords[i]))
    return cum


def _fit_str(value: str, max_bytes: int) -> str:
    """Truncate a string to fit inside a FIT string field.

    FIT string fields cap at 255 bytes on the wire, and garmin_fit_sdk
    enforces it strictly: a field over the limit raises `ValueError` and
    aborts encoding the whole file. Slicing a Python `str` by character
    count (`name[:50]`) is not a safe guard against that — a course or
    waypoint name with a few multi-byte UTF-8 characters can exceed 255
    bytes well under 50 characters. See fit_workout.py's copy of this
    function, which found the bug: one long field there took down every
    pending workout in a single request, not just its own.
    """
    return value.encode("utf-8")[:max_bytes].decode("utf-8", errors="ignore")


def generate_course_fit(
    name: str,
    coords: list[list[float]],
    distance_m: float | None = None,
    *,
    sport: str = "hiking",
    course_id: int = 0,
    ascent_m: float | None = None,
    descent_m: float | None = None,
    course_points: list[dict] | None = None,
    time_created: datetime | None = None,
) -> bytes:
    """Encode a Garmin course FIT from [[lng, lat, ele|None], ...] geometry.

    course_points: optional list of {d_m, lat, lng, type, name} turn prompts; when
    present the file is marked navigation-capable and each becomes a course_point.
    """
    pts = [c for c in (coords or []) if c and c[0] is not None and c[1] is not None]
    if len(pts) < 2:
        raise ValueError("course needs at least 2 coordinates")

    cum = _cumulative(pts)
    total = distance_m if distance_m else cum[-1]
    sport_enum = _SPORT_ENUM.get((sport or "hiking").lower(), "hiking")
    speed = _SPEED_MPS.get((sport or "").lower(), _DEFAULT_SPEED)

    # Synthetic monotonic timestamps from a constant assumed speed.
    base = time_created or datetime.now(timezone.utc)
    times = [base + timedelta(seconds=cum[i] / speed) for i in range(len(pts))]

    # Elevation gain/loss for the lap summary (compute if not supplied).
    if ascent_m is None or descent_m is None:
        gain = loss = 0.0
        prev = None
        for c in pts:
            e = c[2] if len(c) > 2 else None
            if e is None:
                continue
            if prev is not None:
                d = e - prev
                gain += d if d > 0 else 0
                loss += -d if d < 0 else 0
            prev = e
        ascent_m = gain if ascent_m is None else ascent_m
        descent_m = loss if descent_m is None else descent_m

    has_turns = bool(course_points)
    capabilities = _CAP_BASE | (_CAP_NAVIGATION if has_turns else 0)

    enc = Encoder()

    enc.write_mesg({
        "mesg_num":      0,
        "type":          "course",
        "manufacturer":  "garmin",
        "product":       _PRODUCT_CONNECT,
        "serial_number": max(1, int(course_id) or 1),
        "time_created":  base,
    })
    enc.write_mesg({
        "mesg_num":         49,
        "software_version": _FILE_CREATOR_SW_VER,
        "hardware_version": 0,
    })
    enc.write_mesg({
        "mesg_num":     31,
        "name":         _fit_str(name or "Course", 50),
        "sport":        sport_enum,
        "capabilities": capabilities,
    })

    a, b = pts[0], pts[-1]
    enc.write_mesg({
        "mesg_num":            19,
        "message_index":       0,
        "timestamp":           times[-1],
        "start_time":          times[0],
        "start_position_lat":  _semicircles(a[1]),
        "start_position_long": _semicircles(a[0]),
        "end_position_lat":    _semicircles(b[1]),
        "end_position_long":   _semicircles(b[0]),
        "total_elapsed_time":  (times[-1] - times[0]).total_seconds(),
        "total_timer_time":    (times[-1] - times[0]).total_seconds(),
        "total_distance":      total,
        "total_ascent":        int(round(ascent_m or 0)),
        "total_descent":       int(round(descent_m or 0)),
    })

    # timer start
    enc.write_mesg({
        "mesg_num":   21, "timestamp": times[0],
        "event":      "timer", "event_type": "start",
    })

    for i, c in enumerate(pts):
        ele = c[2] if len(c) > 2 else None
        rec = {
            "mesg_num":      20,
            "timestamp":     times[i],
            "position_lat":  _semicircles(c[1]),
            "position_long": _semicircles(c[0]),
            "distance":      cum[i],
        }
        if ele is not None:
            rec["altitude"] = float(ele)
        enc.write_mesg(rec)

    # timer stop
    enc.write_mesg({
        "mesg_num":   21, "timestamp": times[-1],
        "event":      "timer", "event_type": "stop_all",
    })

    if has_turns:
        for idx, cp in enumerate(course_points):
            d_m = float(cp.get("d_m") or 0.0)
            # Timestamp for the turn = base + distance/speed (monotonic with records).
            cp_type = cp.get("type", "generic")
            if cp_type not in COURSE_POINT_TYPES:
                cp_type = "generic"
            enc.write_mesg({
                "mesg_num":      32,
                "message_index": idx,
                "timestamp":     base + timedelta(seconds=d_m / speed),
                "position_lat":  _semicircles(cp["lat"]),
                "position_long": _semicircles(cp["lng"]),
                "distance":      d_m,
                "type":          cp_type,
                "name":          _fit_str(cp.get("name") or "", 50),
            })

    return bytes(enc.close())
