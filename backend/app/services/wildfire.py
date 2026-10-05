# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Live wildfire + smoke overlays, proxied and normalized server-side.

US fire locations and perimeters come from NIFC's public WFIGS ArcGIS feeds
(no API key); Canadian fires from NRCan's CWFIS M3 satellite-estimated
perimeters (GeoServer WFS, also keyless); smoke plumes from NOAA's Hazard
Mapping System daily KML. Everything is normalized to GeoJSON
FeatureCollections here so the frontend just feeds MapLibre geojson sources.

All feeds are COUNTRY-WIDE — the queries carry no location, viewport, or user
data, so browsing the map leaks nothing beyond the server's own IP making the
request. Responses are cached in-process (fires 10 min, smoke 30 min) so map
browsing doesn't hammer the upstream services.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from datetime import datetime, timedelta, timezone

import httpx

logger = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(connect=8.0, read=25.0, write=8.0, pool=8.0)

_WFIGS_BASE = ("https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services")
_INCIDENTS_URL = f"{_WFIGS_BASE}/WFIGS_Incident_Locations_Current/FeatureServer/0/query"
_PERIMETERS_URL = f"{_WFIGS_BASE}/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query"
# Canada: CWFIS M3 satellite-estimated fire perimeters (NRCan GeoServer WFS).
# Canada publishes no national named-incident feed with containment — M3 gives
# location, footprint, and hectares for everything currently burning.
_CWFIS_WFS_URL = "https://cwfis.cfs.nrcan.gc.ca/geoserver/public/ows"
_CWFIS_ACTIVE_DAYS = 7   # only fires with satellite detections this recent
# Daily KML, published per-UTC-day (today's file appears mid-day — fall back).
_HMS_SMOKE_URL = ("https://satepsanone.nesdis.noaa.gov/pub/FIRE/web/HMS/"
                  "Smoke_Polygons/KML/{y}/{m}/hms_smoke{ymd}.kml")

_FIRES_TTL = 600     # 10 min — WFIGS updates every ~5-15 min
_SMOKE_TTL = 1800    # 30 min — HMS is analyst-drawn, updated a few times a day

_EMPTY_FC = {"type": "FeatureCollection", "features": []}

# ── tiny in-process TTL cache ──────────────────────────────────────────────────
_cache: dict[str, tuple[float, dict]] = {}
_lock = threading.Lock()


def _cached(key: str, ttl: int, fn, empty=None) -> dict:
    """Serve `key` from cache within `ttl`; else rebuild via fn(). A failed
    rebuild falls back to the last stale value (better a 40-min-old fire map
    than none) and finally to `empty` (an empty collection)."""
    with _lock:
        hit = _cache.get(key)
        if hit and time.time() - hit[0] < ttl:
            return hit[1]
    try:
        data = fn()
    except Exception as exc:
        logger.warning("wildfire feed %s failed: %s", key, exc)
        data = None
    if data is None:
        return hit[1] if hit else (empty if empty is not None else _EMPTY_FC)
    with _lock:
        _cache[key] = (time.time(), data)
    return data


# ── WFIGS ArcGIS paging ────────────────────────────────────────────────────────

def _arcgis_geojson(url: str, params: dict, max_pages: int = 5) -> list[dict]:
    """All features of an ArcGIS query, following exceededTransferLimit pages."""
    feats: list[dict] = []
    offset = 0
    with httpx.Client(timeout=_TIMEOUT, follow_redirects=True) as client:
        for _ in range(max_pages):
            r = client.get(url, params={**params, "resultOffset": offset})
            r.raise_for_status()
            data = r.json()
            if "error" in data:
                raise RuntimeError(f"ArcGIS error: {data['error']}")
            page = data.get("features", [])
            feats.extend(page)
            if not data.get("properties", {}).get("exceededTransferLimit") or not page:
                break
            offset += len(page)
    return feats


def _fmt_acres(acres) -> str | None:
    if acres is None:
        return None
    return f"{acres:,.0f} ac"


def _fire_label(name, acres, containment) -> str:
    """Two-line map label: name on top, size · containment below."""
    bits = []
    if acres is not None:
        bits.append(_fmt_acres(acres))
    if containment is not None:
        bits.append(f"{containment:.0f}% contained")
    line2 = " · ".join(bits)
    return f"{name}\n{line2}" if line2 else (name or "")


