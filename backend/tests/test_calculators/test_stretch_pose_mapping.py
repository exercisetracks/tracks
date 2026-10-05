# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A library stretch is only mapped to a Garmin pose that *is* that stretch.

The mapping decides two things at once: whether generated flows may pick the
stretch at all (they take only what the watch's Yoga app animates — see
api/flexibility/generation._build_candidates), and what the watch calls the
step, because the Yoga app plays an animation only under Garmin's own pose
name (fit_workout._resolve_yoga_keys). So a "nearest pose" mapping is not a
harmless approximation: Legs Up the Wall mapped to Mountain put a standing pose
on the watch, named Mountain Pose, for a three-minute inversion. Couch Stretch
went out as Low Lunge, and Lying Leg Cradle and Lying Piriformis both went out
as Supine Spinal Twist.

A stretch with no identical Garmin pose carries no mapping, so it drops out of
generated flows and, in a flow built by hand, reaches the watch under its own
name with no animation.

This catches the worst case mechanically: a mapping that shares no word with
the pose it names. It cannot catch a near miss that shares a word (Cobra Pose
to Baby Cobra, Cat-Cow Flow to Cat), which still needs a reviewer to look at
what the pose actually is.
"""
import re

from app.calculators.garmin_animations import get_animation, is_yoga_animatable
from app.calculators.fit_workout import _CATEGORY_INT
from app.spec.library import STRETCHES

# Same pose, different name, checked by hand: the kneeling hip-flexor stretch
# is a low lunge with the back knee down, the butterfly is baddha konasana, and
# the strap only lengthens the reach in supta padangusthasana.
REVIEWED_ALIASES = {
    "Butterfly Stretch": "bound_angle",
    "Kneeling Hip Flexor Stretch": "low_lunge_with_knee_down",
    "Lying Hamstring Stretch with Strap": "reclined_hand_to_foot",
}

_FILLER = {"pose", "stretch", "the", "with", "a", "to", "of", "on", "and"}


def _words(text: str) -> set[str]:
    out = set()
    for w in re.split(r"[^a-z0-9]+", text.lower()):
        if w and w not in _FILLER:
            # "Hero" against HEROS, "Knees" against KNEE.
            out.add(w[:-1] if w.endswith("s") and len(w) > 3 else w)
    return out


def _yoga_pose(stretch: dict):
    cat = _CATEGORY_INT.get((stretch.get("garmin_category") or "").lower())
    sub = stretch.get("garmin_subtype")
    if cat is None or sub is None or not is_yoga_animatable(cat, sub):
        return None
    return get_animation(cat, sub)


def test_every_stretch_sent_as_a_garmin_pose_shares_its_name_or_is_a_reviewed_alias():
    """Fails on a nearest-pose mapping like Legs Up the Wall → Mountain."""
    wrong = []
    for s in STRETCHES:
        pose = _yoga_pose(s)
        if pose is None:
            continue
        if REVIEWED_ALIASES.get(s["name"]) == pose.name:
            continue
        if not _words(s["name"]) & _words(pose.name):
            wrong.append(f"{s['name']} → {pose.name}")
    assert not wrong, wrong


def test_the_stretches_garmin_has_no_pose_for_carry_no_mapping():
    """The ones that reached a watch under another pose's name and animation.
    Each is a stretch Garmin's Yoga app has no pose for, so none may have one."""
    by_name = {s["name"]: s for s in STRETCHES}
    for name in ("Couch Stretch", "Lying Leg Cradle Stretch", "Lying Piriformis Stretch",
                 "Legs Up the Wall (Viparita Karani)"):
        assert _yoga_pose(by_name[name]) is None, name
        assert by_name[name]["has_animation"] is False, name


def test_every_reviewed_alias_is_still_the_mapping_it_was_reviewed_as():
    """An alias is a judgement about one pose. If the mapping moves, the
    judgement no longer covers it and the stretch needs looking at again."""
    by_name = {s["name"]: s for s in STRETCHES}
    for name, pose in REVIEWED_ALIASES.items():
        assert _yoga_pose(by_name[name]).name == pose, name
