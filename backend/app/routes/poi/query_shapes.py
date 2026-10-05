# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Recognising what *kind* of question a search box was handed.

Most queries are a name. Some are not, and answering those as names gives a
confidently wrong result: a pasted coordinate finds nothing, "coffee near
Fairview" searches for a place called that, and "Maple and Broadway" looks for
somewhere literally named "Maple and Broadway".

Each function here recognises one shape and returns None otherwise, so the
caller can try them in order and fall through to an ordinary name search. They
are pure string handling — no database, no network — which is what makes them
cheap enough to run on every keystroke.
"""

from __future__ import annotations

import re

# ── coordinates ──────────────────────────────────────────────────────────────

_DECIMAL = re.compile(
    r"^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;/ ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$"
)

# 39°45'23.5"N 150°13'10"W, and the same with the hemisphere leading, spaces or
# unicode primes instead of quotes, and the seconds left off.
_DMS_PART = r"""
    (?:(?P<lead{i}>[NSEW])\s*)?
    (?P<deg{i}>\d{{1,3}})\s*(?:[°d]|\s)\s*
    (?:(?P<min{i}>\d{{1,2}}(?:\.\d+)?)\s*(?:['′m]|\s)\s*)?
    (?:(?P<sec{i}>\d{{1,2}}(?:\.\d+)?)\s*(?:["″s]|\s)?\s*)?
    (?:(?P<trail{i}>[NSEW])(?![0-9]))?
"""
# The (?![0-9]) matters: in "N39°45' W150°13'" the W belongs to the longitude
# that follows it, and without the guard the latitude's optional *trailing*
# hemisphere swallows it — leaving the longitude with no hemisphere at all and
# the whole coordinate rejected. A genuine trailing hemisphere is followed by a
# space or the end of the string, never by the next number.
_DMS = re.compile(
    r"^\s*" + _DMS_PART.format(i=1) + r"\s*[,;]?\s*" + _DMS_PART.format(i=2) + r"\s*$",
    re.VERBOSE | re.IGNORECASE,
)


def _dms_value(deg: str, minutes: str | None, seconds: str | None) -> float:
    value = float(deg)
    if minutes:
        value += float(minutes) / 60
    if seconds:
        value += float(seconds) / 3600
    return value


def parse_coordinates(q: str) -> tuple[float, float] | None:
    """A pasted position → (lat, lng), or None if this is not one.

    Accepts "39.75, -150.22" and "39°45'N 150°13'W" and the spellings in
    between. Ordering is the one every mapping tool prints, latitude first —
    which is the opposite of GeoJSON, and the mistake worth being explicit
    about: a swapped pair puts the point in the wrong ocean rather than
    failing.
    """
    match = _DECIMAL.match(q)
    if match:
        lat, lng = float(match.group(1)), float(match.group(2))
        return (lat, lng) if _plausible(lat, lng) else None

    match = _DMS.match(q)
    if not match:
        return None
    parts = []
    for i in (1, 2):
        g = match.groupdict()
        hemisphere = (g[f"lead{i}"] or g[f"trail{i}"] or "").upper()
        if not hemisphere:
            return None      # bare "39 45 12 105 13 10" is too ambiguous to guess
        value = _dms_value(g[f"deg{i}"], g[f"min{i}"], g[f"sec{i}"])
        if hemisphere in ("S", "W"):
            value = -value
        parts.append((hemisphere, value))

    by_axis = {"N": None, "S": None, "E": None, "W": None}
    for hemisphere, value in parts:
        by_axis[hemisphere] = value
    lat = by_axis["N"] if by_axis["N"] is not None else by_axis["S"]
    lng = by_axis["E"] if by_axis["E"] is not None else by_axis["W"]
    if lat is None or lng is None:
        return None
    return (lat, lng) if _plausible(lat, lng) else None


def _plausible(lat: float, lng: float) -> bool:
    return -90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0


# ── "X near Y" ───────────────────────────────────────────────────────────────

# Longest first: " in the " must beat " in ".
_NEAR_WORDS = (
    " near the ", " close to ", " closest to ", " nearest to ",
    " around the ", " near ", " around ", " by the ", " in the ", " in ",
    " at the ", " at ",
)

# "near me" / "around here" mean the map, which the caller already knows. They
# are stripped as filler rather than treated as a place to look up.
_SELF_REFERENTIAL = {"me", "here", "us", "my location", "current location"}


def split_near(q: str) -> tuple[str, str] | None:
    """"coffee near Fairview" → ("coffee", "Fairview").

    Returns None when there is no such split, or when the tail refers to the
    user rather than a place — "coffee near me" is just "coffee", searched
    around wherever the map already is.
    """
    lowered = f" {q.lower().strip()} "
    for word in _NEAR_WORDS:
        index = lowered.find(word)
        if index == -1:
            continue
        head = q.strip()[: max(index - 1, 0)].strip()
        tail = q.strip()[index - 1 + len(word) :].strip()
        if not head or not tail:
            continue
        if tail.lower() in _SELF_REFERENTIAL:
            return None
        return head, tail
    return None


def strip_self_reference(q: str) -> str:
    """Drop a trailing "near me" / "around here" so the rest can be matched."""
    lowered = q.lower().strip()
    for word in _NEAR_WORDS:
        marker = word.rstrip()
        if lowered.endswith(marker.strip() + " me") or lowered.endswith(marker.strip() + " here"):
            cut = lowered.rfind(marker.strip())
            return q.strip()[:cut].strip()
    return q.strip()


# ── "A and B" (a street corner) ──────────────────────────────────────────────

_JOINERS = (" and ", " & ", " at ", " / ", " x ", " cross ")


def split_intersection(q: str) -> tuple[str, str] | None:
    """"Maple and Broadway" → ("Maple", "Broadway").

    Deliberately permissive: this only proposes a pair, and the caller checks
    whether two streets by those names actually meet. A query like "bed and
    breakfast" splits here too and then finds no junction, which costs one
    indexed lookup and falls through to the ordinary name search.
    """
    lowered = q.lower()
    for joiner in _JOINERS:
        index = lowered.find(joiner)
        if index == -1:
            continue
        left = q[:index].strip()
        right = q[index + len(joiner):].strip()
        if left and right and " and " not in right.lower():
            return left, right
    return None
