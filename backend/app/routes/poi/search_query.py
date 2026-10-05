# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Search-query building for POI text search.

Pure, side-effect-free helpers that turn a raw user query into the bound
parameters the search SQL needs:

  _build_tsquery  → a Postgres ``to_tsquery`` string (token-AND, expansion-OR,
                    prefix-matched), abbreviation-aware so word order and partial
                    words still match.
  _expand_plain   → a plain expanded string for trigram / word-similarity typo
                    tolerance (first expansion per token).

All tokens are split on non-alphanumerics (``_search_tokens``), so the strings
produced here only ever contain ``[a-z0-9]`` plus the tsquery operators we add —
they are always passed to SQL as *bound* params, never interpolated.
"""

from __future__ import annotations

import re

# Common geographic abbreviations → their full forms, so "mt hood" finds
# "Mount Hood", "ft worth" → "Fort Worth", "st marys" → "Saint Marys".
# Each token is matched as an OR-group of (original + expansions), all as prefix,
# and all the query's tokens are AND-ed — so word order doesn't matter and partial
# words still match. The trigram fallback uses the first expansion for typo
# tolerance.
_POI_ABBREV: dict[str, list[str]] = {
    "mt": ["mount"], "mtn": ["mountain"], "mtns": ["mountains"],
    "mts": ["mountains"], "mont": ["mount"],
    "ft": ["fort"], "st": ["saint", "street"], "ste": ["sainte"],
    "ck": ["creek"], "cr": ["creek"], "crk": ["creek"],
    "spg": ["spring"], "spgs": ["springs"], "sprgs": ["springs"],
    "jct": ["junction"], "lk": ["lake"], "ldg": ["landing"],
    "pt": ["point"], "pk": ["peak"], "mdw": ["meadow"], "mdws": ["meadows"],
    "rdg": ["ridge"], "vly": ["valley"], "cyn": ["canyon"], "cnyn": ["canyon"],
    "res": ["reservoir"], "resvr": ["reservoir"], "rvr": ["river"],
    "n": ["north"], "s": ["south"], "e": ["east"], "w": ["west"],
    "ne": ["northeast"], "nw": ["northwest"], "se": ["southeast"], "sw": ["southwest"],
    "natl": ["national"], "nat": ["national"], "monu": ["monument"],
}


def _search_tokens(q: str) -> list[str]:
    return [t for t in re.split(r"[^a-z0-9]+", q.lower()) if t]


def _build_tsquery(q: str) -> str | None:
    """Build a to_tsquery string: every token AND-ed, each an OR of its expansions,
    all prefix-matched. e.g. 'mt hood' → '(mount:* | mt:*) & (hood:*)'."""
    tokens = _search_tokens(q)
    if not tokens:
        return None
    groups = []
    for t in tokens:
        alts = sorted({t, *_POI_ABBREV.get(t, [])})
        groups.append("(" + " | ".join(f"{a}:*" for a in alts) + ")")
    return " & ".join(groups)


def _expand_plain(q: str) -> str:
    """Plain expanded query for trigram similarity (first expansion per token)."""
    return " ".join(_POI_ABBREV.get(t, [t])[0] for t in _search_tokens(q))


# ── things nobody searches for ───────────────────────────────────────────────
#
# A fifth of this gazetteer was storage tanks — 14,644 of them against 849
# named peaks — plus guideposts, benches, street lamps and survey masts, all
# swept in by the OSM importer because they carry a `name` tag. They are real
# features and they belong on the map; they do not belong in a list of places
# somebody might type the name of, where they crowd out the mountain that
# shares a word with them.
#
# Excluded at query time rather than at index time on purpose: the same rows
# feed the map's icon layer, which does want them, and a filter here can be
# adjusted without a reindex.
_JUNK_KINDS: tuple[str, ...] = (
    "storage_tank", "guidepost", "board", "mast", "street_lamp", "bench",
    "waste_basket", "bollard", "surveillance", "fire_hydrant", "manhole",
    "utility_pole", "power_pole", "tree", "bicycle_parking", "vending_machine",
    "post_box", "telephone", "clock", "advertising_column", "planter",
)
