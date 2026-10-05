# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Searching by what a thing *is*, not only by what it is called.

"coffee" should find the cafe down the road, not the one place with the word
"Coffee" in its name. That is the difference between a gazetteer lookup and a
search box people actually use, and the data already supports it: every POI
carries a `kind` (and often a `kind_detail`), and there are a thousand cafes,
four thousand campsites and nine hundred trailheads sitting behind labels
nobody was matching against.

Two vocabularies live in this table and both are matched here. GNIS spells its
classes in Title Case ("Summit", "Falls", "Stream"); OSM spells its tags in
lowercase with underscores ("camp_site", "drinking_water"). Comparison is
case-folded so a caller never has to care which importer a row came from.

The map is deliberately written from the kinds actually present — see the
`kind`/`kind_detail` counts in the database — rather than from an idea of what
a POI schema ought to contain. A synonym pointing at a kind no row has is a
promise the search cannot keep.
"""

from __future__ import annotations

import re

# Phrase → the kind/kind_detail values it should match.
#
# Keys are matched against the whole query, lower-cased, after punctuation is
# stripped; multi-word keys are matched before single words, so "gas station"
# does not get answered as "station". Values are compared case-insensitively
# against both `kind` and `kind_detail`.
_CATEGORIES: dict[str, tuple[str, ...]] = {
    # ── food and drink ───────────────────────────────────────────────────────
    "coffee": ("cafe", "coffee_shop"),
    "coffee shop": ("cafe", "coffee_shop"),
    "cafe": ("cafe", "coffee_shop"),
    "espresso": ("cafe", "coffee_shop"),
    "restaurant": ("restaurant",),
    "restaurants": ("restaurant",),
    "food": ("restaurant", "fast_food", "cafe"),
    "eat": ("restaurant", "fast_food", "cafe"),
    "dinner": ("restaurant",),
    "lunch": ("restaurant", "fast_food", "cafe"),
    "breakfast": ("cafe", "restaurant"),
    "fast food": ("fast_food",),
    "burger": ("burger", "fast_food"),
    "pizza": ("pizza",),
    "mexican": ("mexican",),
    "sandwich": ("sandwich",),
    "ice cream": ("ice_cream",),
    "bakery": ("bakery",),
    "bar": ("bar", "pub"),
    "pub": ("pub", "bar"),
    "beer": ("pub", "bar", "brewery", "biergarten"),
    "brewery": ("brewery",),
    "drink": ("bar", "pub", "brewery"),

    # ── the ones you need on a trip ──────────────────────────────────────────
    "gas": ("fuel",),
    "gas station": ("fuel",),
    "petrol": ("fuel",),
    "fuel": ("fuel",),
    "diesel": ("fuel",),
    "charging": ("charging_station",),
    "ev charging": ("charging_station",),
    "charger": ("charging_station",),
    "parking": ("parking", "parking_space"),
    "bathroom": ("toilets",),
    "bathrooms": ("toilets",),
    "restroom": ("toilets",),
    "toilet": ("toilets",),
    "toilets": ("toilets",),
    "water": ("drinking_water", "water_point"),
    "drinking water": ("drinking_water", "water_point"),
    "atm": ("atm", "bank"),
    "bank": ("bank", "atm"),
    "post office": ("post_office",),
    "laundry": ("laundry", "dry_cleaning"),
    "shower": ("shower",),

    # ── shops ────────────────────────────────────────────────────────────────
    "grocery": ("supermarket", "convenience", "greengrocer"),
    "groceries": ("supermarket", "convenience"),
    "supermarket": ("supermarket",),
    "store": ("supermarket", "convenience", "department_store", "variety_store"),
    "shop": ("supermarket", "convenience", "department_store"),
    "convenience": ("convenience",),
    "pharmacy": ("pharmacy", "chemist"),
    "chemist": ("pharmacy", "chemist"),
    "hardware": ("hardware", "doityourself"),
    "bike shop": ("bicycle",),
    "bicycle shop": ("bicycle",),
    "outdoor": ("outdoor", "sports"),
    "gear": ("outdoor", "sports"),
    "bookstore": ("books",),
    "books": ("books",),
    "liquor": ("alcohol",),

    # ── sleeping ─────────────────────────────────────────────────────────────
    "hotel": ("hotel", "motel", "guest_house", "hostel"),
    "motel": ("motel", "hotel"),
    "lodging": ("hotel", "motel", "guest_house", "hostel", "chalet"),
    "hostel": ("hostel",),
    "camping": ("camp_site", "camp_pitch", "caravan_site"),
    "campground": ("camp_site", "caravan_site"),
    "campsite": ("camp_site", "camp_pitch"),
    "camp": ("camp_site", "camp_pitch"),
    "rv": ("caravan_site",),
    "hut": ("wilderness_hut", "alpine_hut", "shelter", "chalet"),
    "shelter": ("shelter", "wilderness_hut", "alpine_hut"),
    "bivy": ("shelter", "wilderness_hut"),

    # ── help ─────────────────────────────────────────────────────────────────
    "hospital": ("hospital", "clinic", "doctors"),
    "emergency": ("hospital", "clinic", "fire_station", "police"),
    "er": ("hospital",),
    "clinic": ("clinic", "doctors"),
    "doctor": ("doctors", "clinic"),
    "dentist": ("dentist",),
    "vet": ("veterinary",),
    "veterinary": ("veterinary",),
    "police": ("police",),
    "fire station": ("fire_station",),
    "ranger": ("ranger_station",),
    "ranger station": ("ranger_station",),
    "information": ("information",),
    "visitor center": ("information",),

    # ── outdoors, the reason this app exists ─────────────────────────────────
    "trailhead": ("trailhead",),
    "trailheads": ("trailhead",),
    "trail": ("trailhead", "path", "track"),
    "peak": ("peak", "Summit"),
    "peaks": ("peak", "Summit"),
    "summit": ("peak", "Summit"),
    "mountain": ("peak", "Summit", "Range"),
    "mountains": ("peak", "Summit", "Range"),
    "lake": ("lake", "Lake", "reservoir", "Reservoir"),
    "lakes": ("lake", "Lake", "reservoir", "Reservoir"),
    "reservoir": ("reservoir", "Reservoir"),
    "river": ("river", "Stream"),
    "creek": ("Stream", "stream"),
    "stream": ("Stream", "stream"),
    "waterfall": ("waterfall", "Falls"),
    "waterfalls": ("waterfall", "Falls"),
    "falls": ("waterfall", "Falls"),
    "hot spring": ("hot_spring", "Spring", "spring"),
    "hot springs": ("hot_spring", "Spring", "spring"),
    "spring": ("spring", "Spring"),
    "glacier": ("glacier", "Glacier"),
    "canyon": ("Valley", "valley"),
    "valley": ("Valley", "valley"),
    "pass": ("Gap", "saddle", "gap"),
    "saddle": ("saddle", "Gap"),
    "viewpoint": ("viewpoint",),
    "overlook": ("viewpoint",),
    "view": ("viewpoint",),
    "picnic": ("picnic_site",),
    "picnic area": ("picnic_site",),
    "beach": ("beach", "Beach"),
    "cave": ("cave_entrance", "Cave"),
    "climbing": ("climbing",),
    "crag": ("climbing",),
    "boat ramp": ("boat_ramp", "slipway"),
    "put in": ("boat_ramp", "slipway"),
    "fishing": ("fishing",),
    "ski": ("piste", "ski_school", "chair_lift"),
    "golf": ("golf_course",),
    "playground": ("playground",),
    "park": ("park", "nature_reserve", "protected_area"),
    "dog park": ("dog_park",),
    "swimming": ("swimming_pool", "beach"),
    "pool": ("swimming_pool",),

    # ── civic ────────────────────────────────────────────────────────────────
    "school": ("school", "college", "university", "kindergarten"),
    "university": ("university", "college"),
    "library": ("library",),
    "museum": ("museum",),
    "gallery": ("gallery", "artwork"),
    "theatre": ("theatre", "cinema"),
    "cinema": ("cinema",),
    "movie": ("cinema",),
    "church": ("place_of_worship", "christian"),
    "worship": ("place_of_worship",),
    "cemetery": ("cemetery", "grave_yard"),
    "townhall": ("townhall",),
    "gym": ("fitness_centre", "sports_centre"),
    "airport": ("aerodrome", "helipad"),
    "airfield": ("aerodrome",),
    "bus stop": ("bus_stop",),
    "bus": ("bus_stop", "station"),
    "train": ("station", "platform"),
    "car repair": ("car_repair", "car_parts", "tyres"),
    "mechanic": ("car_repair",),
    "car wash": ("car_wash",),
}

# Longest keys first, so "gas station" and "hot spring" win over "gas"/"spring".
_ORDERED_KEYS = sorted(_CATEGORIES, key=lambda k: (-len(k.split()), -len(k)))

# Words that carry no meaning of their own in a query like "find coffee near me"
# or "any gas around here".
_FILLER = {
    "a", "an", "the", "any", "some", "find", "show", "me", "nearby", "near",
    "around", "here", "closest", "nearest", "close", "by", "to", "for", "of",
    "please", "where", "is", "are", "good", "best", "open", "place", "places",
    "spot", "spots",
}


def _normalise(q: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", " ", q.lower()).strip()


def _strip_filler(q: str) -> str:
    return " ".join(w for w in q.split() if w not in _FILLER)


def match_category(q: str) -> tuple[str, ...] | None:
    """The kinds a query is asking for, or None if it names a place instead.

    Matches only when the *whole* query is the category, once filler words are
    removed — "coffee", "nearest coffee", "any coffee near me". A query that
    still has words left over is a name search: "coffee creek" is a place in
    California, not a request for cafes, and answering it with cafes would be
    the kind of confidently-wrong result this search tries hard to avoid.
    """
    stripped = _strip_filler(_normalise(q))
    if not stripped:
        return None
    for key in _ORDERED_KEYS:
        if stripped == key:
            return _CATEGORIES[key]
    return None


def category_terms() -> list[str]:
    """Every phrase the search understands as a category. For tests and docs."""
    return list(_CATEGORIES)
