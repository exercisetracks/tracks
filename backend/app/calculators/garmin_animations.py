# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Source-of-truth lookup for Garmin Connect movement animations.

The bundled `data/garmin_animations.json` is built from four publicly-served
JSON manifests Garmin Connect's web UI uses to render exercise/pose pages:

  https://connect.garmin.com/web-data/exercises/Exercises.json   (strength)
  https://connect.garmin.com/web-data/exercises/Yoga.json        (yoga poses)
  https://connect.garmin.com/web-data/exercises/Pilates.json     (pilates)
  https://connect.garmin.com/web-data/exercises/Mobility.json    (mobility)

Each entry there pairs a movement name (e.g. `BARBELL_BENCH_PRESS`) to a
FIT-SDK exercise_category + exercise_name enum. The presence of an entry in a
manifest means Garmin maintains an animation asset for it — strong evidence
the watch firmware can render an animation when the (category, exercise_name)
pair shows up in a workout_step.

This module loads the master into memory once at import time and provides
fast `(cat_int, name_int) → AnimationInfo` lookups. It is intentionally
side-effect free: callers (seed generators, API responses, the FIT encoder)
read this to know whether to mark a step as animatable.

Note on watch model: Garmin's manifest doesn't differentiate firmware
revisions. A movement listed here is animatable on devices that ship the
matching animation pack. For Tracks' Fenix 6X target, this is empirically
true for yoga POSE/MOVE/PLANK categories (verified against
After-Work_Yoga.fit, Alignment_101.fit references) and most strength
movements. Mobility.json entries route to a separate "Mobility" activity
type that the Fenix 6X firmware doesn't expose — they're left in the data
file for future device-capability matching but are NOT marked animatable
under the Yoga app routing this app currently uses.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Iterable

_DATA_PATH = Path(__file__).parent.parent / "data" / "garmin_animations.json"

# Garmin app contexts the manifest tags movements with — each maps to a
# distinct sport routing on the watch and a distinct animation pack.
APP_YOGA      = "Yoga"
APP_PILATES   = "Pilates"
APP_MOBILITY  = "Mobility"     # Tracks does not currently route here
APP_STRENGTH  = "Exercises"


@dataclass(frozen=True)
class AnimationInfo:
    cat_int:   int         # FIT exercise_category enum integer
    cat:       str         # snake_case category (e.g. "pose", "squat")
    name_int:  int         # FIT <category>_exercise_name enum integer
    name:      str         # snake_case name (e.g. "barbell_bench_press")
    display:   str         # UPPER_SNAKE display key (matches Garmin's URL)
    apps:      tuple[str, ...]  # which Garmin app manifests list this entry


def _load() -> list[AnimationInfo]:
    raw = json.loads(_DATA_PATH.read_text())
    return [
        AnimationInfo(
            cat_int=int(r["cat_int"]), cat=r["cat"],
            name_int=int(r["name_int"]), name=r["name"],
            display=r["display"], apps=tuple(r["in"]),
        )
        for r in raw
    ]


@lru_cache(maxsize=1)
def all_animations() -> tuple[AnimationInfo, ...]:
    """Full list of (category, name) animation tuples Garmin Connect ships."""
    return tuple(_load())


@lru_cache(maxsize=1)
def _by_pair() -> dict[tuple[int, int], AnimationInfo]:
    return {(a.cat_int, a.name_int): a for a in all_animations()}


@lru_cache(maxsize=1)
def _by_name() -> dict[str, AnimationInfo]:
    return {a.name: a for a in all_animations()}


# ── Public lookups ───────────────────────────────────────────────────────────

def get_animation(cat_int: int, name_int: int) -> AnimationInfo | None:
    """Return the AnimationInfo for a (cat, name) pair, or None if Garmin
    doesn't catalogue an animation for it."""
    return _by_pair().get((int(cat_int), int(name_int)))


