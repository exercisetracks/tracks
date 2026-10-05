# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Heatmap + activity-track GeoJSON endpoints.

`/activities/heatmap` returns anonymous simplified geometry for the WebGL glow
layer (served from the Redis-backed cache in heatmap_cache.py for unfiltered/
sport-only queries; date-filtered queries compute on the fly). `/activities/
tracks-geojson` returns the same tracks as a queryable FeatureCollection (each
line carries its activity id) for click-to-inspect on the map.
"""

import json
from datetime import date, timedelta
from itertools import groupby

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, DataPoint, User
from app.services.crypto_context import require_crypto_session

from .helpers import _claimed_device_ids
from .heatmap_cache import (
    _HEATMAP_MODES,
    _SIMPLIFY_MIN_M,
    _build_heatmap_bytes,
    _simplify_pts,
    _value_col,
    filter_tracks_bbox,
    get_cached_heatmap,
    set_cached_heatmap,
    spacing_for_zoom,
)

router = APIRouter()

_BBOX_DESC = "Viewport as min_lng,min_lat,max_lng,max_lat — returns only tracks passing through it"
_ZOOM_DESC = "Map zoom level; drops detail finer than one screen pixel at that zoom"


def _parse_bbox(raw: str | None) -> tuple[float, float, float, float] | None:
    """Parse a `min_lng,min_lat,max_lng,max_lat` string, or raise 422."""
    if raw is None:
        return None
    try:
        min_lng, min_lat, max_lng, max_lat = (float(p) for p in raw.split(","))
    except ValueError:
        raise HTTPException(
            status_code=422,
            detail="bbox must be four comma-separated numbers: min_lng,min_lat,max_lng,max_lat",
        )
    if not (-180 <= min_lng <= 180 and -180 <= max_lng <= 180):
        raise HTTPException(status_code=422, detail="bbox longitudes must be within [-180, 180]")
    if not (-90 <= min_lat <= 90 and -90 <= max_lat <= 90):
        raise HTTPException(status_code=422, detail="bbox latitudes must be within [-90, 90]")
    if min_lng > max_lng or min_lat > max_lat:
        raise HTTPException(status_code=422, detail="bbox min values must not exceed max values")
    return (min_lng, min_lat, max_lng, max_lat)


@router.get("/heatmap")
def heatmap(
    mode: str = Query("frequency", description="frequency | pace | heartrate | gradient"),
    sport: str | None = Query(None, description="Filter by sport"),
    after: date | None = Query(None),
    before: date | None = Query(None),
    bbox: str | None = Query(None, description=_BBOX_DESC),
    zoom: float | None = Query(None, ge=0, le=22, description=_ZOOM_DESC),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    _key=Depends(require_crypto_session),
):
    """Anonymous simplified track geometry for the heatmap layer.

    Unbounded, this returns the caller's entire GPS history in one body — fine
    for a desktop on a LAN, hostile to a phone on cellular. `bbox` and `zoom`
    exist to bound it: the first drops tracks outside the viewport, the second
    drops detail too fine to see at that scale.

    Both are applied *after* decryption rather than in SQL. `data_points.lat/lng`
    are AES-GCM columns with a random nonce per value, so they can't be compared
    or indexed by the database (see app.models.encrypted_columns). That means
    neither filter saves the server any work — but both save the client a great
    deal of transfer, which is the actual constraint. It also means bbox queries
    can still be served from the cached full payload, which is why the cache
    lookup below happens before the filtering rather than being skipped.
    """
    if mode not in _HEATMAP_MODES:
        mode = "frequency"

    box = _parse_bbox(bbox)
    min_spacing = spacing_for_zoom(zoom) if zoom is not None else None

    def shape(tracks: list) -> list:
        if box is not None:
            tracks = filter_tracks_bbox(tracks, box)
        if min_spacing is not None:
            tracks = [s for s in (_simplify_pts(t, min_spacing) for t in tracks) if len(s) >= 2]
        return tracks

    # Cache covers unfiltered + sport-only queries.
    # Date-range filters always compute on the fly.
    if after is None and before is None:
        cached = get_cached_heatmap(user.id, mode, sport or "")
        if cached is not None:
            if box is None and min_spacing is None:
                return Response(content=cached, media_type="application/json")
            # Narrowing the cached payload still beats recomputing it: the
            # alternative is re-reading and re-decrypting every data point.
            return shape(json.loads(cached))

    claimed_ids = _claimed_device_ids(db, user.id)

    # Unfiltered / sport-only cache miss: compute and store the FULL payload so
    # subsequent requests are instant, then narrow the copy we return. Caching
    # the narrowed version would poison the cache for every other viewport.
    if after is None and before is None:
        payload = _build_heatmap_bytes(db, mode, sport or "", claimed_ids)
        set_cached_heatmap(user.id, mode, sport or "", payload)
        if box is None and min_spacing is None:
            return Response(content=payload, media_type="application/json")
        return shape(json.loads(payload))

    # Date-filtered — compute on the fly without caching (data is dynamic)
    val_col = _value_col(mode)
    cols = [DataPoint.activity_id, DataPoint.lat, DataPoint.lng]
    if val_col is not None:
        cols.append(val_col)

    q = (
        db.query(*cols)
        .join(Activity, DataPoint.activity_id == Activity.id)
        .filter(DataPoint.lat.isnot(None), DataPoint.lng.isnot(None))
        .filter(Activity.device_id.in_(claimed_ids))
        .order_by(DataPoint.activity_id, DataPoint.recorded_at)
    )
    if val_col is not None:
        q = q.filter(val_col.isnot(None))
    if sport:
        q = q.filter(Activity.sport == sport.strip().lower())
    if after:
        q = q.filter(Activity.started_at >= after)
    if before:
        q = q.filter(Activity.started_at < before + timedelta(days=1))

    tracks = []
    for _, grp in groupby(q.all(), key=lambda r: r.activity_id):
        grp = list(grp)
        if val_col is not None:
            pts = [[round(r.lat, 4), round(r.lng, 4), round(r[3], 3)] for r in grp]
        else:
            pts = [[round(r.lat, 4), round(r.lng, 4)] for r in grp]
        pts = _simplify_pts(pts, min_spacing or _SIMPLIFY_MIN_M)
        if len(pts) >= 2:
            tracks.append(pts)
    return filter_tracks_bbox(tracks, box) if box is not None else tracks


@router.get("/tracks-geojson")
def activity_tracks_geojson(
    sport: str | None = Query(None, description="Filter by sport"),
    limit: int = Query(400, ge=1, le=2000, description="Most-recent activities to include"),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    _key=Depends(require_crypto_session),
):
    """Past activities as a GeoJSON FeatureCollection of simplified LineStrings,
    each carrying its activity id + summary. Unlike /heatmap (anonymous geometry
    for the WebGL glow), this is queryable on the map: clicking near a line resolves
    the activity so it can be inspected or turned into a custom track."""
    claimed = _claimed_device_ids(db, user.id)
    if not claimed:
        return {"type": "FeatureCollection", "features": []}

    aq = (
        db.query(Activity.id, Activity.name, Activity.sport,
                 Activity.started_at, Activity.distance_meters)
        .filter(Activity.device_id.in_(claimed),
                Activity.distance_meters.isnot(None), Activity.distance_meters > 0)
    )
    if sport:
        aq = aq.filter(Activity.sport == sport.strip().lower())
    acts = {a.id: a for a in aq.order_by(Activity.started_at.desc()).limit(limit).all()}
    if not acts:
        return {"type": "FeatureCollection", "features": []}

    rows = (
        db.query(DataPoint.activity_id, DataPoint.lat, DataPoint.lng)
        .filter(DataPoint.activity_id.in_(list(acts.keys())),
                DataPoint.lat.isnot(None), DataPoint.lng.isnot(None))
        .order_by(DataPoint.activity_id, DataPoint.recorded_at)
        .all()
    )

    features = []
    for aid, grp in groupby(rows, key=lambda r: r.activity_id):
        pts = _simplify_pts([[round(r.lat, 5), round(r.lng, 5)] for r in grp])
        if len(pts) < 2:
            continue
        a = acts[aid]
        features.append({
            "type": "Feature", "id": aid,
            "properties": {
                "id": aid, "name": a.name, "sport": a.sport,
                "date": a.started_at.isoformat() if a.started_at else None,
                "distance_m": a.distance_meters,
            },
            "geometry": {"type": "LineString", "coordinates": [[p[1], p[0]] for p in pts]},
        })
    return {"type": "FeatureCollection", "features": features}
