# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Classify OSM trail/road ways into rich MVT properties + a length-graded zoom.

The Protomaps basemap has no surface/sac_scale/trail_visibility and few names;
OSM does. This module turns a way's raw tags into the logical `kind`/`use`/
surface/difficulty properties the map styles on, plus a per-feature `min_zoom`
graded by length so the network reads at overview zoom while low-zoom tiles stay
light. Pure functions — no fetching (see overpass_client / trail_fetch).
"""

from __future__ import annotations

from app.services.overpass_client import _approx_length_m

# Foot/bike/horse trails + 4×4 tracks PLUS drivable backcountry road classes
# (tertiary / unclassified / service) so downloaded regions carry a real
# light-duty + graded-road network the basemap only shows from z12.
TRAIL_HIGHWAYS = ("path", "track", "footway", "bridleway", "steps", "cycleway",
                  "service", "unclassified", "tertiary", "tertiary_link")
_PAVED = {"asphalt", "paved", "concrete", "paving_stones", "sett", "metal", "wood"}

# highway=service values that are parking/yard furniture, not roads worth showing.
_SERVICE_SKIP = {"driveway", "parking_aisle", "alley", "drive-through"}

# Access-tag value buckets for the "highest permitted use" classification. A YES
# anywhere in a mode's key group wins; an explicit NO only matters absent a YES.
_ACCESS_YES = {"yes", "designated", "permissive", "destination", "official",
               "customers", "permit", "discouraged"}
_ACCESS_NO = {"no", "private"}

# Hiking/foot route relations → min_zoom by network importance (per-region routes
# still use these; the global long-trail overview forces a uniform floor instead).
ROUTE_NETWORKS = {"iwn": 3, "nwn": 3, "rwn": 5, "lwn": 7}


def _mode_allowed(tags: dict, keys: tuple[str, ...], default: bool) -> bool:
    """Is a travel mode permitted? YES in any of `keys` wins over an explicit NO."""
    vals = [tags.get(k) for k in keys]
    if any(v in _ACCESS_YES for v in vals):
        return True
    if any(v in _ACCESS_NO for v in vals):
        return False
    return default


def trail_use(tags: dict, hw: str) -> str:
    """Gaia-style colour class = the highest-impact permitted travel mode.

    foot < horse < bike < moto; `track` (vehicle-width road) is its own class so
    it can be drawn as a USGS two-track regardless of who's allowed on it.
    """
    if hw == "track":
        return "track"
    motor = _mode_allowed(tags, ("motor_vehicle", "motorcar", "motorcycle", "atv", "ohv"), False)
    bike = (_mode_allowed(tags, ("bicycle", "mtb"), hw == "cycleway")
            or bool(tags.get("mtb:scale")))
    horse = _mode_allowed(tags, ("horse",), hw == "bridleway")
    if motor:
        return "moto"
    if bike:
        return "bike"
    if horse:
        return "horse"
    return "foot"


def classify(tags: dict, length_m: float = 0.0) -> tuple[dict, int]:
    """Return (mvt_properties, min_zoom) for a trail/road way's OSM tags.

    `kind` is a *logical* class the frontend styles on, not the raw highway tag:
      • track     = rough two-track / 4×4 road (ungraded/grade3-5/4wd_only)
      • service   = graded UNPAVED drivable backcountry road (USGS double-dash)
      • lightduty = PAVED light-duty drivable road (solid USGS topo-yellow road)
    `length_m` grades min_zoom so long lines appear at z9 and connectors fill in
    by z11-12, keeping low-zoom tiles light.
    """
    hw = tags.get("highway")
    name = tags.get("name") or ""
    surface = tags.get("surface") or ""
    tracktype = tags.get("tracktype", "")

    if hw == "track":
        graded = (tracktype in ("grade1", "grade2")
                  and tags.get("4wd_only") not in ("yes", "recommended"))
        kind = "service" if graded else "track"
    elif hw in ("tertiary", "tertiary_link"):
        kind = "lightduty"
    elif hw in ("service", "unclassified"):
        kind = "lightduty" if surface in _PAVED else "service"
    else:
        kind = hw

    if kind in ("service", "lightduty"):
        mz = 9 if length_m >= 1500 else 10
    elif kind == "track":
        mz = 9 if length_m >= 2500 else (10 if length_m >= 800 else 11)
    elif kind == "path":
        mz = (9 if length_m >= 1500 else 10 if length_m >= 600
              else 11 if length_m >= 150 else 12)
    elif kind in ("bridleway", "cycleway"):
        mz = 10 if length_m >= 1200 else 11
    elif kind == "footway":
        mz = 12
    elif kind == "steps":
        mz = 14
    else:
        mz = 12
    if name and kind in ("path", "track", "service", "lightduty", "bridleway", "cycleway"):
        mz = min(mz, 9)

    props = {
        "kind": kind,
        "use": trail_use(tags, hw),
        "name": name,
        "surface": surface,
        "paved": 1 if surface in _PAVED else (0 if surface else -1),  # -1 = unknown
        "sac_scale": tags.get("sac_scale", ""),
        "trail_visibility": tags.get("trail_visibility", ""),
        "tracktype": tracktype,
        "bridge": 1 if tags.get("bridge") in ("yes", "viaduct") else 0,
        "oneway": 1 if tags.get("oneway") in ("yes", "true", "1", "-1") else 0,
        "min_zoom": mz,
    }
    return props, mz


def classify_feature(tags: dict, geom) -> tuple[dict, int] | None:
    """Streaming-path classify for ONE LineString trail/road way → (props,
    min_zoom) or None to skip. Used by the single-pass osmium-export builder."""
    hw = tags.get("highway")
    if hw not in TRAIL_HIGHWAYS:
        return None
    if tags.get("footway") in ("sidewalk", "crossing", "access_aisle", "traffic_island"):
        return None
    if hw == "service" and tags.get("service") in _SERVICE_SKIP:
        return None
    return classify(tags, _approx_length_m(geom))
