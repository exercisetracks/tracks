# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A whole-body stretch is never followed by another (space_whole_body).

A two-set Downward Dog used to play as the same pose twice back to back.
Its second set now bridges the sides of the next one-sided stretch, which
is the transition a good flow uses.
"""
from app.calculators.flexibility import space_whole_body

DOG = {"name": "Downward Dog", "each_side": False, "sets": 2, "muscles": ["hamstrings", "calves", "upper_back", "lats"]}
LUNGE = {"name": "Runner's Lunge", "each_side": True, "sets": 1, "muscles": ["hip_flexors"]}
FOLD = {"name": "Forward Fold", "each_side": False, "sets": 1, "muscles": ["hamstrings"]}


def _shape(steps):
    return [(s["name"], s.get("side"), s["sets"]) for s in steps]


def test_the_second_set_bridges_the_two_sides_of_a_lunge():
    assert _shape(space_whole_body([DOG, LUNGE, FOLD])) == [
        ("Downward Dog", None, 1), ("Runner's Lunge", "left", 1),
        ("Downward Dog", None, 1), ("Runner's Lunge", "right", 1), ("Forward Fold", None, 1),
    ]


def test_with_no_sides_to_bridge_it_follows_the_next_stretch():
    assert _shape(space_whole_body([DOG, FOLD])) == [
        ("Downward Dog", None, 1), ("Forward Fold", None, 1), ("Downward Dog", None, 1),
    ]


def test_alone_it_is_held_once_rather_than_twice_in_a_row():
    assert _shape(space_whole_body([DOG])) == [("Downward Dog", None, 1)]


def test_a_split_side_is_one_hold_not_two():
    left = space_whole_body([DOG, LUNGE])[1]
    assert left["each_side"] is False and left["side"] == "left"
