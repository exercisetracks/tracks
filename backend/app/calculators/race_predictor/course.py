# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
GPX course parsing and course-level summaries.

Parses GPX 1.0/1.1 tracks into ~100 m gradient segments, samples a path-point
list for the elevation profile, and derives course-wide stats (totals,
technicality penalty).
"""
from __future__ import annotations

import math
import xml.etree.ElementTree as ET

from .geo import _haversine_m

_GPX_NS = {
    "gpx":   "http://www.topografix.com/GPX/1/1",
    "gpx10": "http://www.topografix.com/GPX/1/0",
}


def _gpx_namespace(root: ET.Element) -> str | None:
    """Return the _GPX_NS key matching the document's namespace, or None."""
    tag = root.tag
    if "topografix.com/GPX/1/1" in tag:
        return "gpx"
    if "topografix.com/GPX/1/0" in tag:
        return "gpx10"
    return None


def _find_trkpts(root: ET.Element, ns: str | None) -> list[ET.Element]:
    """Find all <trkpt> elements, honouring the document namespace (if any)."""
    if ns:
        return root.findall(f".//{{{_GPX_NS[ns]}}}trkpt")
    return root.findall(".//trkpt")


def _parse_root(content: bytes | str) -> ET.Element | None:
    """Parse GPX content into an XML root, or None on parse error."""
    try:
        return ET.fromstring(content if isinstance(content, str) else content.decode())
    except ET.ParseError:
        return None


def parse_gpx(content: bytes | str) -> list[dict]:
    """
    Parse a GPX 1.0 or 1.1 file and return a list of segment dicts:
      [{"distance_m": float, "elevation_gain_m": float, "gradient": float}, ...]

    Each entry corresponds to ~100 m of course, averaged over the raw track points.
    Returns an empty list if the file cannot be parsed or contains no points.
    """
    root = _parse_root(content)
    if root is None:
        return []

    ns  = _gpx_namespace(root)
    pts = _find_trkpts(root, ns)
    if not pts:
        return []

    raw: list[tuple[float, float, float | None]] = []
    for pt in pts:
        try:
            lat = float(pt.get("lat", 0))
            lon = float(pt.get("lon", 0))
        except ValueError:
            continue
        ele_el = pt.find(f"{{{_GPX_NS.get(ns or 'gpx', '')}}}ele") if ns else pt.find("ele")
        ele = float(ele_el.text) if ele_el is not None and ele_el.text else None
        raw.append((lat, lon, ele))

    if len(raw) < 2:
        return []

    # Aggregate into ~100 m segments
    segments: list[dict] = []
    bucket_dist = 0.0
    bucket_gain = 0.0
    prev = raw[0]

    for cur in raw[1:]:
        d    = _haversine_m(prev[0], prev[1], cur[0], cur[1])
        gain = (cur[2] - prev[2]) if (cur[2] is not None and prev[2] is not None) else 0.0
        bucket_dist += d
        bucket_gain += gain
        if bucket_dist >= 100:
            segments.append(_segment(bucket_dist, bucket_gain))
            bucket_dist = 0.0
            bucket_gain = 0.0
        prev = cur

    if bucket_dist > 10:  # flush last partial segment
        segments.append(_segment(bucket_dist, bucket_gain))

    return segments


def _segment(bucket_dist: float, bucket_gain: float) -> dict:
    """Build a single ~100 m course segment dict from accumulated distance/gain."""
    grad = bucket_gain / bucket_dist if bucket_dist > 0 else 0.0
    return {
        "distance_m":       round(bucket_dist, 1),
        "elevation_gain_m": round(bucket_gain, 1),
        "gradient":         round(grad, 4),
    }


def extract_path_points(content: bytes | str, max_points: int = 300) -> list[list]:
    """
    Return a sampled list of [lat, lon, ele_or_None] from a GPX file.

    Elevation is included when available so the frontend can render an
    elevation profile.  Samples up to max_points evenly from all track points.
    """
    root = _parse_root(content)
    if root is None:
        return []

    ns  = _gpx_namespace(root)
    pts = _find_trkpts(root, ns)
    if not pts:
        return []

    coords: list[list] = []
    for pt in pts:
        try:
            lat = float(pt.get("lat", 0))
            lon = float(pt.get("lon", 0))
            ele_el = pt.find(f"{{{_GPX_NS[ns]}}}ele") if ns else pt.find("ele")
            ele = round(float(ele_el.text), 1) if ele_el is not None and ele_el.text else None
            coords.append([round(lat, 6), round(lon, 6), ele])
        except (ValueError, TypeError):
            continue

    if not coords:
        return []

    # Uniform downsampling
    if len(coords) <= max_points:
        return coords
    step = len(coords) / max_points
    return [coords[round(i * step)] for i in range(max_points)]


def compute_technicality_factor(segments: list[dict]) -> tuple[float, str]:
    """
    Derive a time-penalty multiplier from course roughness/technicality.

    Two signals from the parsed GPX segments:
      1. Gradient standard deviation — high σ means many short climbs/descents
         (rocky singletrack, switchbacks, undulating fire roads).
      2. Percentage of segments steeper than ±20 % — sustained steep terrain
         requiring careful foot placement.

    Returns (factor, label) where factor ≥ 1.0.
    Examples:
      Smooth road (σ ≈ 2-4 %):     1.00   "Smooth"
      Rolling trail (σ ≈ 8 %):     1.015  "Rolling"
      Technical trail (σ ≈ 14 %):  1.04   "Technical"
      Very technical (σ ≈ 20 %+):  1.07+  "Very technical"
    """
    if not segments or len(segments) < 2:
        return 1.0, "Smooth"

    gradients = [s["gradient"] for s in segments]
    n         = len(gradients)
    mean_g    = sum(gradients) / n
    sigma     = math.sqrt(sum((g - mean_g) ** 2 for g in gradients) / n)

    # Fraction of segments with extreme grade (braking / careful footwork required)
    steep_pct = sum(1 for g in gradients if abs(g) > 0.20) / n

    factor = 1.0 + max(0.0, (sigma - 0.04) * 0.30) + steep_pct * 0.06
    factor = round(min(1.12, factor), 3)

    if   factor < 1.012:  label = "Smooth"
    elif factor < 1.035:  label = "Rolling"
    elif factor < 1.065:  label = "Technical"
    else:                 label = "Very technical"

    return factor, label


def course_totals(segments: list[dict]) -> dict:
    """Return total distance (m), elevation gain (m), and loss (m) from segments."""
    if not segments:
        return {"distance_m": 0, "elevation_gain_m": 0, "elevation_loss_m": 0}
    total_d    = sum(s["distance_m"]       for s in segments)
    total_gain = sum(s["elevation_gain_m"] for s in segments if s["elevation_gain_m"] > 0)
    total_loss = abs(sum(s["elevation_gain_m"] for s in segments if s["elevation_gain_m"] < 0))
    return {
        "distance_m":       round(total_d),
        "elevation_gain_m": round(total_gain),
        "elevation_loss_m": round(total_loss),
    }
