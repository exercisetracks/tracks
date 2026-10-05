# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tag selector mirroring each builder's Overpass key=value filter."""

from __future__ import annotations


def select(elements: list[dict], key: str, values) -> list[dict]:
    """Filter elements to those whose ``tags[key]`` matches ``values``.

    ``values`` may be an iterable of exact strings or a compiled regex. Mirrors
    each builder's Overpass key=value selector so the local path returns exactly
    what the Overpass query would have.
    """
    if hasattr(values, "match"):
        return [el for el in elements
                if (v := (el.get("tags") or {}).get(key)) and values.match(v)]
    vset = set(values)
    return [el for el in elements
            if (el.get("tags") or {}).get(key) in vset]
