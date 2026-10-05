# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download and index USGS GNIS (Geographic Names Information System) data."""

import csv
import io
import logging
import zipfile
from typing import Optional

import httpx
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import insert as pg_insert

from app.database import SessionLocal
from app.models.poi_search import PoiSearch

logger = logging.getLogger(__name__)

# USGS retired the old geonames.usgs.gov/docs/stategaz/NationalFile.zip endpoint.
# GNIS is now distributed via The National Map S3 bucket as "DomesticNames".
# The file format changed: lowercase pipe-delimited columns, a UTF-8 BOM, and
# NO elevation column (the old ELEV_IN_FT field is gone from this product).
GNIS_URL = (
    "https://prd-tnm.s3.amazonaws.com/StagedProducts/GeographicNames/"
    "DomesticNames/DomesticNames_National_Text.zip"
)

# FIPS state-numeric → USPS abbreviation (the new file dropped state_alpha).
_FIPS_TO_STATE = {
    "01": "AL", "02": "AK", "04": "AZ", "05": "AR", "06": "CA", "08": "CO",
    "09": "CT", "10": "DE", "11": "DC", "12": "FL", "13": "GA", "15": "HI",
    "16": "ID", "17": "IL", "18": "IN", "19": "IA", "20": "KS", "21": "KY",
    "22": "LA", "23": "ME", "24": "MD", "25": "MA", "26": "MI", "27": "MN",
    "28": "MS", "29": "MO", "30": "MT", "31": "NE", "32": "NV", "33": "NH",
    "34": "NJ", "35": "NM", "36": "NY", "37": "NC", "38": "ND", "39": "OH",
    "40": "OK", "41": "OR", "42": "PA", "44": "RI", "45": "SC", "46": "SD",
    "47": "TN", "48": "TX", "49": "UT", "50": "VT", "51": "VA", "53": "WA",
    "54": "WV", "55": "WI", "56": "WY", "60": "AS", "66": "GU", "69": "MP",
    "72": "PR", "78": "VI",
}

FEATURE_CLASS_TO_KIND = {
    "Summit": "peak",
    "Mountain": "peak",
    "Ridge": "ridge",
    "Valley": "valley",
    "Basin": "basin",
    "Plain": "plain",
    "Flat": "flat",
    "Lake": "lake",
    "Reservoir": "reservoir",
    "Stream": "stream",
    "River": "river",
    "Creek": "stream",
    "Spring": "spring",
    "Well": "well",
    "Bay": "bay",
    "Gut": "channel",
    "Channel": "channel",
    "Cape": "cape",
    "Pillar": "pillar",
    "Arch": "arch",
    "Area": "area",
    "Park": "park",
    "Forest": "forest",
    "Reserve": "nature_reserve",
    "Wilderness": "protected_area",
    "Recreation Area": "park",
    "Beach": "beach",
    "Swamp": "wetland",
    "Marsh": "wetland",
    "Desert": "desert",
    "Glacier": "glacier",
    "Lava": "lava",
    "Crater": "crater",
    "Caldera": "crater",
    "Cliff": "cliff",
    "Cave": "cave",
    "Falls": "falls",
    "Mine": "mine",
    "Quarry": "mine",
    "Oil field": "oil_field",
    "Gas field": "gas_field",
    "Dam": "dam",
    "Bridge": "bridge",
    "Tunnel": "tunnel",
    "Building": "building",
    "Church": "place_of_worship",
    "School": "school",
    "Hospital": "hospital",
    "Airport": "aerodrome",
    "Harbor": "harbor",
    "Port": "harbor",
    "Crossing": "crossing",
    "Ford": "ford",
    "Post Office": "post_office",
    "Cemetery": "cemetery",
    "Camp": "camp_site",
    "Tower": "tower",
    "Lookout Tower": "viewpoint",
    "Observation Point": "viewpoint",
    "Trail": "trail",
    "Levee": "levee",
    "Pumping Plant": "pumping_plant",
    "Sewage Treatment Plant": "wastewater",
    "Hydroelectric Plant": "power_plant",
    "Nuclear Plant": "power_plant",
    "Power Plant": "power_plant",
    "Substation": "substation",
    "Landing": "landing",
    "Locale": "locale",
    "Civil": "administrative",
    "Populated Place": "locality",
    "Pseudo": "pseudo",
    # The actual DomesticNames product only carries ~39 distinct feature
    # classes (verified against the live file — it's a pure physical/
    # geographic gazetteer with no Airport/Mine/School/Church/Park/Forest
    # rows despite those kinds existing above for OSM-sourced POIs). These
    # are the additional classes that DO appear and matter for hiking:
    "Gap": "gap",              # mountain pass/saddle — a real trail waypoint
    "Arroyo": "arroyo",        # dry wash, common in desert-Southwest hiking
    "Rapids": "rapids",        # river hazard/feature
    "Island": "island",
    "Canal": "canal",
    "Range": "range",          # named mountain range — regional overview label
}


