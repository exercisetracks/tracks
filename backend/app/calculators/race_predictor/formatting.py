# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Time and pace formatting helpers used across the race predictor.

Pure string formatters with no physics — kept in one place so running,
cycling, and swimming all render paces/times identically.
"""
from __future__ import annotations


def format_time(seconds: float) -> str:
    """Format seconds as H:MM:SS or MM:SS."""
    s = int(round(seconds))
    h, rem = divmod(s, 3600)
    m, sc  = divmod(rem, 60)
    if h:
        return f"{h}:{m:02d}:{sc:02d}"
    return f"{m}:{sc:02d}"


def _fmt_pace(sec_per_km: float) -> str:
    """Format a running/cycling pace (sec per km) as M:SS/km."""
    m, sc = int(sec_per_km // 60), int(sec_per_km % 60)
    return f"{m}:{sc:02d}/km"


def format_swim_pace(css_sec_per_100m: float, intensity: float = 1.0) -> str:
    """Format a swim pace (adjusted by intensity factor) as M:SS / 100m."""
    adjusted = css_sec_per_100m * intensity
    m  = int(adjusted // 60)
    sc = int(adjusted % 60)
    return f"{m}:{sc:02d}/100m"
