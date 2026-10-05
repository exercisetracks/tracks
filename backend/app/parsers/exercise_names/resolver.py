# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Resolver logic over the static FIT exercise lookup tables in :mod:`._data`.

These two helpers turn the raw ``exercise_category`` / ``category_subtype``
fields of a FIT set message into canonical strings and human-readable names.
"""

from ._data import _CATEGORY_ID, _NAMES


def decode_category(raw) -> str | None:
    """Convert a FIT exercise_category value to a canonical snake_case string.

    Handles both fitdecode enum objects (have a .name attribute) and raw
    integers that fitdecode couldn't decode.
    """
    if raw is None:
        return None
    if hasattr(raw, "name"):          # fitdecode enum
        name = raw.name.lower()
    else:
        try:
            name = _CATEGORY_ID.get(int(raw), str(raw)).lower()
        except (TypeError, ValueError):
            name = str(raw).lower()
    if name in ("unknown", "65534"):
        return None
    return name


def resolve_exercise_name(category: str | None, subtype: int | None) -> str | None:
    """
    Return a human-readable exercise name from a FIT set message's category
    and category_subtype fields.

    category:  fitdecode enum string (e.g. "squat", "bench_press")
    subtype:   raw integer index into that category's exercise name enum

    Returns None if category is None/unknown and subtype has no entry.
    """
    if category is None:
        return None
    cat = str(category).lower()
    if cat in ("unknown", "65534"):
        return None
    if subtype is not None:
        name = _NAMES.get((cat, int(subtype)))
        if name:
            return name
    # Fallback: prettify the category name
    return cat.replace("_", " ").title()
