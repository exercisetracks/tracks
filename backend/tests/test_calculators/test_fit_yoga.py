# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What a stretch flow looks like on the watch: names, sides, sets.

A five-stretch Full-Body Unwind reached a fenix 6X as Low Lunge, Supine Spinal
Twist, Thunderbolt, Supine Spinal Twist, Mountain — every stretch but one under
another pose's name, each one-sided hold doubled into a single step with no cue
to switch sides, and every set written out again. These decode the file the
encoder writes, so they check what the watch is handed, not the step dicts on
the way there.
"""
from garmin_fit_sdk import Decoder, Stream

from app.calculators.fit_workout import _emit_yoga_steps, generate_strength_workout_fit


def _hold(name, seconds=60, sets=1, each_side=False, cat=None, sub=None, **kw):
    return {"type": "mobility_exercise", "name": name, "duration_seconds": seconds,
            "sets": sets, "each_side": each_side, "garmin_category": cat, "garmin_subtype": sub, **kw}


def _decode(holds):
    data = generate_strength_workout_fit("Flow", holds, workout_id=7,
                                         time_created=1756000000000, workout_type="flexibility")
    msgs, errors = Decoder(Stream.from_byte_array(bytearray(data))).read()
    assert not errors
    titles = {(t["exercise_category"], t["exercise_name"]): t["wkt_step_name"]
              for t in msgs["exercise_title_mesgs"]}
    shown = []
    for s in msgs["workout_step_mesgs"]:
        if s["duration_type"] == "repeat_until_steps_cmplt":
            shown.append(("repeat", s["duration_value"], s["target_value"]))
        else:
            shown.append((titles.get((s.get("exercise_category"), s.get("exercise_name"))),
                          s["duration_value"] // 1000))
    return shown, msgs["workout_mesgs"][0]


def test_a_one_sided_stretch_is_one_hold_a_side_repeated_for_every_set():
    """Couch Stretch, 2 sets of 60 s a side: one 1:00 hold, run four times. The
    step change is the watch's cue to switch sides; before, it was two 2:00
    holds that read as two minutes a side."""
    shown, _ = _decode([_hold("Couch Stretch", 60, sets=2, each_side=True)])
    assert shown == [("Couch Stretch", 60), ("repeat", 0, 4)]


def test_a_single_hold_has_no_loop():
    shown, _ = _decode([_hold("Thunderbolt Pose", 60, cat="pose", sub=76)])
    assert shown == [("Thunderbolt Pose", 60)]


def test_a_stretch_with_no_garmin_pose_goes_out_under_its_own_name():
    """Two such stretches need two exercise numbers: the watch names a step by
    its (category, exercise_name), so one shared number would put the first
    name on both."""
    shown, _ = _decode([_hold("Lying Leg Cradle Stretch", 45, each_side=True),
                        _hold("Legs Up the Wall", 180)])
    assert shown == [("Lying Leg Cradle Stretch", 45), ("repeat", 0, 2),
                     ("Legs Up the Wall", 180)]


def test_a_garmin_pose_goes_out_under_garmins_name():
    """The Yoga app plays the animation only under its own pose name, so a
    stretch that is a Garmin pose keeps that name on the watch."""
    shown, _ = _decode([_hold("Kneeling Hip Flexor Stretch", 60, cat="pose", sub=41)])
    assert shown == [("Low Lunge with Knee Down Pose", 60)]


def test_a_pair_only_the_strength_app_animates_keeps_the_stretchs_name():
    """warm_up/37 is Garmin's STRETCH_CALF, an animation the Yoga app does not
    play — so the foot release mapped to it is not renamed after it."""
    shown, _ = _decode([_hold("Tennis Ball Foot Release", 60, cat="warm_up", sub=37)])
    assert shown == [("Tennis Ball Foot Release", 60)]


def test_the_same_stretch_twice_shares_one_title():
    steps, titles = _emit_yoga_steps([_hold("Couch Stretch"), _hold("Couch Stretch")])
    assert len(titles) == 1
    assert steps[0]["exercise_name"] == steps[1]["exercise_name"]


def test_a_one_sided_member_of_a_group_is_written_once_a_side_inside_one_loop():
    """No loop inside a loop: whether the watch runs one is unknown."""
    g = {"uid": "g", "kind": "repeat", "rounds": 3, "rest_seconds": 20}
    shown, _ = _decode([_hold("Couch Stretch", 30, sets=2, each_side=True, group=g),
                        _hold("Thunderbolt Pose", 30, cat="pose", sub=76, group=g)])
    assert shown == [("Couch Stretch", 30), ("Couch Stretch", 30), ("Thunderbolt Pose", 30),
                     (None, 20), ("repeat", 0, 3)]


def test_the_workouts_total_time_counts_every_run_of_a_loop():
    """Couch Stretch 4 × 1:00, then Thunderbolt 1:00: five minutes, not two."""
    _, workout = _decode([_hold("Couch Stretch", 60, sets=2, each_side=True),
                          _hold("Thunderbolt Pose", 60, cat="pose", sub=76)])
    assert workout["_total_workout_time"] == 300_000
    assert workout["_total_workout_time_b"] == 300_000
