# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training zone calculators.

HR zones:    Friel 7-zone model (from "The Triathlete's Training Bible")
             Boundaries are percentages of LTHR (lactate threshold HR).
             Separate boundaries are provided for running vs cycling because
             cycling LTHR sits ~5–7 bpm lower relative to max HR than running.

Power zones: Coggan 7-zone model (from "Training and Racing with a Power Meter")
             Boundaries are percentages of FTP.

The boundary percentages are NOT defined here. They live in spec/zones.yaml and
are generated into app/spec/zones.py, so the Python backend, the JS frontend,
and the Kotlin mobile core all read the same numbers — see spec/codegen.py.
This module is the Python-side arithmetic over those tables.
"""

from app.spec.zones import HR_MODELS, POWER_MODELS

_FRIEL_RUN_HR = HR_MODELS["friel_lthr_run"]["zones"]
_FRIEL_BIKE_HR = HR_MODELS["friel_lthr_bike"]["zones"]
_COGGAN_POWER = POWER_MODELS["coggan_ftp"]["zones"]


# ─────────────────────────────────────────
# Public API
# ─────────────────────────────────────────

def hr_zones(lthr: int, sport: str = "running") -> list[dict]:
    """
    Return Friel HR zone boundaries (in bpm) for the given LTHR.

    Args:
        lthr:  Lactate threshold heart rate in bpm.
        sport: 'running' (default) or 'cycling'. Affects Zone 1/2 boundaries.

    Returns:
        List of 7 dicts with keys: number, name, description, min_bpm, max_bpm.
        max_bpm is None for Zone 5c (no upper bound).
    """
    defs = _FRIEL_BIKE_HR if sport == "cycling" else _FRIEL_RUN_HR
    result = []
    for z in defs:
        min_bpm = round(lthr * z["min_pct"])
        max_bpm = round(lthr * z["max_pct"]) - 1 if z["max_pct"] is not None else None
        result.append({
            "number":      z["number"],
            "name":        z["name"],
            "description": z["description"],
            "min_bpm":     min_bpm,
            "max_bpm":     max_bpm,
        })
    return result


def power_zones(ftp: int) -> list[dict]:
    """
    Return Coggan power zone boundaries (in watts) for the given FTP.

    Returns:
        List of 7 dicts with keys: number, name, description, min_watts, max_watts.
        max_watts is None for Zone 7 (no upper bound).
    """
    result = []
    for z in _COGGAN_POWER:
        min_watts = round(ftp * z["min_pct"])
        max_watts = round(ftp * z["max_pct"]) - 1 if z["max_pct"] is not None else None
        result.append({
            "number":      z["number"],
            "name":        z["name"],
            "description": z["description"],
            "min_watts":   min_watts,
            "max_watts":   max_watts,
        })
    return result


def hr_zone_for_bpm(bpm: int, lthr: int, sport: str = "running") -> int | None:
    """Return the zone number (1–7) for a given HR reading."""
    for zone in hr_zones(lthr, sport):
        lo = zone["min_bpm"]
        hi = zone["max_bpm"]
        if bpm >= lo and (hi is None or bpm <= hi):
            return zone["number"]
    return None


def power_zone_for_watts(watts: int, ftp: int) -> int | None:
    """Return the zone number (1–7) for a given power reading."""
    for zone in power_zones(ftp):
        lo = zone["min_watts"]
        hi = zone["max_watts"]
        if watts >= lo and (hi is None or watts <= hi):
            return zone["number"]
    return None
