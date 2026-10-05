# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build a smooth distance/elevation profile for a long-trail section.

The stored section geometry is resampled at EVEN distance intervals (so the x-axis
isn't clustered by raw vertex density), DEM-sampled (elevation_sampler — high-res
regional with a global z0-7 fallback), de-spiked, and reduced to a
distance/elevation series + gain/loss for the chart. Pure compute; the API layer
(routes_api) loads the geometry and returns this.
"""

from __future__ import annotations

import json
import logging
import math
from pathlib import Path

from app.config import settings
from app.services import elevation_sampler

logger = logging.getLogger(__name__)

_PROFILE_MIN_N, _PROFILE_MAX_N = 80, 400   # output points, evenly spaced by distance
_GAP_M = 2000           # a step longer than this is a jump between disjoint pieces
_ELE_MIN_M, _ELE_MAX_M = -450, 9000   # plausible on-foot range (Dead Sea → Everest)


def _haversine_m(a, b) -> float:
    dx = (b[0] - a[0]) * math.cos(math.radians((a[1] + b[1]) / 2)) * 111320.0
    dy = (b[1] - a[1]) * 110540.0
    return math.hypot(dx, dy)


def _resample(coords: list[list[float]], cum: list[float], n: int) -> list[list[float]]:
    """n positions evenly spaced by cumulative distance along the path."""
    total = cum[-1]
    out, j = [], 0
    for k in range(n):
        td = total * k / (n - 1)
        while j < len(coords) - 2 and cum[j + 1] < td:
            j += 1
        seg = cum[j + 1] - cum[j]
        f = 0.0 if seg <= 0 else (td - cum[j]) / seg
        out.append([coords[j][0] + (coords[j + 1][0] - coords[j][0]) * f,
                    coords[j][1] + (coords[j + 1][1] - coords[j][1]) * f])
    return out


def _clean_elevations(eles: list[float | None]) -> list[float | None]:
    """Drop implausible values, interpolate short None gaps, then median(5)+mean(3)
    smooth so single-sample DEM spikes / tile-edge artifacts don't show as outliers."""
    e = [(v if (v is not None and _ELE_MIN_M < v < _ELE_MAX_M) else None) for v in eles]
    n = len(e)
    i = 0
    while i < n:
        if e[i] is None:
            j = i
            while j < n and e[j] is None:
                j += 1
            lo, hi = i - 1, j
            if lo >= 0 and hi < n and (j - i) <= 6:
                a, b = e[lo], e[hi]
                for k in range(i, j):
                    e[k] = a + (b - a) * (k - lo) / (hi - lo)
            i = j
        else:
            i += 1

    def _win(seq, w, fn):
        r = w // 2
        out = []
        for k in range(len(seq)):
            vals = [seq[m] for m in range(max(0, k - r), min(len(seq), k + r + 1))
                    if seq[m] is not None]
            out.append(fn(vals) if vals else None)
        return out

    def _median(v):
        s = sorted(v)
        m = len(s) // 2
        return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2

    e = _win(e, 5, _median)                       # kill isolated spikes
    e = _win(e, 3, lambda v: sum(v) / len(v))     # gentle smooth
    return e


def build_profile(coords: list[list[float]], true_distance_m: float | None = None) -> dict:
    """Resample evenly, DEM-sample, de-spike → distance/elevation series + gain/loss."""
    empty = {"points": [], "distance_m": round(true_distance_m or 0),
             "gain_m": 0, "loss_m": 0, "min_m": None, "max_m": None}
    if len(coords) < 2:
        return empty

    cum = [0.0]
    for i in range(1, len(coords)):
        d = _haversine_m(coords[i - 1], coords[i])
        cum.append(cum[-1] + (d if d < _GAP_M else 0.0))
    total = cum[-1]
    if total <= 0:
        return empty

    n = min(_PROFILE_MAX_N, max(_PROFILE_MIN_N, int(total / 500)))
    samples = _resample(coords, cum, n)
    eles = _clean_elevations(elevation_sampler.sample_elevations(samples))

    scale = (true_distance_m / total) if (true_distance_m and total > 0) else 1.0
    points = [{"d_km": round(total * k / (n - 1) * scale / 1000, 3),
               "ele_m": (round(eles[k], 1) if eles[k] is not None else None)}
              for k in range(n)]

    gain = loss = 0.0
    prev = None
    for v in eles:
        if v is None:
            prev = None          # break gain accumulation across an off-DEM gap
            continue
        if prev is not None:
            de = v - prev
            gain += de if de > 0 else 0
            loss += -de if de < 0 else 0
        prev = v
    evals = [v for v in eles if v is not None]
    return {
        "points": points,
        "distance_m": round(true_distance_m if true_distance_m else total),
        "gain_m": round(gain), "loss_m": round(loss),
        "min_m": round(min(evals), 1) if evals else None,
        "max_m": round(max(evals), 1) if evals else None,
    }


def precompute_route_profiles() -> int:
    """Cache an elevation profile into every long-trail section that lacks one, so
    the detail panel opens instantly (DEM-sampled once at startup, not per click).

    Idempotent: skips sections that already carry a `profile`. Returns the number
    of section profiles newly computed. Runs in a background thread at startup
    (see main.lifespan); the global z0-7 DEM means every section gets one."""
    d = Path(settings.map_data_dir) / "routes"
    if not d.exists():
        return 0
    n = 0
    for f in sorted(d.glob("*.json")):
        try:
            idx = json.loads(f.read_text())
        except Exception:
            continue
        changed = False
        for sec in idx.get("sections", []):
            if sec.get("profile") is None and sec.get("coords"):
                sec["profile"] = build_profile(sec["coords"], sec.get("distance_m"))
                changed = True
                n += 1
        if changed:
            f.write_text(json.dumps(idx, separators=(",", ":")))
    if n:
        logger.info("Precomputed %d long-trail section elevation profiles", n)
    return n
