# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Display-label helpers for POI features.

Maps the internal ``kind`` codes (locality, peak, trailhead, …) to the
human-readable category strings shown in the geocoder, plus the two tuning
constants the endpoints share.
"""

from __future__ import annotations

# Categories we suppress in the display name — too generic to be useful.
_SUPPRESS_CATEGORY = {"City", "Town"}

# Render icon POIs this many zoom levels earlier than their stored min_zoom, so
# named features reveal sooner as the user zooms in. A value of 2 means a POI
# tagged min_zoom=14 starts showing at map zoom 12. Tune down to 1 for a denser
# reveal curve, 0 to disable.
ICON_ZOOM_LEAD = 2

_KIND_LABELS: dict[str, str] = {
    "locality": "Place",
    "administrative": "Place",
    "neighbourhood": "Neighborhood",
    "country": "Country",
    "region": "Region",
    "county": "County",
    "park": "Park",
    "national_park": "National Park",
    "nature_reserve": "Nature Reserve",
    "protected_area": "Protected Area",
    "forest": "Forest",
    "peak": "Peak",
    "ridge": "Ridge",
    "valley": "Valley",
    "basin": "Basin",
    "plain": "Plain",
    "flat": "Flat",
    "lake": "Lake",
    "reservoir": "Reservoir",
    "stream": "Stream",
    "river": "River",
    "spring": "Spring",
    "well": "Well",
    "bay": "Bay",
    "channel": "Channel",
    "cape": "Cape",
    "pillar": "Pillar",
    "arch": "Arch",
    "area": "Area",
    "beach": "Beach",
    "wetland": "Wetland",
    "desert": "Desert",
    "glacier": "Glacier",
    "lava": "Lava Flow",
    "crater": "Crater",
    "cliff": "Cliff",
    "cave": "Cave",
    "mine": "Mine",
    "mine_shaft": "Mine Shaft",
    "quarry": "Quarry",
    "tank": "Tank",
    "gaging_station": "Gaging Station",
    "benchmark": "Benchmark",
    "spot_elevation": "Spot Elevation",
    "boat_ramp": "Boat Ramp",
    "falls": "Falls",
    "winter_rec": "Winter Recreation",
    "monument": "Monument",
    "hot_spring": "Hot Spring",
    "oil_field": "Oil Field",
    "gas_field": "Gas Field",
    "dam": "Dam",
    "bridge": "Bridge",
    "tunnel": "Tunnel",
    "harbor": "Harbor",
    "crossing": "Crossing",
    "ford": "Ford",
    "post_office": "Post Office",
    "cemetery": "Cemetery",
    "camp_site": "Campground",
    "alpine_hut": "Alpine Hut",
    "wilderness_hut": "Wilderness Hut",
    "viewpoint": "Viewpoint",
    "information": "Information",
    "shelter": "Shelter",
    "picnic_site": "Picnic Site",
    "ranger_station": "Ranger Station",
    "hospital": "Hospital",
    "school": "School",
    "university": "University",
    "college": "College",
    "library": "Library",
    "place_of_worship": "Place of Worship",
    "restaurant": "Restaurant",
    "fast_food": "Fast Food",
    "cafe": "Cafe",
    "pub": "Pub",
    "bar": "Bar",
    "supermarket": "Supermarket",
    "convenience": "Convenience Store",
    "fuel": "Fuel Station",
    "parking": "Parking",
    "bus_stop": "Bus Stop",
    "station": "Station",
    "aerodrome": "Airport",
    "railway": "Railway Station",
    "ferry": "Ferry",
    "tower": "Tower",
    "trail": "Trail",
    "trailhead": "Trailhead",
    "levee": "Levee",
    "pumping_plant": "Pumping Plant",
    "wastewater": "Wastewater Plant",
    "power_plant": "Power Plant",
    "substation": "Substation",
    "landing": "Landing",
    "locale": "Locale",
    "pseudo": "Pseudo",
    "gap": "Gap",
    "arroyo": "Arroyo",
    "rapids": "Rapids",
    "island": "Island",
    "canal": "Canal",
    "range": "Range",
}


def _kind_label(kind: str | None, kind_detail: str | None = None) -> str:
    if not kind:
        return ""
    return _KIND_LABELS.get(kind, kind.replace("_", " ").title())
