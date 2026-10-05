# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the strength and mobility plan corpus.

The backend's own planners, run in-process: calculators/strength_plan/ (the
generator and every helper it leans on) and calculators/flexibility.py. The
phone's port in mobile/core (com.tracks.core.strengthplan) replays every case
and must agree exactly — including the order a shuffle leaves a list in, which
is why the corpus pins Python's `random.Random` directly as well.

Regenerable, like the metrics corpus: the Python stays the independent oracle.
Rerun after changing any of those files, and the Kotlin suite says whether the
port still agrees.

Synthetic and deterministic. The exercise and stretch libraries here are
invented rows shaped like the real ones, chosen to reach every branch: each
movement slot, the stretch-category leak, custom and preferred entries, missing
relevance maps, null difficulty, unilateral and vertical-pull names, every
injury band. Dates are fixed, never "today".

Touches no database, but importing `app` reads config:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_strength_plan_fixtures.py
"""

from __future__ import annotations

import json
import sys
from dataclasses import asdict
from datetime import date, timedelta
from pathlib import Path
from random import Random
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators import flexibility as flex  # noqa: E402
from app.calculators.strength_plan import generator as gen  # noqa: E402
from app.calculators.strength_plan.archetypes import ARCHETYPES, select_archetype  # noqa: E402
from app.calculators.strength_plan.exercises import _select_exercises  # noqa: E402
from app.calculators.strength_plan.flow_archetypes import FLOW_ARCHETYPES, select_flow_archetype  # noqa: E402
from app.calculators.strength_plan.leveling import infer_experience_suggestion  # noqa: E402
from app.calculators.strength_plan.loads import estimate_1rm, round_weight_for_equipment  # noqa: E402
from app.calculators.strength_plan.mobility import mobility_target_muscles  # noqa: E402

OUT = Path(__file__).parent / "fixtures" / "strength_plan.json"


def _plain(v):
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, dict):
        return {("null" if k is None else k): _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, (set, frozenset)):
        return sorted(_plain(x) for x in v)
    return v


# ── Libraries ────────────────────────────────────────────────────────────────

def _ex(name, pattern, primary, equipment, difficulty=2, compound=True, animates=True,
        relevance=None, secondary=(), category=None, subtype=None, cues=None, **extra):
    row = {
        "name": name, "movement_pattern": pattern, "primary_muscles": list(primary),
        "secondary_muscles": list(secondary), "equipment": list(equipment),
        "difficulty": difficulty, "is_compound": compound, "has_animation": animates,
        "garmin_category": category, "garmin_subtype": subtype,
        "cues": cues if cues is not None else [f"{name} cue {i}" for i in range(1, 5)],
    }
    if relevance is not None:
        row["sport_relevance"] = relevance
    row.update(extra)
    return row


R = lambda **k: k  # noqa: E731 — a relevance map
LIBRARY = [
    _ex("Back Squat", "squat", ["quads", "glutes"], ["barbell"], 3, relevance=R(running=4, cycling=5, strength=5), category="squat", subtype=1),
    _ex("Front Squat", "squat", ["quads"], ["barbell"], 4, relevance=R(cycling=5, strength=4)),
    _ex("Goblet Squat", "squat", ["quads", "glutes"], ["dumbbell", "kettlebell"], 1, relevance=R(running=4, generic=3)),
    _ex("Air Squat", "squat", ["quads"], ["bodyweight"], 1, relevance=R(strength=2)),
    _ex("Leg Press", "squat", ["quads", "glutes"], ["machine"], 1, relevance=R(cycling=4, strength=3)),
    _ex("Bulgarian Split Squat", "squat", ["quads", "glutes"], ["dumbbell", "bodyweight"], 2, relevance=R(running=5, hiking=5, strength=4)),
    _ex("Pistol Squat", "squat", ["quads"], ["bodyweight"], 5, relevance=R(running=3)),
    _ex("Step-Up", "squat", ["quads", "glutes"], ["dumbbell", "bodyweight"], 1, relevance=R(hiking=5, running=4)),
    _ex("Box Jump", "plyometric", ["quads", "calves"], ["bodyweight"], 2, relevance=R(running=5)),
    _ex("Squat Jump", "plyometric", ["quads"], ["bodyweight"], 2, relevance=R(running=4)),
    _ex("Deadlift", "hinge", ["hamstrings", "glutes"], ["barbell"], 3, secondary=["lower_back"], relevance=R(strength=5, cycling=4, running=4)),
    _ex("Romanian Deadlift", "hinge", ["hamstrings", "glutes"], ["barbell", "dumbbell"], 2, relevance=R(running=5, strength=4)),
    _ex("Single Leg Romanian Deadlift", "hinge", ["hamstrings", "glutes"], ["dumbbell", "kettlebell"], 3, relevance=R(running=5)),
    _ex("Kettlebell Swing", "hinge", ["glutes", "hamstrings"], ["kettlebell"], 2, relevance=R(mountain_biking=5, generic=4)),
    _ex("Glute Bridge", "hinge", ["glutes"], ["bodyweight"], 1, relevance={}),
    _ex("Power Clean", "hinge", ["glutes", "quads"], ["barbell"], 5, relevance=R(strength=5, mountain_biking=5)),
    _ex("Bench Press", "push", ["chest"], ["barbell"], 3, secondary=["triceps"], relevance=R(strength=5, paddling=3)),
    _ex("Dumbbell Bench Press", "push", ["chest"], ["dumbbell"], 2, relevance=R(strength=4, climbing=3)),
    _ex("Push-Up", "push", ["chest"], ["bodyweight"], 1, relevance=R(climbing=4, generic=3)),
    _ex("Incline Dumbbell Press", "push", ["chest", "front_delts"], ["dumbbell"], 2, relevance=R(strength=3)),
    _ex("Overhead Press", "push", ["shoulders"], ["barbell"], 3, relevance=R(strength=5, paddling=4)),
    _ex("Dumbbell Shoulder Press", "push", ["front_delts", "side_delts"], ["dumbbell"], 2, relevance=R(strength=4, climbing=4)),
    _ex("Pike Push-Up", "push", ["shoulders"], ["bodyweight"], 3, relevance=None, animates=False),
    _ex("Cable Fly", "isolation", ["chest"], ["cable"], 1, compound=False, relevance=R(strength=2)),
    _ex("Triceps Pushdown", "isolation", ["triceps"], ["cable"], 1, compound=False, relevance=R(strength=3)),
    _ex("Dips", "push", ["triceps", "chest"], ["bodyweight"], 3, relevance=R(climbing=3, strength=4)),
    _ex("Pull-Up", "pull", ["lats"], ["pullup_bar"], 4, secondary=["biceps"], relevance=R(climbing=5, strength=5, paddling=4)),
    _ex("Chin-Up", "pull", ["lats", "biceps"], ["pullup_bar"], 4, relevance=R(climbing=5, strength=4)),
    _ex("Negative Pull-Up", "pull", ["lats", "biceps"], ["pullup_bar"], 2, relevance=R(climbing=3, strength=2)),
    _ex("Band Lat Pulldown", "pull", ["lats"], ["band"], 1, relevance=R(strength=2, paddling=3)),
    _ex("Lat Pulldown", "pull", ["lats"], ["machine", "cable"], 1, relevance=R(strength=4, paddling=5, climbing=3)),
    _ex("Barbell Row", "pull", ["upper_back", "lats"], ["barbell"], 3, relevance=R(strength=5, paddling=5)),
    _ex("Single Arm Dumbbell Row", "pull", ["upper_back", "lats"], ["dumbbell"], 2, relevance=R(strength=4, paddling=4)),
    _ex("Seated Cable Row", "pull", ["mid_back"], ["cable", "machine"], 1, relevance=R(paddling=5, cycling=3)),
    _ex("Inverted Row", "pull", ["upper_back"], ["barbell"], 2, relevance=R(climbing=4, generic=3)),
    _ex("Face Pull", "pull", ["rear_delts"], ["cable", "band"], 1, compound=False, relevance=R(climbing=4, paddling=4)),
    _ex("Biceps Curl", "isolation", ["biceps"], ["dumbbell", "barbell"], 1, compound=False, relevance=R(strength=3, climbing=3)),
    _ex("Hammer Curl", "pull", ["biceps", "brachialis"], ["dumbbell"], 1, compound=False, relevance=R(climbing=4)),
    _ex("Shrug", "pull", ["traps"], ["dumbbell", "barbell"], 1, relevance=R(strength=2)),
    _ex("Farmer Carry", "carry", ["forearms", "traps"], ["dumbbell", "kettlebell"], 1, relevance=R(climbing=4, generic=4)),
    _ex("Dead Hang", "isometric", ["forearms"], ["pullup_bar"], 1, relevance=R(climbing=5)),
    _ex("Standing Calf Raise", "isolation", ["calves"], ["bodyweight", "dumbbell"], 1, compound=False, relevance=R(running=5, hiking=4)),
    _ex("Seated Calf Raise", "isolation", ["calves"], ["machine"], 1, compound=False, relevance=R(running=4, cycling=3)),
    _ex("Lateral Raise", "isolation", ["side_delts"], ["dumbbell", "cable"], 1, compound=False, relevance=R(strength=3)),
    _ex("Reverse Fly", "isolation", ["rear_delts"], ["dumbbell"], 1, compound=False, relevance=R(paddling=3)),
    _ex("Clamshell", "isolation", ["hip_abductors"], ["band", "bodyweight"], 1, compound=False, relevance=R(running=5)),
    _ex("Side Plank", "isometric", ["hip_abductors", "obliques"], ["bodyweight"], 2, compound=False, relevance=R(running=4, hiking=3)),
    _ex("Plank", "isometric", ["core"], ["bodyweight"], 1, compound=False, relevance=R(generic=4, running=3)),
    _ex("Dead Bug", "isolation", ["abs"], ["bodyweight"], 1, compound=False, relevance=R(running=4)),
    _ex("Hollow Hold", "isometric", ["abs", "core"], ["bodyweight"], 2, compound=False, animates=False, relevance=R(climbing=4)),
    _ex("Russian Twist", "rotation", ["obliques"], ["bodyweight", "dumbbell"], 1, relevance=R(paddling=5)),
    _ex("Pallof Press", "rotation", ["core"], ["cable", "band"], 2, relevance=R(paddling=4, running=3)),
    _ex("Snatch", "hinge", ["glutes"], ["barbell"], 5, relevance=R(strength=5)),
    # Stretches that leaked into the exercise library: never strength work.
    _ex("Cat Cow", "isolation", ["core"], ["bodyweight"], 1, category="warm_up", relevance=R(running=5)),
    _ex("Worlds Greatest Stretch", "squat", ["quads", "hip_flexors"], ["bodyweight"], 1, category="move", relevance=R(running=5)),
    # A relevance map whose only entry is null; a row with no cues at all.
    _ex("Hip Thrust", "hinge", ["glutes"], ["barbell", "bodyweight"], 2, relevance={"running": None}, cues=[]),
    # Nulls where the real custom-exercise rows can carry them.
    _ex("Reverse Nordic", "isolation", ["quads"], ["bodyweight"], None, compound=None, relevance=None),
]
CUSTOM = {
    "name": "My Sled Push", "primary_muscles": ["quads", "glutes"], "secondary_muscles": [],
    "equipment": ["sled"], "movement_pattern": "squat", "is_compound": None, "difficulty": None,
    "garmin_category": None, "garmin_subtype": None, "has_animation": False, "_is_custom": True,
}


def library(with_custom=False):
    lib = {row["name"]: dict(row) for row in LIBRARY}
    if with_custom:
        lib[CUSTOM["name"]] = dict(CUSTOM)
    return lib


def _st(name, primary, pattern="static_stretch", difficulty=1, dur=30, sets=1, each_side=False,
        position=None, equipment=("bodyweight",), preferred=False, custom=False, cues=(), breath=None,
        category="warm_up", subtype=None, description=None):
    return flex.StretchCandidate(
        name=name, primary_muscles=tuple(primary), movement_pattern=pattern, difficulty=difficulty,
        duration_per_side_sec=dur, sets=sets, each_side=each_side,
        description=description if description is not None else f"{name}.",
        garmin_category=category, garmin_subtype=subtype, is_custom=custom, preferred=preferred,
        secondary_muscles=(), equipment=tuple(equipment), cues=tuple(cues), breath_cue=breath,
        position=position,
    )


STRETCHES = [
    _st("Low Lunge", ["hip_flexors", "quads"], "yoga_pose", 1, 45, 1, True, "kneeling", cues=("Sink the hips",), breath="Exhale down", subtype=12),
    _st("Couch Stretch", ["hip_flexors"], "static_stretch", 2, 60, 1, True, "kneeling", equipment=("wall",)),
    _st("Standing Quad Stretch", ["quads"], "static_stretch", 1, 30, 1, True, "standing"),
    _st("Leg Swings", ["hip_flexors", "hamstrings"], "dynamic_stretch", 1, 20, 1, True, "standing"),
    _st("Hamstring Strap Stretch", ["hamstrings"], "static_stretch", 1, 45, 2, True, "supine", equipment=("strap",)),
    _st("Seated Forward Fold", ["hamstrings", "lower_back"], "static_stretch", 2, 60, 1, False, "seated"),
    _st("PNF Hamstring", ["hamstrings"], "pnf_stretch", 3, 30, 3, True, None),
    _st("Pigeon Pose", ["glutes", "hip_flexors"], "yoga_pose", 2, 60, 1, True, "prone"),
    _st("Figure Four", ["glutes"], "static_stretch", 1, 45, 1, True, "supine"),
    _st("Glute Foam Roll", ["glutes"], "myofascial_release", 1, 60, 1, True, "seated", equipment=("foam_roller",)),
    _st("Child's Pose", ["lower_back", "lats"], "yoga_pose", 1, 60, 1, False, "kneeling"),
    _st("Supine Twist", ["lower_back", "obliques"], "static_stretch", 1, 45, 1, True, "supine"),
    _st("Thread the Needle", ["thoracic_spine", "upper_back"], "yoga_pose", 2, 30, 1, True, "kneeling"),
    _st("Open Book", ["thoracic_spine"], "dynamic_stretch", 1, 30, 1, True, "supine"),
    _st("Doorway Chest Stretch", ["chest", "shoulders"], "static_stretch", 1, 30, 1, True, "standing", equipment=("wall",)),
    _st("Cross Body Shoulder", ["shoulders"], "static_stretch", 1, 30, 1, True, "standing"),
    _st("Arm Circles", ["shoulders"], "dynamic_stretch", 1, 20, 1, False, "standing"),
    _st("Wall Calf Stretch", ["calves"], "static_stretch", 1, 30, 1, True, "standing", equipment=("wall",)),
    _st("Downward Dog", ["calves", "hamstrings", "shoulders"], "yoga_pose", 2, 45, 1, False, None),
    _st("Tree Pose", ["glutes", "calves"], "balance_pose", 2, 30, 1, True, "standing"),
    _st("Wrist Flexor Stretch", ["forearms"], "static_stretch", 1, 30, 1, True, "kneeling"),
    _st("Lat Band Stretch", ["lats"], "static_stretch", 1, 30, 1, True, "standing", equipment=("band",)),
    _st("Cat-Cow", ["upper_back", "lower_back"], "dynamic_stretch", 1, 30, 1, False, "kneeling"),
    _st("Neck Tilt", ["neck"], "static_stretch", 1, 20, 1, True, "seated"),
    _st("My Hip Routine", ["hip_flexors", "glutes"], "static_stretch", 3, 0, 0, False, "supine", custom=True, description=""),
    _st("Favourite Calf Drop", ["calves"], "balance_pose", 3, 40, 2, True, "standing", equipment=("strap",), preferred=True),
]


def _stretch_json(c):
    d = asdict(c)
    return _plain(d)


# ── Python's random, pinned directly ─────────────────────────────────────────

def random_cases():
    seeds = ["", "a", "running-upper_a-h_push-block0", "mobility-running-3-", "cycling-lower_b-calf-7-salt",
             "日本語-ü", "x" * 300]
    out = []
    for s in seeds:
        for n in range(0, 9):
            xs = list(range(n))
            Random(s).shuffle(xs)
            out.append({"seed": s, "n": n, "expect": xs})
    return out


# ── Units ────────────────────────────────────────────────────────────────────

def load_cases():
    e1rm = [{"weight": w, "reps": r, "expect": estimate_1rm(w, r)}
            for w in (0, -5, 20.0, 57.5, 100, 142.3) for r in (0, 1, 2, 5, 8, 10, 12, 40)]
    rounds = []
    for eq in (["kettlebell"], ["machine"], ["machine", "barbell"], ["dumbbell"], ["cable"],
               ["barbell"], ["bodyweight"], [], None, ["kettlebell", "dumbbell"]):
        for units in ("metric", "imperial", "IMPERIAL", None):
            for w in (0.0, -1.0, 1.0, 3.7, 6.25, 11.3, 17.5, 23.9, 44.6, 61.25, 87.0, 130.0, 250.0):
                rounds.append({"weight": w, "equipment": eq, "units": units,
                               "expect": round_weight_for_equipment(w, eq, units=units)})
    return {"estimate_1rm": e1rm, "round_weight": rounds}


def archetype_cases():
    splits = ["ppl_push", "ppl_pull", "ppl_legs", "upper_a", "upper_b", "lower_a", "lower_b",
              "full_body", "supp_lower", "supp_upper_core"]
    sports = ["running", "cycling", "climbing", "generic", "mountain_biking"]
    out = []
    for sp in splits:
        for sport in sports:
            for tier in range(1, 6):
                for stage in ("linear", "weekly_undulating", "dup"):
                    for block in (0, 1, 2, -1):
                        a = select_archetype(sp, sport, tier, stage, block)
                        out.append([sp, sport, tier, stage, block, a.key if a else None])
    flows = []
    for ctx in ("post_workout", "weekly_mobility", "other"):
        for sport in ("running", "cycling", "climbing", "hiking", "generic"):
            for theme in (None, "hips", "posterior", "shoulders", "nope"):
                for vk in range(0, 4):
                    f = select_flow_archetype(ctx, sport, theme, vk)
                    flows.append([ctx, sport, theme, vk, f.key if f else None])
    return {
        "strength_keys": [a.key for a in ARCHETYPES],
        "flow_keys": [a.key for a in FLOW_ARCHETYPES],
        "select": out,
        "select_flow": flows,
    }


def leveling_cases():
    out = []
    for cur in (None, "brand_new", "returning", "regular", "advanced", "bogus"):
        for sessions in (0, 11, 12, 30):
            for trend in ("rising", "flat", "falling"):
                for idle in (0, 7, 8, 20):
                    sig = {"sessions_12wk": sessions, "e1rm_trend": trend, "weeks_since_last": idle}
                    out.append({"current": cur, "signals": sig, "expect": infer_experience_suggestion(cur, sig)})
    out.append({"current": "regular", "signals": {}, "expect": infer_experience_suggestion("regular", {})})
    return out


def flexibility_cases():
    names = [c.name for c in STRETCHES]
    sport = [{"sport": s, "fallback": fb, "expect": flex.sport_target_muscles(s, fallback=fb)}
             for s in ("Trail Running", "road_cycling", "MOUNTAIN_BIKING", "swimming", "bouldering",
                       "sea_kayaking", "rowing", "hiking", "walking", "strength_training", "golf", "", None)
             for fb in (None, [], ["neck"])]
    # Several non-priority muscles tie on rank; the name breaks the tie, so
    # these cases pin that ordering rather than avoiding it.
    strength = [{"trained": t, "expect": flex.strength_target_muscles(t)} for t in (
        [], ["quads", "hip_flexors", "quads"], ["chest", "shoulders", "glutes"],
        ["biceps"], ["lower_back", "upper_back", "hip_flexors", "hamstrings"], ["calves", "quads"],
        ["triceps", "biceps", "forearms", "neck"], ["neck", "biceps", "chest", "triceps"],
    )]
    mobility = [{"sport": s, "expect": mobility_target_muscles(s)}
                for s in ("running", "cycling", "climbing", "paddling", "generic", "hiking", "strength")]
    select = []
    variants = [
        dict(targets=["hip_flexors", "hamstrings", "glutes", "calves"], count=4, seed="s1"),
        dict(targets=["hip_flexors", "hamstrings", "glutes", "calves"], count=4, seed="s2"),
        dict(targets=["calves", "glutes"], count=6, seed="w3", min_count=5),
        dict(targets=["hamstrings"], count=3, seed="x", min_count=3, equipment_free=True),
        dict(targets=["glutes", "calves", "forearms", "neck", "unknown"], count=5, seed="ef", equipment_free=True),
        dict(targets=mobility_target_muscles("running"), count=8, seed="mobility-running-0-", min_count=6,
             order_by_position=True, closer_muscles=["lower_back", "glutes"]),
        dict(targets=mobility_target_muscles("climbing"), count=8, seed="mobility-climbing-5-r", min_count=6,
             order_by_position=True, closer_muscles=["forearms"]),
        dict(targets=mobility_target_muscles("cycling"), count=8, seed="m", min_count=6,
             order_by_position=True, closer_muscles=None),
        # A caller's own ranking, dynamic first. This was the strength warm-up's
        # before strength sessions stopped carrying stretches; kept as a case
        # of the selector's `pattern_rank`, which nothing else here reaches.
        dict(targets=["quads", "hip_flexors", "shoulders"], count=3, seed="warm",
             pattern_rank={"dynamic_stretch": 0, "yoga_pose": 1, "myofascial_release": 2,
                           "pnf_stretch": 3, "static_stretch": 4},
             equipment_free=True, min_count=2),
        dict(targets=["glutes"], count=2, seed="bal", exclude_patterns=frozenset()),
        dict(targets=[], count=4, seed="empty"),
    ]
    for v in variants:
        kwargs = {k: v[k] for k in ("pattern_rank", "equipment_free", "min_count", "order_by_position",
                                    "closer_muscles", "exclude_patterns") if k in v}
        picked = flex.select_stretch_flow(STRETCHES, v["targets"], count=v["count"], seed=v["seed"], **kwargs)
        case = {k: _plain(x) for k, x in v.items()}
        case["expect"] = [c.name for c in picked]
        case["steps"] = [flex.to_step(c) for c in picked]
        select.append(case)
    order = []
    for sel, closer in (
        (["Standing Quad Stretch", "Figure Four", "Low Lunge", "Downward Dog", "Seated Forward Fold"], ["glutes"]),
        (["Supine Twist", "Arm Circles", "Child's Pose"], ["lats"]),
        (["Arm Circles", "Couch Stretch"], ["neck"]),
        (["PNF Hamstring", "Wall Calf Stretch", "Pigeon Pose"], None),
        ([], ["glutes"]),
    ):
        picked = [STRETCHES[names.index(n)] for n in sel]
        order.append({"selected": sel, "closer": closer,
                      "expect": [c.name for c in flex.order_flow_by_position(picked, closer)]})
    return {"sport_targets": sport, "strength_targets": strength, "mobility_targets": mobility,
            "select": select, "order": order}


# ── Exercise selection and the whole generator ───────────────────────────────

def _inj(part, severity):
    return SimpleNamespace(body_part=part, severity=severity)


INJURY_SETS = {
    "none": [],
    "minor_knee": [("knee", 2)],
    "moderate_shoulder": [("Shoulder", 5)],
    "severe_lower_back": [("lower_back", 9)],
    "mixed": [("knee", 3), ("elbow", 6), ("hip", 8), ("unknown_part", 9), (None, 5)],
}


def selection_cases():
    out = []
    combos = [
        ("running", 3, "lower_a", ["barbell", "dumbbell", "bodyweight"], "none", 0, "", None),
        ("running", 3, "lower_a", ["barbell", "dumbbell", "bodyweight"], "none", 1, "", None),
        ("cycling", 4, "upper_b", ["barbell", "dumbbell", "cable", "machine"], "none", 2, "salt", None),
        ("climbing", 2, "supp_upper_core", ["bodyweight", "dumbbell"], "moderate_shoulder", 0, "", None),
        ("climbing", 4, "supp_upper_core", ["bodyweight", "pullup_bar"], "none", 0, "", None),
        ("climbing", 4, "supp_upper_core", ["bodyweight", "band"], "none", 0, "", None),
        ("paddling", 2, "supp_upper_core", ["cable", "band", "bodyweight"], "none", 5, "r", 3),
        ("generic", 1, "full_body", ["bodyweight"], "mixed", 0, "", 2),
        ("triathlon", 5, "ppl_legs", ["barbell", "dumbbell", "kettlebell", "bodyweight", "machine"], "severe_lower_back", 3, "", None),
        ("swimming", 5, "ppl_pull", ["barbell", "dumbbell", "cable", "bodyweight"], "none", 1, "", None),
        ("mountain_biking", 4, "ppl_push", ["dumbbell", "bodyweight", "cable"], "minor_knee", 0, "x", None),
        ("hiking", 3, "not_a_split", ["dumbbell", "bodyweight", "sled"], "none", 4, "", None),
    ]
    for i, (sport, tier, split, eq, inj, week, salt, maxd) in enumerate(combos):
        pref = {"Goblet Squat", "My Sled Push"} if i % 3 == 0 else set()
        excl = {"Deadlift"} if i % 2 == 0 else set()
        conf = {"Push-Up", "Goblet Squat"} if i % 4 == 1 else set()
        used = {"Back Squat", "Romanian Deadlift", "Pull-Up"} if i % 2 == 1 else set()
        picks = _select_exercises(
            sport_family=sport, tier=tier, split_type=split, equipment=eq, library=library(i % 3 == 0),
            active_injuries=[_inj(p, s) for p, s in INJURY_SETS[inj]], max_exercises=4 + (i % 3),
            preferred=pref, excluded=excl, week_num=week, regen_salt=salt, confirmed_no=conf,
            used_this_week=used, max_difficulty=maxd, block_num=week // 4, return_slots=True,
        )
        out.append({"sport": sport, "tier": tier, "split": split, "equipment": eq, "injuries": inj,
                    "week": week, "salt": salt, "max_difficulty": maxd, "custom": i % 3 == 0,
                    "preferred": sorted(pref), "excluded": sorted(excl), "confirmed_no": sorted(conf),
                    "used": sorted(used), "max_exercises": 4 + (i % 3),
                    "expect": [[s, n] for s, n in picks]})
    return out


def _existing(start: date, spec: list[tuple[int, str]]):
    return [{"scheduled_date": start + timedelta(days=d), "workout_type": t} for d, t in spec]


def generator_cases():
    wed = date(2026, 3, 4)          # a Wednesday
    mon = date(2026, 3, 2)
    sun = date(2026, 3, 8)
    hard_week = [(0, "intervals"), (2, "tempo"), (5, "long_run"), (6, "easy"), (9, "threshold"),
                 (12, "race_pace"), (13, "long"), (15, "aerobic"), (20, None)]
    records = {
        "Back Squat": {"estimated_1rm_kg": 120.0, "last_weight_kg": 95.0, "progression_stage": "weekly_undulating"},
        "Romanian Deadlift": {"estimated_1rm_kg": None, "last_weight_kg": 60.0, "progression_stage": "dup"},
        "Bench Press": {"estimated_1rm_kg": 0.01, "last_weight_kg": None, "progression_stage": None},
        "Pull-Up": {"estimated_1rm_kg": None, "last_weight_kg": None, "progression_stage": "linear"},
    }
    cases = [
        dict(name="running_tier3_race_in_five_weeks", sport="running", today=wed,
             goal=dict(strength_tier=3, strength_days_per_week=None, event_date=wed + timedelta(days=36)),
             existing=_existing(mon, hard_week), equipment=["barbell", "dumbbell", "bodyweight"],
             injuries="none", sessions=0, units="metric", experience=None, anchor=mon - timedelta(days=21)),
        dict(name="cycling_tier4_no_race_imperial", sport="cycling", today=mon,
             goal=dict(strength_tier=4, strength_days_per_week=None, event_date=None),
             existing=[], equipment=["barbell", "dumbbell", "cable", "machine", "bodyweight"],
             injuries="minor_knee", sessions=45, units="imperial", experience="regular", anchor=None,
             records=True, salt="regen2"),
        dict(name="climbing_tier2_supplementary", sport="climbing", today=sun,
             goal=dict(strength_tier=2, strength_days_per_week=None, event_date=sun + timedelta(days=20)),
             existing=_existing(sun, [(1, "intervals"), (4, "long")]), equipment=["bodyweight", "dumbbell", "band"],
             injuries="moderate_shoulder", sessions=5, units="metric", experience="brand_new", anchor=sun),
        dict(name="generic_tier1_bodyweight_injured", sport="generic", today=wed,
             goal=dict(strength_tier=1, strength_days_per_week=None, event_date=wed + timedelta(days=15)),
             existing=[], equipment=["bodyweight"], injuries="mixed", sessions=0, units="metric",
             experience="returning", anchor=None),
        dict(name="strength_tier5_dup_advanced", sport="strength", today=mon,
             goal=dict(strength_tier=5, strength_days_per_week=None, event_date=mon + timedelta(days=27)),
             existing=[], equipment=["barbell", "dumbbell", "cable", "machine", "kettlebell", "bodyweight", "pullup_bar"],
             injuries="none", sessions=150, units="metric", experience="advanced", anchor=mon - timedelta(days=70),
             records=True, custom=True, preferred={"Front Squat", "My Sled Push"}, excluded={"Deadlift"},
             confirmed_no={"Lat Pulldown"}, max_minutes=40),
        dict(name="explicit_days_mtb_race_tomorrow_ish", sport="mountain_biking", today=wed,
             goal=dict(strength_tier=3, strength_days_per_week=2, event_date=wed + timedelta(days=9)),
             existing=_existing(mon, [(3, "vo2max"), (7, "tempo")]), equipment=["dumbbell", "kettlebell", "bodyweight"],
             injuries="none", sessions=19, units="metric", experience=None, anchor=wed + timedelta(days=30)),
        dict(name="paddling_no_stretches", sport="paddling", today=wed,
             goal=dict(strength_tier=2, strength_days_per_week=None, event_date=wed + timedelta(days=14)),
             existing=[], equipment=["cable", "band", "bodyweight"], injuries="none", sessions=0,
             units="metric", experience=None, anchor=None, no_stretches=True),
        dict(name="hiking_race_already_run_plans_twelve_weeks", sport="hiking", today=wed,
             goal=dict(strength_tier=3, strength_days_per_week=None, event_date=wed - timedelta(days=3)),
             existing=[], equipment=["dumbbell", "bodyweight"], injuries="none", sessions=99,
             units="metric", experience="regular", anchor=None, salt="s"),
        dict(name="triathlon_tier5_explicit_five_days", sport="triathlon", today=sun,
             goal=dict(strength_tier=5, strength_days_per_week=5, event_date=sun + timedelta(days=22)),
             existing=_existing(sun, hard_week), equipment=["barbell", "dumbbell", "bodyweight"],
             injuries="severe_lower_back", sessions=20, units="imperial", experience=None, anchor=None),
        dict(name="alpine_skiing_plyo_finisher", sport="alpine_skiing", today=mon,
             goal=dict(strength_tier=3, strength_days_per_week=None, event_date=mon + timedelta(days=42)),
             existing=[], equipment=["barbell", "dumbbell", "bodyweight"], injuries="none", sessions=30,
             units="metric", experience="regular", anchor=None),
        dict(name="nordic_skiing_supplementary", sport="nordic_skiing", today=sun,
             goal=dict(strength_tier=2, strength_days_per_week=None, event_date=sun + timedelta(days=30)),
             existing=[], equipment=["dumbbell", "bodyweight"], injuries="none", sessions=5,
             units="imperial", experience=None, anchor=None),
        dict(name="nothing_is_safe", sport="running", today=wed,
             goal=dict(strength_tier=3, strength_days_per_week=None, event_date=wed + timedelta(days=10)),
             existing=[], equipment=["sled"], injuries="none", sessions=0, units="metric", experience=None,
             anchor=None),
    ]
    out = []
    for c in cases:
        goal = SimpleNamespace(**c["goal"])
        lib = library(c.get("custom", False))
        stretches = None if c.get("no_stretches") else STRETCHES
        result = gen.generate_strength_workouts(
            goal=goal, sport_family=c["sport"], today=c["today"], existing_workouts=c["existing"],
            equipment=c["equipment"], library=lib, strength_records=records if c.get("records") else {},
            active_injuries=[_inj(p, s) for p, s in INJURY_SETS[c["injuries"]]],
            sessions_count=c["sessions"], units=c["units"],
            preferred_exercises=c.get("preferred"), excluded_exercises=c.get("excluded"),
            session_max_minutes=c.get("max_minutes"), regen_salt=c.get("salt", ""),
            confirmed_no_exercises=c.get("confirmed_no"), stretch_candidates=stretches,
            experience=c["experience"], anchor_date=c["anchor"],
        )
        out.append({
            "name": c["name"], "sport": c["sport"], "today": c["today"].isoformat(),
            "goal": _plain(c["goal"]), "existing": _plain(c["existing"]), "equipment": c["equipment"],
            "injuries": c["injuries"], "sessions": c["sessions"], "units": c["units"],
            "experience": c["experience"], "anchor": _plain(c["anchor"]),
            "records": bool(c.get("records")), "custom": bool(c.get("custom")),
            "preferred": sorted(c.get("preferred") or []), "excluded": sorted(c.get("excluded") or []),
            "confirmed_no": sorted(c.get("confirmed_no") or []), "max_minutes": c.get("max_minutes"),
            "salt": c.get("salt", ""), "no_stretches": bool(c.get("no_stretches")),
            "expect": _plain(result),
        })
    return {"records": records, "cases": out}


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_strength_plan_fixtures.py from the backend's own planners. Do not edit.",
        "library": LIBRARY,
        "custom_exercise": CUSTOM,
        "stretches": [_stretch_json(c) for c in STRETCHES],
        "injury_sets": {k: [[p, s] for p, s in v] for k, v in INJURY_SETS.items()},
        "random": random_cases(),
        "loads": load_cases(),
        "archetypes": archetype_cases(),
        "leveling": leveling_cases(),
        "flexibility": flexibility_cases(),
        "selection": selection_cases(),
        "generator": generator_cases(),
    }
    OUT.write_text(json.dumps(corpus, separators=(",", ":"), allow_nan=False, ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