def is_animatable(cat_int: int | None, name_int: int | None,
                   app: str | None = None) -> bool:
    """True iff Garmin Connect catalogues an animation for this (cat, name).

    `app` optionally narrows to a specific Garmin app context — e.g.
    `app=APP_YOGA` returns True only if the pair is in Yoga.json.
    Defaults to "in any manifest"."""
    if cat_int is None or name_int is None:
        return False
    info = get_animation(int(cat_int), int(name_int))
    if info is None:
        return False
    if app is None:
        return True
    return app in info.apps


def is_yoga_animatable(cat_int: int | None, name_int: int | None) -> bool:
    """Specialised check for Tracks' current routing: flexibility / mobility
    workouts go to the watch's Yoga app, which only renders pose / move /
    plank categories. Returns True iff the pair is in Yoga.json AND uses one
    of those categories."""
    if not is_animatable(cat_int, name_int, APP_YOGA):
        return False
    info = get_animation(int(cat_int), int(name_int))
    return info is not None and info.cat in {"pose", "move", "plank"}


def find_by_name(name: str) -> AnimationInfo | None:
    """Lookup by snake_case FIT enum name (e.g. 'barbell_bench_press')."""
    return _by_name().get((name or "").lower())


def in_category(cat: str | int, *, app: str | None = None
                ) -> tuple[AnimationInfo, ...]:
    """All animations within a category. `cat` may be the snake_case name
    or the int enum value."""
    target_int = cat if isinstance(cat, int) else None
    target_str = cat if isinstance(cat, str) else None
    out = []
    for a in all_animations():
        if target_int is not None and a.cat_int != target_int:
            continue
        if target_str is not None and a.cat != target_str:
            continue
        if app is not None and app not in a.apps:
            continue
        out.append(a)
    return tuple(out)


def yoga_poses() -> tuple[AnimationInfo, ...]:
    """All pose-category animations that Garmin's Yoga app renders. Use this
    as the master catalogue when expanding the stretch library."""
    return in_category("pose", app=APP_YOGA)


def yoga_moves() -> tuple[AnimationInfo, ...]:
    return in_category("move", app=APP_YOGA)


# ── Name-based remapping (current seed → animating equivalent) ───────────────
#
# When migrating existing library entries to animating subtypes, the closest-
# match heuristic is: split the entry's name into tokens, then score each
# animation by how many tokens overlap with its snake_case name. The highest
# score within the desired category wins. Caller can constrain the search
# category (e.g. only consider pose/move animations for yoga remapping).

def _tokenize(s: str) -> set[str]:
    return {t for t in s.lower().replace("-", " ").replace("_", " ").split() if t}


def suggest_remap(name_hint: str, *,
                  prefer_categories: Iterable[str] = ("pose", "move", "plank"),
                  app: str = APP_YOGA,
                  ) -> AnimationInfo | None:
    """Find the animating (cat, name) pair most lexically similar to a
    free-text exercise/stretch name. Returns the best candidate, or None
    if nothing scored above zero.

    The search is constrained to animations that appear in `app`'s manifest
    AND whose category is in `prefer_categories`. For yoga remapping use the
    defaults; for general remapping pass `app=APP_STRENGTH` and the relevant
    strength categories."""
    hint_tokens = _tokenize(name_hint)
    if not hint_tokens:
        return None

    prefer = set(prefer_categories)
    best: tuple[int, AnimationInfo] | None = None
    for a in all_animations():
        if app not in a.apps:
            continue
        if prefer and a.cat not in prefer:
            continue
        score = len(hint_tokens & _tokenize(a.name))
        if score == 0:
            continue
        if best is None or score > best[0]:
            best = (score, a)
    return best[1] if best else None


# ── Four-state animation status resolver ─────────────────────────────────────
#
# Each (garmin_category, garmin_subtype) pair maps to one of:
#
#   UNKNOWN        no garmin mapping at all — not in any Garmin manifest, so
#                  the watch has no way to render an animation.
#   LIKELY         in Garmin's published manifest, but no user has yet
#                  confirmed whether THEIR specific watch firmware actually
#                  plays the animation. This is the default for any
#                  manifest-listed entry on a device with no confirmations.
#   CONFIRMED_YES  a user with the device_product_id has marked it as
#                  animating on their watch. Trusted across all users with
#                  the same product_id.
#   CONFIRMED_NO   a user with the device_product_id has marked it as NOT
#                  animating. Same trust model.
#
# Resolution precedence: requesting user's own confirmation (if any) wins
# over an aggregate from other users. This lets the user override a stale
# confirmation from a family member without UI hoops.

