# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Workout archetypes: named, coach-authored session blueprints layered over the
slot machinery. An archetype gives a session an identity (name + intent),
per-slot scheme overrides (e.g. 5×5 on the main hinge), superset pairings, a
finisher, and a cooldown theme that feeds the post-workout flow.

There is no warm-up block. Archetypes used to carry one, filled with
stretches from the stretch library and put in front of the lifts. A strength
session holds lifts only: stretching is its own workout, the flow placed on
the same day (api/training_plan/injectors.inject_stretch_flows_with) and the
weekly mobility session. A `warmup` key is refused rather than ignored, so it
cannot come back as config that silently does nothing.

Archetypes are JSON data files in ``app/data/archetypes/strength/*.json``,
loaded and validated once at import. They reference movement slots by key
(``slots.SLOTS_BY_KEY``) — the slot registry remains the single source of truth
for what movements fill a slot; archetypes only compose and flavour them.

The design is fail-open: if no archetype matches a session the generator falls
back to its previous behaviour exactly, so archetypes are purely additive.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from random import Random

from .slots import SLOTS_BY_KEY

logger = logging.getLogger(__name__)

_ARCHETYPE_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(__file__))),  # app/
    "data", "archetypes", "strength",
)

_VALID_PHASES = {"linear", "weekly_undulating", "dup", "any"}
_VALID_FINISHERS = {"plyo", "carry_core", None}


@dataclass(frozen=True)
class Archetype:
    key: str
    name: str
    tagline: str
    intent: str
    splits: frozenset          # empty = applies to any split
    sports: frozenset | None   # None = any sport family
    tier_range: tuple[int, int]
    phases: frozenset          # periodization stages this suits (or {"any"})
    schemes: dict              # slot_key -> {sets,reps,pct_1rm,rpe,tempo,rest_seconds}
    supersets: tuple           # tuple of (slot_key, slot_key) pairs
    finisher: str | None
    cooldown_theme: str | None

    def applies(self, split_key: str, sport_family: str, tier: int, stage: str) -> bool:
        if self.splits and split_key not in self.splits:
            return False
        if self.sports is not None and sport_family not in self.sports:
            return False
        lo, hi = self.tier_range
        if not (lo <= tier <= hi):
            return False
        if "any" not in self.phases and stage not in self.phases:
            return False
        return True


def _parse(raw: dict) -> Archetype:
    main = raw.get("main", [])
    schemes = {}
    for entry in main:
        slot = entry["slot"]
        if slot not in SLOTS_BY_KEY:
            raise ValueError(f"archetype {raw.get('key')!r} references unknown slot {slot!r}")
        if entry.get("scheme"):
            schemes[slot] = entry["scheme"]
    supersets = tuple(tuple(pair) for pair in raw.get("supersets", []))
    for pair in supersets:
        for slot in pair:
            if slot not in SLOTS_BY_KEY:
                raise ValueError(f"archetype {raw.get('key')!r} superset references unknown slot {slot!r}")
    phases = frozenset(raw.get("phases", ["any"]))
    if not phases <= _VALID_PHASES:
        raise ValueError(f"archetype {raw.get('key')!r} has invalid phases {phases - _VALID_PHASES}")
    finisher = raw.get("finisher")
    if finisher not in _VALID_FINISHERS:
        raise ValueError(f"archetype {raw.get('key')!r} has invalid finisher {finisher!r}")
    if "warmup" in raw:
        raise ValueError(f"archetype {raw.get('key')!r} has a warmup: strength sessions carry no stretches")
    tr = raw.get("tier_range", [1, 5])
    return Archetype(
        key=raw["key"],
        name=raw["name"],
        tagline=raw.get("tagline", ""),
        intent=raw.get("intent", ""),
        splits=frozenset(raw.get("splits", [])),
        sports=(frozenset(raw["sports"]) if raw.get("sports") else None),
        tier_range=(int(tr[0]), int(tr[1])),
        phases=phases,
        schemes=schemes,
        supersets=supersets,
        finisher=finisher,
        cooldown_theme=raw.get("cooldown_theme"),
    )


def _load_all() -> list[Archetype]:
    out: list[Archetype] = []
    if not os.path.isdir(_ARCHETYPE_DIR):
        return out
    for fname in sorted(os.listdir(_ARCHETYPE_DIR)):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(_ARCHETYPE_DIR, fname)
        try:
            with open(path) as f:
                out.append(_parse(json.load(f)))
        except Exception as e:  # a bad file must never break plan generation
            logger.error("skipping invalid workout archetype %s: %s", fname, e)
    return out


# Loaded once at import. Deliberately eager so a malformed file surfaces in logs
# at startup rather than mid-request.
ARCHETYPES: list[Archetype] = _load_all()


def select_archetype(
    split_key: str,
    sport_family: str,
    tier: int,
    stage: str,
    block_index: int,
    archetypes: list[Archetype] | None = None,
) -> Archetype | None:
    """Pick the archetype for a session, rotating per training block so a given
    split cycles through its applicable archetypes block to block (stable within
    a block, matching the main-lift stability). Returns None when nothing
    applies — the generator then keeps its default behaviour."""
    pool = [a for a in (archetypes if archetypes is not None else ARCHETYPES)
            if a.applies(split_key, sport_family, tier, stage)]
    if not pool:
        return None
    pool.sort(key=lambda a: a.key)  # deterministic order
    # Rotate deterministically on the block so consecutive blocks vary.
    idx = block_index % len(pool)
    return pool[idx]
