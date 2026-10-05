# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Flow archetypes: named, coach-authored stretch-flow blueprints ("Runner's
Reset", "Hip Opener Wind-Down") that give a post-activity or weekly mobility
flow an identity, a calming position arc, and a themed closer.

Like workout archetypes these are JSON data files
(``app/data/archetypes/flows/*.json``) loaded and validated at import. They are
purely additive — when nothing matches, the flow keeps its generic title and
default ordering.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass

logger = logging.getLogger(__name__)

_FLOW_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(__file__))),  # app/
    "data", "archetypes", "flows",
)

_VALID_CONTEXTS = {"post_workout", "weekly_mobility"}


@dataclass(frozen=True)
class FlowArchetype:
    key: str
    name: str
    tagline: str
    contexts: frozenset
    sports: frozenset | None    # None = any sport family
    themes: frozenset           # cooldown themes this flow suits
    closer_muscles: tuple       # calming closer targets these muscles

    def applies(self, context: str, sport_family: str, theme: str | None) -> bool:
        if self.contexts and context not in self.contexts:
            return False
        if self.sports is not None and sport_family not in self.sports:
            return False
        # A theme match is a strong signal but not required — a themed flow can
        # still be a good generic choice.
        return True

    def theme_score(self, theme: str | None) -> int:
        return 1 if (theme and theme in self.themes) else 0


def _parse(raw: dict) -> FlowArchetype:
    contexts = frozenset(raw.get("contexts", []))
    if not contexts <= _VALID_CONTEXTS:
        raise ValueError(f"flow archetype {raw.get('key')!r} has invalid contexts")
    return FlowArchetype(
        key=raw["key"],
        name=raw["name"],
        tagline=raw.get("tagline", ""),
        contexts=contexts,
        sports=(frozenset(raw["sports"]) if raw.get("sports") else None),
        themes=frozenset(raw.get("themes", [])),
        closer_muscles=tuple(raw.get("closer_muscles", [])),
    )


def _load_all() -> list[FlowArchetype]:
    out: list[FlowArchetype] = []
    if not os.path.isdir(_FLOW_DIR):
        return out
    for fname in sorted(os.listdir(_FLOW_DIR)):
        if not fname.endswith(".json"):
            continue
        try:
            with open(os.path.join(_FLOW_DIR, fname)) as f:
                out.append(_parse(json.load(f)))
        except Exception as e:
            logger.error("skipping invalid flow archetype %s: %s", fname, e)
    return out


FLOW_ARCHETYPES: list[FlowArchetype] = _load_all()


def select_flow_archetype(
    context: str,
    sport_family: str,
    theme: str | None,
    variety_key: int,
    archetypes: list[FlowArchetype] | None = None,
) -> FlowArchetype | None:
    """Pick a flow archetype for the context, preferring theme matches, then
    rotating on `variety_key` so consecutive flows vary. Returns None when
    nothing applies (flow keeps its generic title/order)."""
    pool = [a for a in (archetypes if archetypes is not None else FLOW_ARCHETYPES)
            if a.applies(context, sport_family, theme)]
    if not pool:
        return None
    best = max(a.theme_score(theme) for a in pool)
    pool = [a for a in pool if a.theme_score(theme) == best]
    pool.sort(key=lambda a: a.key)
    return pool[variety_key % len(pool)]