def _build_us_incidents() -> dict:
    feats = _arcgis_geojson(_INCIDENTS_URL, {
        "where": "IncidentTypeCategory IN ('WF','CX')",
        "outFields": ("IncidentName,IncidentSize,PercentContained,"
                      "FireDiscoveryDateTime,ModifiedOnDateTime_dt,"
                      "POOState,FireBehaviorGeneral,IncidentTypeCategory"),
        "f": "geojson",
    })
    out = []
    for f in feats:
        if not f.get("geometry"):
            continue
        p = f.get("properties", {}) or {}
        name = (p.get("IncidentName") or "Unnamed fire").strip()
        acres = p.get("IncidentSize")
        containment = p.get("PercentContained")
        out.append({
            "type": "Feature",
            "geometry": f["geometry"],
            "properties": {
                "name": name,
                "acres": acres if acres is not None else 0,
                "acres_label": _fmt_acres(acres),
                "containment": containment,
                "discovered": p.get("FireDiscoveryDateTime"),
                "updated": p.get("ModifiedOnDateTime_dt"),
                "state": p.get("POOState"),
                "behavior": p.get("FireBehaviorGeneral"),
                "complex": p.get("IncidentTypeCategory") == "CX",
                "country": "US",
                "label": _fire_label(name, acres, containment),
            },
        })
    return {"type": "FeatureCollection", "features": out}


def _build_us_perimeters() -> dict:
    # Geometry simplified upstream (maxAllowableOffset ≈ 30 m) — perimeters at
    # full fidelity run to tens of MB.
    feats = _arcgis_geojson(_PERIMETERS_URL, {
        "where": "1=1",
        "outFields": ("attr_IncidentName,attr_IncidentSize,"
                      "attr_PercentContained,poly_GISAcres"),
        "maxAllowableOffset": 0.0003,
        "geometryPrecision": 5,
        "f": "geojson",
    })
    out = []
    for f in feats:
        if not f.get("geometry"):
            continue
        p = f.get("properties", {}) or {}
        name = (p.get("attr_IncidentName") or "Unnamed fire").strip()
        acres = p.get("attr_IncidentSize") or p.get("poly_GISAcres")
        out.append({
            "type": "Feature",
            "geometry": f["geometry"],
            "properties": {
                "name": name,
                "acres": acres if acres is not None else 0,
                "acres_label": _fmt_acres(acres),
                "containment": p.get("attr_PercentContained"),
                "country": "US",
            },
        })
    return {"type": "FeatureCollection", "features": out}


# ── Canada: CWFIS M3 satellite-estimated perimeters ────────────────────────────

_HA_TO_ACRES = 2.47105


def _iso_ms(iso: str | None) -> int | None:
    """'2026-06-27T09:15:00Z' → ms epoch (matching WFIGS's ms timestamps)."""
    if not iso:
        return None
    try:
        return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


def _round_ring(ring: list, nd: int = 4) -> list:
    """Round coords to `nd` decimals (~10 m) and drop the resulting duplicate
    consecutive vertices — the WFS ignores precision options, so the ~2.5 MB
    payload is slimmed here instead."""
    out = []
    for pt in ring:
        p = [round(pt[0], nd), round(pt[1], nd)]
        if not out or out[-1] != p:
            out.append(p)
    return out


