# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build and maintain the POI search index.

Sources (in priority order):
  1. GNIS (USGS Geographic Names) — authoritative US natural features; never wiped by reindex
  2. Natural Earth populated places — global cities with accurate centroids
  3. Regional master.pmtiles — local OSM POIs (trailheads, cafes, etc.) from downloaded areas

The planet_z7.pmtiles global overview is intentionally NOT indexed because its POI
coordinates are tile-centroid approximations, not authoritative point locations.
"""

from __future__ import annotations

import json
import logging
import math
import gzip
import urllib.request
from pathlib import Path
from typing import Generator

from mapbox_vector_tile import decode
from pmtiles.reader import Reader, MmapSource, traverse
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.database import SessionLocal
from app.models.poi_search import PoiSearch

logger = logging.getLogger(__name__)

TILE_EXTENT = 4096
LAYERS = frozenset({"pois", "places"})
BATCH_SIZE = 2000
# Only index regional-exclusive high-zoom tiles. The global basemap overview now
# spans z0-12, so its z8-12 tiles carry coarse/place-label POIs at label
# positions (inaccurate) AND would force a full ~17 GB scan on every reindex.
# Regional extracts are the only source of z13+ tiles, and any POI present at
# z12 is also present at z13+, so z13 loses no accurate POIs. Global place search
# is served separately by Natural Earth centroids.
MIN_TILE_ZOOM = 13

# Kinds from the basemap `places` source-layer that should NOT be extracted from
# vector tiles. Their coordinates are tile-label positions (not geographic centroids),
# so they are systematically inaccurate. Place labels are already rendered by the
# basemap's own label layers; adding them to poi_search would produce duplicates at
# wrong positions. Natural Earth provides accurate city centroids for search.
_SKIP_PLACE_KINDS = frozenset({
    "locality", "neighbourhood", "macrohood", "microhood",
    "administrative", "country", "region", "county", "borough",
    "localadmin", "pseudo",
})


# ── Tile geometry helpers ─────────────────────────────────────────────────────

def _tile_to_lnglat(z: int, x: int, y: int, px: float, py: float) -> tuple[float, float]:
    n = 2.0 ** z
    world_x = (x + px / TILE_EXTENT) / n
    world_y = (y + py / TILE_EXTENT) / n
    lng = world_x * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * world_y))))
    return (round(lng, 6), round(lat, 6))


def _extract_features(z: int, x: int, y: int, tile_data: bytes) -> Generator[dict, None, None]:
    # y_coord_down=True keeps the raw MVT y-axis (origin at the tile's north/top
    # edge, increasing southward), which is what _tile_to_lnglat expects. The
    # decoder's default (y_coord_down=False) flips y to GeoJSON "y-up", which
    # would vertically mirror every point within its tile and push POIs ~north.
    _decode_opts = {"y_coord_down": True}
    try:
        decoded = decode(tile_data, default_options=_decode_opts)
    except Exception:
        try:
            decoded = decode(gzip.decompress(tile_data), default_options=_decode_opts)
        except Exception:
            return

    for layer_name in LAYERS:
        if layer_name not in decoded:
            continue
        for feat in decoded[layer_name].get("features", []):
            props = feat.get("properties", {})
            name = props.get("name") or props.get("name:en")
            if not name:
                continue
            geom = feat.get("geometry")
            if not geom or geom.get("type") != "Point":
                continue
            coords = geom.get("coordinates")
            if not coords or len(coords) < 2:
                continue

            kind = props.get("kind")

            # Skip place-label kinds — their tile-pixel coordinates are rendering
            # positions, not geographic centroids, and are badly inaccurate.
            if layer_name == "places" and kind in _SKIP_PLACE_KINDS:
                continue

            px, py = float(coords[0]), float(coords[1])
            lng, lat = _tile_to_lnglat(z, x, y, px, py)

            yield {
                "osm_id": feat.get("id"),
                "name": name,
                "kind": kind,
                "kind_detail": props.get("kind_detail"),
                "lat": lat,
                "lng": lng,
                "source": layer_name,
                "min_zoom": props.get("min_zoom", 15),
            }


# ── PMTiles indexing (regional only) ─────────────────────────────────────────

def index_pmtiles(pmtiles_path: str, label: str = "",
                  skip_below_zoom: int = MIN_TILE_ZOOM) -> int:
    """Index named POIs from a PMTiles archive (z8+ only). Returns feature count."""
    path = Path(pmtiles_path)
    if not path.exists():
        logger.warning("PMTiles not found: %s", pmtiles_path)
        return 0

    logger.info("Indexing %s (%s) …", label or path.name, _fmt_size(path.stat().st_size))

    with open(path, "rb") as f:
        source = MmapSource(f)
        reader = Reader(source)
        try:
            header = reader.header()
        except Exception as exc:
            # An extraction that was cancelled or died mid-write leaves a file of
            # plausible size whose header was never finished, and pmtiles raises
            # MagicNumberNotFound on the first read. Skipping it is right: there
            # is nothing to index and the region it belongs to is not installed.
            # Raising was not — this runs inside the startup reindex thread, so
            # one abandoned download took down the indexing of every region after
            # it AND the global city index, and the only symptom was search
            # quietly missing places.
            logger.warning("Skipping unreadable PMTiles %s: %s",
                           label or path.name, exc)
            return 0

        batch: list[dict] = []
        seen_osm_ids: set[int] = set()
        total = 0

        for (z, x, y), tile_data in traverse(
            source, header, header["root_offset"], header["root_length"]
        ):
            if z < skip_below_zoom:
                continue
            for feature in _extract_features(z, x, y, tile_data):
                osm_id = feature["osm_id"]
                if osm_id is not None:
                    if osm_id in seen_osm_ids:
                        continue
                    seen_osm_ids.add(osm_id)
                batch.append(feature)
                if len(batch) >= BATCH_SIZE:
                    total += _flush_batch(batch)
                    batch = []

        if batch:
            total += _flush_batch(batch)

    logger.info("Indexed %d features from %s", total, label or path.name)
    return total


# ── Batch insert ──────────────────────────────────────────────────────────────

def _flush_batch(rows: list[dict]) -> int:
    """Upsert a batch of POI rows, skipping osm_id conflicts."""
    db = SessionLocal()
    try:
        stmt = pg_insert(PoiSearch).values(rows).on_conflict_do_nothing()
        db.execute(stmt)
        db.commit()
        return len(rows)
    except Exception as exc:
        db.rollback()
        logger.warning("Batch insert failed (%d rows): %s", len(rows), exc)
        return 0
    finally:
        db.close()


# ── Natural Earth ─────────────────────────────────────────────────────────────

NATURAL_EARTH_URL = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/"
    "master/geojson/ne_10m_populated_places.geojson"
)
NATURAL_EARTH_FILE = "ne_10m_populated_places.geojson"


def _download_natural_earth(data_dir: Path) -> Path | None:
    dest = data_dir / NATURAL_EARTH_FILE
    if dest.exists():
        try:
            with open(dest) as f:
                json.load(f)
            logger.info("Natural Earth data already present at %s", dest)
            return dest
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("Natural Earth GeoJSON corrupt — re-downloading")
            dest.unlink(missing_ok=True)

    dest.parent.mkdir(parents=True, exist_ok=True)
    logger.info("Downloading Natural Earth populated places …")
    try:
        urllib.request.urlretrieve(NATURAL_EARTH_URL, str(dest))
        logger.info("Downloaded %s (%.0f KB)", dest, dest.stat().st_size / 1024)
        return dest
    except Exception as e:
        logger.warning("Failed to download Natural Earth data: %s", e)
        dest.unlink(missing_ok=True)
        return None


def _ne_min_zoom(feature_class: str, pop: int) -> int:
    """Map Natural Earth feature class + population to a map min_zoom level.

    Lower min_zoom = more important = appears at lower zoom levels.
    """
    fc_lower = feature_class.lower()
    if "admin-0 capital" in fc_lower:
        return 2
    if "admin-1 capital" in fc_lower or "state capital" in fc_lower:
        return 4
    if pop > 1_000_000:
        return 3
    if pop > 500_000:
        return 4
    if pop > 100_000:
        return 5
    if pop > 50_000:
        return 6
    if pop > 10_000:
        return 7
    return 9


def index_natural_earth(data_dir: Path) -> int:
    path = _download_natural_earth(data_dir)
    if not path:
        return 0

    with open(path) as f:
        fc = json.load(f)

    rows = []
    for feat in fc.get("features", []):
        props = feat.get("properties", {})
        name = props.get("NAME")
        if not name:
            continue
        geom = feat.get("geometry", {})
        if geom.get("type") != "Point":
            continue
        coords = geom.get("coordinates")
        if not coords or len(coords) < 2:
            continue

        lng, lat = float(coords[0]), float(coords[1])
        feature_class = props.get("FEATURECLA", "")
        pop = int(props.get("POP_MAX") or 0)
        min_zoom = _ne_min_zoom(feature_class, pop)

        fc_lower = feature_class.lower()
        if "admin-0 capital" in fc_lower:
            kind_detail = "Capital"
        elif "admin-1 capital" in fc_lower or "state capital" in fc_lower:
            kind_detail = "State Capital"
        elif pop > 100_000:
            kind_detail = "City"
        else:
            kind_detail = "Town"

        rows.append({
            "osm_id": None,
            "name": name,
            "kind": "locality",
            "kind_detail": kind_detail,
            "lat": lat,
            "lng": lng,
            "source": "natural_earth",
            "min_zoom": min_zoom,
        })

    total = 0
    for i in range(0, len(rows), BATCH_SIZE):
        total += _flush_batch(rows[i:i + BATCH_SIZE])

    logger.info("Indexed %d Natural Earth cities into poi_search", total)
    return total


# ── Full reindex ──────────────────────────────────────────────────────────────

def full_reindex() -> None:
    """Rebuild the POI index from all non-GNIS sources.

    GNIS rows (source='gnis') are intentionally preserved — they represent a
    large, slow, manually-or-auto-triggered import that must not be wiped on
    every startup.
    """
    from app.config import settings

    db = SessionLocal()
    try:
        # Preserve GNIS and OSM-POI rows: both are expensive imports (GNIS bulk,
        # osm_poi via Overpass per region) rebuilt only on their own triggers, not
        # on every startup reindex.
        db.execute(text("DELETE FROM poi_search WHERE source NOT IN ('gnis', 'osm_poi')"))
        db.commit()
        logger.info("Cleared basemap/natural-earth poi_search rows")
    except Exception:
        db.rollback()
    finally:
        db.close()

    data_dir = Path(settings.map_data_dir)

    # Regional POIs come from the small per-region source archives (z13+, accurate
    # OSM coords) — NOT the 17 GB master. master's z13+ tiles are exactly the union
    # of these sources, so indexing them is equivalent but avoids a full-planet scan
    # (index_pmtiles on master reads all ~22M tiles before the z<13 filter).
    regions_dir = data_dir / "regions"
    if regions_dir.is_dir():
        for region_dir in sorted(regions_dir.iterdir()):
            src = region_dir / "source.pmtiles"
            if not src.exists():
                continue
            # Per region, so a corrupt or half-written archive costs its own
            # POIs and nothing else. index_pmtiles already handles the common
            # case (an unfinished header); this catches the rest.
            try:
                index_pmtiles(str(src), label=f"region {region_dir.name}",
                              skip_below_zoom=MIN_TILE_ZOOM)
            except Exception:
                logger.exception("Region %s failed to index; continuing",
                                 region_dir.name)

    # Global city index with authoritative centroids
    index_natural_earth(data_dir)

    # Note: contours are NOT regenerated here. They depend on the DEM, not the
    # POI index, and are handled by the startup thread / rebuild_master_dem().

    logger.info("Full POI reindex complete")


# ── Helpers ───────────────────────────────────────────────────────────────────

def _fmt_size(bytes_: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if bytes_ < 1024:
            return f"{bytes_:.0f} {unit}"
        bytes_ /= 1024
    return f"{bytes_:.1f} GB"