def download_gnis_zip() -> Optional[bytes]:
    """Download the GNIS NationalFile.zip from USGS."""
    logger.info("Downloading GNIS data from %s", GNIS_URL)
    try:
        resp = httpx.get(GNIS_URL, follow_redirects=True, timeout=300)
        resp.raise_for_status()
        logger.info("Downloaded %d bytes", len(resp.content))
        return resp.content
    except Exception as e:
        logger.error("Failed to download GNIS data: %s", e)
        return None


def _gnis_min_zoom(kind: str, ele_ft: float | None) -> int:
    """Return the min_zoom at which a GNIS feature should first appear.

    Mirrors USGS quad-sheet visibility rules: major summits appear at regional
    overview zoom (8-9), major water at z9-10, minor named features at z11-12.
    Lower value = appears at lower zoom = more important.
    """
    if kind == "peak":
        if ele_ft and ele_ft >= 14000:
            return 8   # 14ers — always visible
        if ele_ft and ele_ft >= 11000:
            return 9
        return 10
    if kind in ("ridge", "glacier", "crater", "range"):
        return 10
    if kind in ("valley", "basin", "plain", "flat"):
        return 9
    if kind in ("lake", "reservoir"):
        if ele_ft and ele_ft >= 10000:
            return 9
        return 10
    if kind in ("river", "bay", "channel", "canal"):
        return 9
    if kind in ("stream", "arroyo"):
        return 11
    if kind in ("spring", "well", "falls", "rapids"):
        return 11
    if kind in ("gap", "island"):
        return 10
    if kind in ("park", "national_park", "nature_reserve", "protected_area", "forest"):
        return 9
    if kind in ("area",):
        return 10
    if kind in ("cape", "cliff", "pillar", "arch"):
        return 10
    if kind in ("locality",):
        return 10
    if kind in ("camp_site", "alpine_hut", "wilderness_hut"):
        return 11
    if kind == "viewpoint":
        return 11
    if kind == "aerodrome":
        return 15
    # "Culture" and "Mines & relief" groups — secondary map clutter, held
    # back to z12 (mirrors poi_builder._MIN_ZOOM for the same kinds).
    if kind in ("cave", "mine", "tower", "substation",
                "ranger_station", "place_of_worship", "school", "cemetery"):
        return 14
    return 12


