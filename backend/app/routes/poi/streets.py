# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Looking up a street corner by the names of the two streets.

The normalisation here has to match what the importer stored, so both sides
call `street_tokens` — see app.services.street_junctions for the writing half.
"""

from __future__ import annotations

import re

from sqlalchemy import String, bindparam, text
from sqlalchemy.dialects.postgresql import ARRAY

_TEXT_ARRAY = ARRAY(String)

# Street-type words, dropped so "Maple", "Maple St" and "Maple Street" are the
# same query. Directionals are deliberately NOT here: North Broadway and South
# Broadway are different streets, and collapsing them would answer the wrong
# corner with complete confidence.
_STREET_TYPES = {
    "street", "st", "avenue", "ave", "av", "road", "rd", "drive", "dr",
    "lane", "ln", "boulevard", "blvd", "court", "ct", "circle", "cir",
    "place", "pl", "way", "trail", "trl", "parkway", "pkwy", "highway",
    "hwy", "terrace", "ter", "loop", "run", "path", "alley", "aly",
    "plaza", "plz", "square", "sq", "crescent", "cres", "close", "walk",
    "row", "bend", "pass", "point", "pt", "ridge", "rdg", "spur", "route",
    "rte", "expressway", "expy", "freeway", "fwy", "turnpike", "tpke",
}


def street_tokens(name: str) -> list[str]:
    """"North Maple St." → ["north", "maple"].

    Numbered streets keep their number as the word — "5th" stays "5th" — and
    the ordinal suffix is normalised away so "5th", "5" and "fifth" do not
    fragment into three different corners. Everything is lower-cased; anything
    that is only a street type contributes nothing.
    """
    words = [w for w in re.split(r"[^a-z0-9]+", name.lower()) if w]
    tokens = []
    for word in words:
        if word in _STREET_TYPES:
            continue
        # 5th/5TH/05 → 5, so the three spellings of one street agree.
        ordinal = re.fullmatch(r"(\d+)(st|nd|rd|th)", word)
        if ordinal:
            word = ordinal.group(1)
        elif word.isdigit():
            word = str(int(word))
        tokens.append(word)
    return tokens


def find_intersections(db, first: str, second: str, anchor, limit: int):
    """Corners where a street matching `first` meets one matching `second`.

    Both sides must contribute at least one real word; "the and a" is not a
    corner. Returns rows shaped like the POI rows the rest of search returns,
    so the caller can format them the same way.
    """
    a = street_tokens(first)
    b = street_tokens(second)
    if not a or not b or set(a) == set(b):
        return []

    params: dict = {"a": a, "b": b, "limit": limit}
    if anchor is not None:
        clat, clng, _span = anchor
        params.update(clat=clat, clng=clng)
        order = ("((lat - :clat) * (lat - :clat) + "
                 "(lng - :clng) * (lng - :clng)) ASC")
    else:
        order = "id ASC"

    sql = f"""
        SELECT id, lat, lng, names
        FROM street_junctions
        WHERE tokens @> :a AND tokens @> :b
        ORDER BY {order}
        LIMIT :limit
    """
    statement = text(sql).bindparams(
        bindparam("a", type_=_TEXT_ARRAY), bindparam("b", type_=_TEXT_ARRAY),
    )
    return db.execute(statement, params).fetchall()