STATE_UNKNOWN       = "unknown"
STATE_LIKELY        = "likely"
STATE_CONFIRMED_YES = "confirmed_yes"
STATE_CONFIRMED_NO  = "confirmed_no"


def resolve_state(
    cat: str | None,
    subtype: int | None,
    *,
    own_confirmation: bool | None = None,
    others_confirmed_yes: bool = False,
    others_confirmed_no:  bool = False,
) -> str:
    """Map a (category, subtype) pair to its four-state animation status.

    own_confirmation       True/False if THIS user has confirmed; None otherwise.
    others_confirmed_yes/no Whether other users (same device product) have
                            an opinion. Caller is expected to aggregate the
                            DB rows before calling.
    """
    if not cat or subtype is None:
        return STATE_UNKNOWN
    from app.calculators.fit_workout import _CATEGORY_INT
    cat_int = _CATEGORY_INT.get(cat.lower())
    if cat_int is None:
        return STATE_UNKNOWN
    if not is_animatable(cat_int, subtype):
        return STATE_UNKNOWN

    if own_confirmation is True:
        return STATE_CONFIRMED_YES
    if own_confirmation is False:
        return STATE_CONFIRMED_NO

    if others_confirmed_yes and not others_confirmed_no:
        return STATE_CONFIRMED_YES
    if others_confirmed_no and not others_confirmed_yes:
        return STATE_CONFIRMED_NO
    # Mixed signals or no signal → fall back to manifest membership.
    return STATE_LIKELY


def build_confirmation_lookup(
    db,                                         # Session — not typed to avoid circular import
    product_id: int | None,
    user_id: int | None,
) -> dict[tuple[str, int], dict]:
    """Bulk-load all confirmations for a (product_id) and return a lookup
    keyed by (garmin_category, garmin_subtype) → {own: bool|None,
    others_yes: bool, others_no: bool}.

    The planner builds many step dicts in a single pass; doing a query per
    library entry would N+1. This pre-builds a small dict that the resolver
    can hit from memory."""
    if product_id is None:
        return {}
    from app.models.strength import AnimationConfirmation
    rows = (
        db.query(AnimationConfirmation)
        .filter(AnimationConfirmation.product_id == product_id)
        .all()
    )
    out: dict[tuple[str, int], dict] = {}
    for r in rows:
        key = (r.garmin_category, r.garmin_subtype)
        slot = out.setdefault(key, {"own": None, "others_yes": False, "others_no": False})
        if user_id is not None and r.user_id == user_id:
            slot["own"] = bool(r.animates)
        else:
            if r.animates:
                slot["others_yes"] = True
            else:
                slot["others_no"] = True
    return out


def state_from_lookup(cat: str | None, subtype: int | None,
                      lookup: dict[tuple[str, int], dict]) -> str:
    """Convenience: resolve the 4-state from a prebuilt lookup dict."""
    if not cat or subtype is None:
        return resolve_state(cat, subtype)
    entry = lookup.get((cat, subtype))
    if entry is None:
        return resolve_state(cat, subtype)
    return resolve_state(
        cat, subtype,
        own_confirmation=entry["own"],
        others_confirmed_yes=entry["others_yes"],
        others_confirmed_no=entry["others_no"],
    )


__all__ = [
    "APP_YOGA", "APP_PILATES", "APP_MOBILITY", "APP_STRENGTH",
    "AnimationInfo",
    "all_animations", "get_animation",
    "is_animatable", "is_yoga_animatable",
    "find_by_name", "in_category", "yoga_poses", "yoga_moves",
    "suggest_remap",
    "STATE_UNKNOWN", "STATE_LIKELY", "STATE_CONFIRMED_YES", "STATE_CONFIRMED_NO",
    "resolve_state", "build_confirmation_lookup", "state_from_lookup",
]