def import_gnis_to_db(data: bytes, batch_size: int = 2000) -> int:
    """Parse GNIS zip and insert into poi_search table. Returns count of inserted rows."""
    count = 0
    db = SessionLocal()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            # The archive stores the data file under Text/DomesticNames_National.txt
            txt_names = [n for n in zf.namelist() if n.endswith(".txt")]
            if not txt_names:
                raise ValueError("No .txt data file found in GNIS archive")
            csv_filename = txt_names[0]

            with zf.open(csv_filename) as f:
                # utf-8-sig strips the BOM present at the start of the header line.
                text = io.TextIOWrapper(f, encoding="utf-8-sig", newline="")
                reader = csv.DictReader(text, delimiter="|")

                batch = []
                for row in reader:
                    # New DomesticNames schema uses lowercase column names and has
                    # NO elevation column.
                    feature_class = (row.get("feature_class") or "").strip()
                    kind = FEATURE_CLASS_TO_KIND.get(feature_class)
                    if not kind:
                        continue

                    name = (row.get("feature_name") or "").strip()
                    if not name:
                        continue

                    lat_str = (row.get("prim_lat_dec") or "").strip()
                    lng_str = (row.get("prim_long_dec") or "").strip()
                    if not lat_str or not lng_str:
                        continue

                    try:
                        lat = float(lat_str)
                        lng = float(lng_str)
                    except ValueError:
                        continue
                    # The file contains 0.0/0.0 placeholders for unlocated features.
                    if lat == 0.0 and lng == 0.0:
                        continue

                    gnis_id = (row.get("feature_id") or "").strip()
                    state_fips = (row.get("state_numeric") or "").strip()
                    state = _FIPS_TO_STATE.get(state_fips)
                    county = (row.get("county_name") or "").strip() or None

                    # Elevation is not in this product; peaks render name-only and
                    # min_zoom falls back to the no-elevation tier.
                    ele_ft = None

                    batch.append({
                        "gnis_id": int(gnis_id) if gnis_id else None,
                        "name": name,
                        "kind": kind,
                        "kind_detail": feature_class,
                        "lat": lat,
                        "lng": lng,
                        "source": "gnis",
                        "ele_ft": ele_ft,
                        "state": state,
                        "county": county,
                        "min_zoom": _gnis_min_zoom(kind, ele_ft),
                    })

                    if len(batch) >= batch_size:
                        count += _bulk_insert_gnis(db, batch)
                        batch = []

                if batch:
                    count += _bulk_insert_gnis(db, batch)

        db.commit()
        logger.info("Imported %d GNIS features", count)
    except Exception as e:
        db.rollback()
        logger.error("Failed to import GNIS data: %s", e)
        raise
    finally:
        db.close()

    return count


def _bulk_insert_gnis(db, rows):
    """Upsert GNIS features - deduplicate by gnis_id."""
    stmt = pg_insert(PoiSearch).values(rows)
    stmt = stmt.on_conflict_do_update(
        index_elements=['gnis_id'],
        # The uq_poi_search_gnis_id index is partial (WHERE gnis_id IS NOT NULL);
        # the conflict target must repeat that predicate to match it.
        index_where=PoiSearch.gnis_id.isnot(None),
        set_={
            'name': stmt.excluded.name,
            'kind': stmt.excluded.kind,
            'kind_detail': stmt.excluded.kind_detail,
            'lat': stmt.excluded.lat,
            'lng': stmt.excluded.lng,
            'ele_ft': stmt.excluded.ele_ft,
            'state': stmt.excluded.state,
            'county': stmt.excluded.county,
            'source': stmt.excluded.source,
            'min_zoom': stmt.excluded.min_zoom,
        }
    )
    db.execute(stmt)
    db.flush()
    return len(rows)


def needs_import() -> bool:
    """Return True if no GNIS features are present in the database."""
    db = SessionLocal()
    try:
        # Use EXISTS for efficiency — avoids full COUNT scan
        result = db.execute(
            text("SELECT EXISTS(SELECT 1 FROM poi_search WHERE source = 'gnis' LIMIT 1)")
        ).scalar()
        return not result
    finally:
        db.close()


def gnis_import_status() -> dict:
    """Return count of GNIS features in the database."""
    db = SessionLocal()
    try:
        count = db.execute(
            text("SELECT COUNT(*) FROM poi_search WHERE source = 'gnis'")
        ).scalar()
        return {"imported": count > 0, "count": count}
    finally:
        db.close()
