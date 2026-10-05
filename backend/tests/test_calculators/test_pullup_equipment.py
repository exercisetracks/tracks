# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pull-ups, and the people who cannot do one.

Against the real exercise library, not a synthetic one: the bug was in the
library data itself. Pull-ups were tagged "bodyweight", so every user was
eligible for them — including the many with no bar to hang from and the many
who cannot yet lift their chin over one.

The fix is three parts, and these pin each: anything you hang from needs the
`pullup_bar` equipment, an unassisted pull-up is a difficulty only experienced
lifters reach, and every equipment set still gets its lats trained by
something it can actually do.
"""

import pytest

from app.calculators.strength_plan.exercises import _select_exercises
from app.calculators.strength_plan.leveling import experience_max_difficulty
from app.spec.library import EXERCISES

LIBRARY = {e["name"]: e for e in EXERCISES}

_HANGING = ("pull-up", "chin-up", "dead hang", "hanging ", "toes to bar")
BAR_ONLY = {n for n in LIBRARY if any(f in n.lower() for f in _HANGING)
            and "assisted pull-up" not in n.lower() or n == "Toes to Bar"}
# The machine version hangs from nothing: it has its own pad and weight stack.
BAR_ONLY.discard("Assisted Pull-Up")

UNASSISTED = {"Pull-Up", "Chin-Up", "Chin-up", "Weighted Pull-Up"}
LADDER = {"Scapular Pull-Up", "Negative Pull-Up", "Band-Assisted Pull-Up"}

PULL_SPLITS = ("ppl_pull", "upper_a", "upper_b", "full_body", "supp_upper_core")


def _picks(equipment, *, tier=3, max_difficulty=None, sport="strength", week=0):
    out = set()
    for split in PULL_SPLITS:
        out.update(_select_exercises(
            sport, tier, split, equipment, LIBRARY, [], max_exercises=8,
            max_difficulty=max_difficulty, week_num=week,
        ))
    return out


def _lats(names):
    return [n for n in names if "lats" in LIBRARY[n]["primary_muscles"]]


def test_everything_you_hang_from_asks_for_a_pull_up_bar():
    """A hang tagged "bodyweight" is offered to someone with nothing to hang from."""
    assert {"Pull-Up", "Chin-Up", "Dead Hang", "Hanging Leg Raise", "Toes to Bar"} <= BAR_ONLY
    for name in BAR_ONLY:
        assert LIBRARY[name]["equipment"] == ["pullup_bar"], name


@pytest.mark.parametrize("equipment", [
    ["bodyweight"],
    ["bodyweight", "dumbbell"],
    ["bodyweight", "band"],
    ["bodyweight", "dumbbell", "barbell", "cable", "machine", "kettlebell", "band"],
])
@pytest.mark.parametrize("tier", [1, 3, 5])
def test_no_bar_means_nothing_that_needs_one_is_scheduled(equipment, tier):
    for week in range(4):
        assert not _picks(equipment, tier=tier, week=week) & BAR_ONLY


@pytest.mark.parametrize("equipment", [
    ["bodyweight"],
    ["bodyweight", "dumbbell"],
    ["bodyweight", "band"],
])
def test_a_lifter_without_a_bar_still_trains_the_lats(equipment):
    """The gap the bar rule opens: no bar, and the vertical-pull slot has to be
    filled by something a home lifter owns."""
    picks = _picks(equipment)
    assert _lats(picks), picks


def test_a_brand_new_lifter_with_a_bar_gets_the_ladder_not_the_pull_up():
    """Brand-new lifters are capped at difficulty 2; an unassisted pull-up is
    a prerequisite they do not have."""
    cap = experience_max_difficulty("brand_new")
    picks = _picks(["bodyweight", "pullup_bar"], tier=2, max_difficulty=cap, sport="climbing")
    assert not picks & UNASSISTED
    assert picks & LADDER


def test_an_endurance_athlete_with_a_bar_is_not_handed_unassisted_pull_ups():
    """Endurance goals keep strength supplementary (tier 2); they get the
    scalable versions."""
    picks = _picks(["bodyweight", "pullup_bar"], tier=2, sport="running")
    assert not picks & UNASSISTED


def test_an_experienced_lifter_with_a_bar_still_gets_pull_ups():
    picks = _picks(["bodyweight", "pullup_bar"], tier=5, sport="climbing")
    assert picks & UNASSISTED


def test_the_ladder_needs_the_bar_too():
    """Scapular pulls and negatives are done hanging from the bar."""
    for name in LADDER:
        assert LIBRARY[name]["equipment"] == ["pullup_bar"], name
        assert LIBRARY[name]["difficulty"] <= 2, name
