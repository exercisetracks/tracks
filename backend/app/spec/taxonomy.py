# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Evaluate the generated sport-taxonomy rules.

Hand-written, unlike the rule table it walks (spec/sport_taxonomy.yaml →
sport_taxonomy.py). Templating this loop into three languages would be worse to
maintain than the drift it prevents, and it is small enough to read in one
sitting. What keeps it honest is spec/fixtures/sport_taxonomy.json: the same
input/output corpus runs against the Python, JavaScript, and Kotlin evaluators,
so an implementation that diverges fails its own test suite.

If you change the matching semantics here, change them in
frontend/src/spec/taxonomy.js and the Kotlin equivalent in the same commit —
the fixtures will tell you if you forgot, but only after the fact.
"""

import re
from functools import lru_cache

from .sport_taxonomy import FALLBACK, RULES, SPORT_TYPES

__all__ = ["SPORT_TYPES", "sport_type", "normalise"]

_NON_ALNUM = re.compile(r"[^a-z0-9]")


@lru_cache(maxsize=512)
def _compiled(pattern: str) -> re.Pattern:
    """Rule patterns are a small fixed set reused across every activity in a
    query, so compiling them once matters when classifying a few thousand rows."""
    return re.compile(pattern)


def normalise(value: str | None) -> str:
    return _NON_ALNUM.sub("_", (value or "").lower())


def _field(fields: dict[str, str], name: str) -> str:
    return fields[name]


def _condition_matches(cond: dict, fields: dict[str, str]) -> bool:
    value = _field(fields, cond["field"])
    if "equals" in cond:
        return value == cond["equals"]
    return _compiled(cond["matches"]).search(value) is not None


def _entry_matches(entry: dict, fields: dict[str, str]) -> bool:
    if "all" in entry:
        return all(_condition_matches(c, fields) for c in entry["all"])
    return _condition_matches(entry, fields)


def sport_type(sport: str | None, sub_sport: str | None = None) -> str:
    """Classify a FIT sport/sub_sport pair into a Tracks sport type.

    Rules are evaluated in order and the first match wins — see the ordering
    notes in spec/sport_taxonomy.yaml before assuming any rule is independent.
    """
    s = normalise(sport)
    ss = normalise(sub_sport)
    fields = {"sport": s, "sub_sport": ss, "combined": f"{s} {ss}"}

    for rule in RULES:
        if not any(_entry_matches(e, fields) for e in rule["any"]):
            continue
        if any(_entry_matches(e, fields) for e in rule.get("none", ())):
            continue
        return rule["type"]
    return FALLBACK
