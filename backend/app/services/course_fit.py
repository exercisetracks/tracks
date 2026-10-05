# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Parse course geometry from external FIT course files and GPX uploads.

Used by:
  - the course-ingest sync endpoint, to render device courses NOT created here
    (the user wanted full geometry + elevation, not just a filename), and
  - the /maps/courses/import endpoint, to create a custom track from a user's
    uploaded .gpx / .fit file.

Both paths return the same dict shape that courses_api stores on a CustomTrack:
  {name, sport, coords [[lng,lat,ele|None],...], distance_m, ascent_m, descent_m,
   bounds [w,s,e,n], profile (route_profile.build_profile output)}
"""
from __future__ import annotations

import io
import math
import xml.etree.ElementTree as ET

import fitdecode

from app.parsers.utils import as_str, get, get_enhanced
from app.services import elevation_sampler
from app.services.route_profile import build_profile

_SEMI_TO_DEG = 180.0 / (2 ** 31)


def _to_deg(semicircles) -> float | None:
    if semicircles is None:
        return None
    return semicircles * _SEMI_TO_DEG


def _haversine_m(a, b) -> float:
    dx = (b[0] - a[0]) * math.cos(math.radians((a[1] + b[1]) / 2)) * 111320.0
    dy = (b[1] - a[1]) * 110540.0
    return math.hypot(dx, dy)


def compute_stats(coords: list[list[float]], *, dem_fill: bool = True) -> dict:
    """distance / ascent / descent / bounds / elevation profile for a polyline.

    Shared by the importers here and by courses_api when saving a built track.
    DEM-fills elevation when the source carried none (so a bare GPX still gets a
    real profile + ascent). Returns rounded coords + stats; raises on < 2 points.
    """
    coords = [c for c in (coords or []) if c and c[0] is not None and c[1] is not None]
    if len(coords) < 2:
        raise ValueError("course has fewer than 2 valid points")

    if dem_fill and not any(len(c) > 2 and c[2] is not None for c in coords):
        eles = elevation_sampler.sample_elevations([[c[0], c[1]] for c in coords])
        coords = [[c[0], c[1], (eles[i] if i < len(eles) else None)] for i, c in enumerate(coords)]

    dist = sum(_haversine_m(coords[i - 1], coords[i]) for i in range(1, len(coords)))
    gain = loss = 0.0
    prev = None
    for c in coords:
        e = c[2] if len(c) > 2 else None
        if e is None:
            continue
        if prev is not None:
            d = e - prev
            gain += d if d > 0 else 0
            loss += -d if d < 0 else 0
        prev = e

    lons = [c[0] for c in coords]
    lats = [c[1] for c in coords]
    return {
        "coords": [[round(c[0], 6), round(c[1], 6),
                    (round(c[2], 1) if len(c) > 2 and c[2] is not None else None)] for c in coords],
        "distance_m": round(dist, 1),
        "ascent_m": round(gain, 1),
        "descent_m": round(loss, 1),
        "bounds": [min(lons), min(lats), max(lons), max(lats)],
        "profile": build_profile([[c[0], c[1]] for c in coords], dist),
    }


def _finalize(name: str, sport: str, coords: list[list[float]]) -> dict:
    """compute_stats + the source name/sport, for the importers."""
    stats = compute_stats(coords)
    return {"name": name or "Imported Course", "sport": sport or "hiking", **stats}


def parse_course_fit(data: bytes) -> dict:
    """Parse a Garmin course (or activity) FIT file into the standard course dict.

    Reads `record` frames for geometry; `course` frame for the display name/sport.
    Tolerant of activity FITs too (no course frame) so 'save device course' works
    even if the file is really a recorded ride.
    """
    name = None
    sport = "hiking"
    coords: list[list[float]] = []

    with fitdecode.FitReader(io.BytesIO(data)) as fit:
        for frame in fit:
            if not isinstance(frame, fitdecode.FitDataMessage):
                continue
            if frame.name == "course":
                name = get(frame, "name") or name
                s = as_str(get(frame, "sport"))
                if s:
                    sport = s
            elif frame.name == "record":
                lat = _to_deg(get(frame, "position_lat"))
                lng = _to_deg(get(frame, "position_long"))
                if lat is None or lng is None:
                    continue
                ele = get_enhanced(frame, "altitude")
                coords.append([lng, lat, ele])

    return _finalize(name, sport, coords)


def parse_gpx(text: str) -> dict:
    """Parse a GPX 1.1 file (track or route) into the standard course dict."""
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise ValueError(f"invalid GPX: {exc}")

    def _local(tag: str) -> str:
        return tag.rsplit("}", 1)[-1]

    name = None
    coords: list[list[float]] = []
    for el in root.iter():
        tag = _local(el.tag)
        if tag == "name" and name is None and el.text:
            name = el.text.strip()
        elif tag in ("trkpt", "rtept"):
            try:
                lat = float(el.attrib["lat"])
                lng = float(el.attrib["lon"])
            except (KeyError, ValueError):
                continue
            ele = None
            for child in el:
                if _local(child.tag) == "ele" and child.text:
                    try:
                        ele = float(child.text)
                    except ValueError:
                        ele = None
                    break
            coords.append([lng, lat, ele])

    return _finalize(name, "hiking", coords)
