# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Generic workout builders — for sports with no builders of their own
(paddling, golf, "other").

Simple activity-type workouts with intensity zone descriptions, using generic
Zone 2-4 labels. Swimming, rowing, hiking, skiing and climbing used to share
these; each now has its own module.
"""

from __future__ import annotations


def _generic_easy(duration_min: int, sport: str) -> list[dict]:
    return [{"type": "activity", "duration_min": duration_min, "intensity": "easy",
             "note": f"Easy {sport} · Zone 2 · conversational pace"}]


def _generic_aerobic(duration_min: int, sport: str) -> list[dict]:
    return [{"type": "activity", "duration_min": duration_min, "intensity": "aerobic",
             "note": f"Aerobic {sport} · Zone 3 · steady moderate effort"}]


def _generic_quality(duration_min: int, sport: str) -> list[dict]:
    warmup, cooldown = 12, 8
    main = max(10, duration_min - warmup - cooldown)
    return [
        {"type": "activity", "duration_min": warmup,   "intensity": "easy",    "note": "Warm-up"},
        {"type": "activity", "duration_min": main,     "intensity": "threshold","note": f"Quality {sport} · Zone 4 · comfortably hard"},
        {"type": "activity", "duration_min": cooldown, "intensity": "easy",    "note": "Cool-down"},
    ]


def _generic_long(duration_min: int, sport: str) -> list[dict]:
    return [{"type": "activity", "duration_min": duration_min, "intensity": "easy",
             "note": f"Long {sport} · easy steady effort · build aerobic base"}]