def _geom_bbox_center(geom: dict) -> list | None:
    xs, ys = [], []
    polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
    for poly in polys:
        for x, y in poly[0]:
            xs.append(x)
            ys.append(y)
    if not xs:
        return None
    return [(min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2]


def _build_ca_fires() -> dict:
    """CWFIS M3 polygons → {points: [...], perimeters: [...]} feature lists.
    Satellite-derived: footprint + hectares + detection dates, but no incident
    name or containment (Canada publishes none nationally)."""
    since = (datetime.now(timezone.utc)
             - timedelta(days=_CWFIS_ACTIVE_DAYS)).strftime("%Y-%m-%dT00:00:00Z")
    r = httpx.get(_CWFIS_WFS_URL, params={
        "service": "WFS", "version": "2.0.0", "request": "GetFeature",
        "typeName": "public:m3_polygons_current",
        "outputFormat": "application/json",
        "srsName": "EPSG:4326",
        "cql_filter": f"lastdate AFTER {since}",
    }, timeout=_TIMEOUT, follow_redirects=True)
    r.raise_for_status()
    points, perims = [], []
    for f in r.json().get("features", []):
        geom = f.get("geometry")
        p = f.get("properties", {}) or {}
        if not geom or geom["type"] not in ("Polygon", "MultiPolygon"):
            continue
        if geom["type"] == "Polygon":
            geom = {"type": "Polygon",
                    "coordinates": [_round_ring(rg) for rg in geom["coordinates"]]}
        else:
            geom = {"type": "MultiPolygon",
                    "coordinates": [[_round_ring(rg) for rg in poly]
                                    for poly in geom["coordinates"]]}
        acres = round((p.get("area") or 0) * _HA_TO_ACRES)
        props = {
            "name": "Wildfire",       # M3 detections carry no incident name
            "acres": acres,
            "acres_label": _fmt_acres(acres),
            "containment": None,
            "discovered": _iso_ms(p.get("firstdate")),
            "updated": _iso_ms(p.get("lastdate")),
            "state": None,
            "behavior": None,
            "complex": False,
            "country": "CA",
            # No name — the dot is labelled by size alone, details in the panel.
            "label": _fmt_acres(acres) or "",
        }
        perims.append({"type": "Feature", "geometry": geom,
                       "properties": {**props, "label": None}})
        center = _geom_bbox_center(geom)
        if center:
            points.append({"type": "Feature",
                           "geometry": {"type": "Point", "coordinates": center},
                           "properties": props})
    return {"points": points, "perimeters": perims}


_EMPTY_CA = {"points": [], "perimeters": []}


def fetch_incidents() -> dict:
    """Active wildfire points (US named incidents + Canadian satellite
    detections) → GeoJSON FC with normalized props: name, acres, containment
    (0-100 | None), discovered/updated (ms epoch), state, behavior, country,
    label (preformatted for the map)."""
    us = _cached("us_incidents", _FIRES_TTL, _build_us_incidents)
    ca = _cached("ca_fires", _FIRES_TTL, _build_ca_fires, empty=_EMPTY_CA)
    return {"type": "FeatureCollection",
            "features": us["features"] + ca["points"]}


def fetch_perimeters() -> dict:
    """Current fire perimeter polygons (US WFIGS + Canada M3) → GeoJSON FC."""
    us = _cached("us_perimeters", _FIRES_TTL, _build_us_perimeters)
    ca = _cached("ca_fires", _FIRES_TTL, _build_ca_fires, empty=_EMPTY_CA)
    return {"type": "FeatureCollection",
            "features": us["features"] + ca["perimeters"]}


# ── NOAA HMS smoke KML → GeoJSON ───────────────────────────────────────────────

_PLACEMARK_RE = re.compile(r"<Placemark>(.*?)</Placemark>", re.S)
_DENSITY_RE = re.compile(r"Density:\s*(\w+)")
_TIME_RE = re.compile(r"(Start|End) Time:\s*([^<]+)")
_COORDS_RE = re.compile(r"<coordinates>(.*?)</coordinates>", re.S)


def _parse_smoke_kml(text: str) -> dict:
    feats = []
    for pm in _PLACEMARK_RE.findall(text):
        m = _DENSITY_RE.search(pm)
        density = (m.group(1) if m else "Light").lower()
        times = dict((k.lower(), v.strip()) for k, v in _TIME_RE.findall(pm))
        for block in _COORDS_RE.findall(pm):
            ring = []
            for triple in block.split():
                parts = triple.split(",")
                if len(parts) >= 2:
                    try:
                        ring.append([float(parts[0]), float(parts[1])])
                    except ValueError:
                        continue
            if len(ring) >= 4:
                feats.append({
                    "type": "Feature",
                    "geometry": {"type": "Polygon", "coordinates": [ring]},
                    "properties": {
                        "density": density,          # light | medium | heavy
                        "start": times.get("start"),
                        "end": times.get("end"),
                    },
                })
    return {"type": "FeatureCollection", "features": feats}


def fetch_smoke() -> dict:
    """NOAA HMS smoke plume polygons for today (UTC), falling back to
    yesterday until today's analysis is published."""
    def build():
        with httpx.Client(timeout=_TIMEOUT, follow_redirects=True) as client:
            for days_back in (0, 1):
                day = datetime.now(timezone.utc) - timedelta(days=days_back)
                url = _HMS_SMOKE_URL.format(
                    y=day.strftime("%Y"), m=day.strftime("%m"),
                    ymd=day.strftime("%Y%m%d"))
                r = client.get(url)
                if r.is_success:
                    fc = _parse_smoke_kml(r.text)
                    fc["properties"] = {"analysis_date": day.strftime("%Y-%m-%d")}
                    return fc
        logger.warning("HMS smoke KML unavailable for today and yesterday")
        return None

    return _cached("smoke", _SMOKE_TTL, build)
