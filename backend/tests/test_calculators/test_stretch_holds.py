# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Each stretch is held for what it is, not one length for all."""
from statistics import mean

from app.calculators.flexibility import StretchCandidate, hold_seconds, to_step
from app.spec.library import STRETCHES


def _candidate(x: dict, **over) -> StretchCandidate:
    fields = dict(
        name=x["name"], primary_muscles=tuple(x["primary_muscles"]),
        movement_pattern=x["movement_pattern"], difficulty=x["difficulty"],
        duration_per_side_sec=x["duration_per_side_sec"], sets=x["sets"],
        each_side=x["each_side"], description="", garmin_category=None,
        garmin_subtype=None, position=x["position"],
    )
    fields.update(over)
    return StretchCandidate(**fields)


def _by_name(name: str) -> StretchCandidate:
    return _candidate(next(x for x in STRETCHES if x["name"] == name))


def test_the_moderate_holds_average_about_a_minute():
    """Moderate is the plan's level: a minute a stretch, as asked."""
    assert 55 <= mean(hold_seconds(_candidate(x)) for x in STRETCHES) <= 65


def test_a_long_floor_stretch_outlasts_a_strength_pose_and_a_wrist_stretch():
    """The bug: every stretch played the same length. Pigeon needs longer than chair pose."""
    assert hold_seconds(_by_name("Pigeon Pose")) > hold_seconds(_by_name("Chair Pose"))
    assert hold_seconds(_by_name("Seated Forward Fold")) > hold_seconds(_by_name("Wrist Flexor Stretch"))
    assert hold_seconds(_by_name("Corpse Pose (Savasana)")) == 180


def test_a_custom_stretch_keeps_its_own_time():
    """Someone who wrote their own stretch chose its length; the rule does not override it."""
    c = _candidate(STRETCHES[0], is_custom=True, duration_per_side_sec=25)
    assert to_step(c)["duration_seconds"] == 25
