# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Static lookup tables and tuning constants for the coaching engine.

Pure data only — no logic. Centralised here so the calibration numbers
(decay rates, TSS-per-hour, sport speeds, HR zones, periodisation phases,
descriptive labels) live in one place and can be reviewed at a glance.
"""

from __future__ import annotations

from math import exp

# ── CTL/ATL exponential decay constants ────────────────────────────
# Standard Banister model time constants: 42-day chronic, 7-day acute.
_CTL_DECAY = exp(-1 / 42)
_ATL_DECAY = exp(-1 / 7)

# Approximate TSS per hour at each intensity tier (used for projections)
_TSS_PER_HOUR = {
    "rest":       0,
    "walk":       20,
    "easy":       45,
    "aerobic":    60,
    "tempo":      80,
    "threshold": 100,
    "quality":   115,
}

# Sports that produce distance-based output in km
_DISTANCE_SPORTS = {
    "running", "trail_running", "road_biking", "cycling", "mountain_biking",
    "gravel_cycling", "virtual_cycling", "indoor_cycling", "e_biking", "walking",
    "hiking", "treadmill_running",
}

# Rough km/h per intensity for distance estimation
_SPORT_SPEED_KMH: dict[str, dict[str, float]] = {
    "running":          {"easy": 8.0,  "aerobic": 9.5,  "tempo": 12.0, "threshold": 13.5, "quality": 14.5},
    "trail_running":    {"easy": 7.0,  "aerobic": 8.5,  "tempo": 10.0, "threshold": 11.5, "quality": 12.5},
    "treadmill_running":{"easy": 8.0,  "aerobic": 9.5,  "tempo": 12.0, "threshold": 13.5, "quality": 14.5},
    "cycling":          {"easy": 22.0, "aerobic": 26.0, "tempo": 30.0, "threshold": 34.0, "quality": 36.0},
    "road_biking":      {"easy": 22.0, "aerobic": 26.0, "tempo": 30.0, "threshold": 34.0, "quality": 36.0},
    "mountain_biking":  {"easy": 15.0, "aerobic": 18.0, "tempo": 21.0, "threshold": 24.0, "quality": 26.0},
    "gravel_cycling":   {"easy": 20.0, "aerobic": 24.0, "tempo": 28.0, "threshold": 32.0, "quality": 34.0},
    "walking":          {"easy": 5.0,  "aerobic": 5.5},
    "hiking":           {"easy": 4.0,  "aerobic": 5.0},
}

# HR zone targets (% of LTHR) by intensity tier
_ZONE_BY_TIER = {
    "rest":       None,
    "walk":       (0.65, 0.75),
    "easy":       (0.72, 0.82),
    "aerobic":    (0.82, 0.89),
    "tempo":      (0.89, 0.96),
    "threshold":  (0.96, 1.02),
    "quality":    (1.00, 1.06),
}

# Friel periodisation phases keyed by weeks-to-event (first match wins, so
# keep ordered ascending; the 999 entry is the catch-all "far out" base phase)
_PHASE_WEEKS = [
    (4,  "taper"),
    (8,  "peak"),
    (12, "build"),
    (999, "base"),
]

# Human-readable intensity labels for workout descriptions
_TIER_LABEL = {
    "rest":       "Rest day",
    "walk":       "Easy walk",
    "easy":       "Easy / Recovery",
    "aerobic":    "Aerobic base",
    "tempo":      "Tempo",
    "threshold":  "Threshold",
    "quality":    "Quality / Hard",
}

# Suggested terrain phrasing per sport (cosmetic, used in descriptions)
_SPORT_TERRAIN = {
    "running":       "road or trail",
    "trail_running": "trail",
    "mountain_biking": "dirt trail or singletrack",
    "road_biking":   "road",
    "cycling":       "road or indoor",
    "gravel_cycling":"gravel road",
    "hiking":        "trail",
    "walking":       "flat route",
    "rock_climbing": "gym or crag",
    "snowboarding":  "groomed or off-piste",
    "swimming":      "pool or open water",
}
