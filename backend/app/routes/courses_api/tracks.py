# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Custom-track collection routes — the static `/courses` paths.

Listing (JSON + GeoJSON), creation (drawn coords / GeoJSON, file import,
from-activity), and the live turn-by-turn compatibility check. The dynamic
`/courses/{track_id}` routes live in detail.py so they register last.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, User
from app.models.custom_track import CustomTrack
from app.services import brouter_client, brouter_downloader, course_fit
from app.services.course_turns import is_turn_compatible
from app.services.crypto_context import require_crypto_session

from .helpers import (
    _MAX_NAME, _activity_coords, _apply_stats, _detail, _feature, _owned_or_404,
    _random_color, _refresh_turns, _summary,
)

log = logging.getLogger(__name__)

router = APIRouter()


@router.get("/courses")
def list_courses(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    tracks = (db.query(CustomTrack).filter(CustomTrack.user_id == user.id)
              .order_by(CustomTrack.created_at.desc()).all())
    return [_summary(t) for t in tracks]


@router.get("/courses/geojson")
def courses_geojson(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    tracks = (db.query(CustomTrack)
              .filter(CustomTrack.user_id == user.id)
              .filter(CustomTrack.hidden.is_(False))
              .filter(CustomTrack.geometry.isnot(None)).all())
    return {"type": "FeatureCollection",
            "features": [_feature(t) for t in tracks if t.geometry]}


class CourseCreate(BaseModel):
    name: str | None = None
    color: str | None = None
    sport: str | None = None
    source: str | None = "builder"
    turn_by_turn: bool = False
    load_to_device: bool = False
    # Either a list of [lng,lat,ele?] OR a GeoJSON Feature/FeatureCollection.
    coords: list | None = None
    geojson: dict | None = None


def _coords_from_geojson(gj: dict) -> list[list[float]]:
    feat = gj
    if gj.get("type") == "FeatureCollection":
        feats = gj.get("features") or []
        if not feats:
            return []
        feat = feats[0]
    geom = feat.get("geometry", feat) if isinstance(feat, dict) else {}
    return geom.get("coordinates", []) if geom.get("type") == "LineString" else []


@router.post("/courses", status_code=201)
def create_course(body: CourseCreate, user: User = Depends(require_auth),
                  db: Session = Depends(get_db)):
    coords = body.coords or (_coords_from_geojson(body.geojson) if body.geojson else [])
    if not coords or len(coords) < 2:
        raise HTTPException(400, "A track needs at least 2 coordinates")

    t = CustomTrack(
        user_id=user.id,
        name=(body.name or "Custom Track").strip()[:_MAX_NAME] or "Custom Track",
        color=body.color or _random_color(),
        sport=(body.sport or "hiking").lower(),
        source=body.source or "builder",
        turn_by_turn=bool(body.turn_by_turn),
        load_to_device=bool(body.load_to_device),
    )
    _apply_stats(t, coords)
    _refresh_turns(t)
    db.add(t)
    db.commit()
    db.refresh(t)
    return _detail(t)


@router.post("/courses/import", status_code=201)
async def import_course(file: UploadFile = File(...),
                        user: User = Depends(require_auth), db: Session = Depends(get_db)):
    raw = await file.read()
    fname = (file.filename or "").lower()
    try:
        if fname.endswith(".gpx"):
            parsed = course_fit.parse_gpx(raw.decode("utf-8", errors="replace"))
            source = "gpx"
        elif fname.endswith(".fit"):
            parsed = course_fit.parse_course_fit(raw)
            source = "fit"
        else:
            raise HTTPException(400, "Upload a .gpx or .fit file")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(400, f"Could not parse file: {exc}")

    t = CustomTrack(user_id=user.id,
                    name=(parsed["name"] or file.filename or "Imported")[:_MAX_NAME],
                    color=_random_color(), sport=parsed["sport"], source=source,
                    geometry=parsed["coords"], distance_m=parsed["distance_m"],
                    ascent_m=parsed["ascent_m"], descent_m=parsed["descent_m"],
                    bounds=parsed["bounds"], profile=parsed["profile"])
    db.add(t)
    db.commit()
    db.refresh(t)
    return _detail(t)


@router.post("/courses/from-activity/{activity_id}", status_code=201)
def course_from_activity(activity_id: int, user: User = Depends(require_auth),
                         db: Session = Depends(get_db),
                         _key=Depends(require_crypto_session)):
    act = db.get(Activity, activity_id)
    if act is None or (act.user_id is not None and act.user_id != user.id):
        raise HTTPException(404, "Activity not found")
    coords = _activity_coords(activity_id, db)
    if len(coords) < 2:
        raise HTTPException(400, "Activity has no GPS track")

    t = CustomTrack(user_id=user.id,
                    name=(act.name or "Activity Course")[:_MAX_NAME],
                    color=_random_color(), sport=(act.sport or "hiking").lower(),
                    source="activity", activity_id=activity_id)
    _apply_stats(t, coords)
    db.add(t)
    db.commit()
    db.refresh(t)
    return _detail(t)


class TurnCheck(BaseModel):
    coords: list | None = None
    track_id: int | None = None
    sport: str | None = None


@router.post("/courses/check-turns")
async def check_turns(body: TurnCheck, user: User = Depends(require_auth),
                      db: Session = Depends(get_db)):
    """Live turn-by-turn compatibility check. Snaps the track to the road/path
    network (BRouter) so a line that doesn't follow real ways is caught, then
    runs the geometric turn detector on the snapped geometry."""
    coords = body.coords
    if not coords and body.track_id is not None:
        t = _owned_or_404(body.track_id, user, db)
        coords = t.geometry
    coords = [c for c in (coords or []) if c and c[0] is not None and c[1] is not None]
    if len(coords) < 3:
        return {"compatible": False, "turn_count": 0,
                "reason": "Track is too short for turn-by-turn."}

    # Snap a downsampled set of waypoints to roads; if it routes cleanly and stays
    # close to the drawn line, it follows the network and turns are meaningful.
    snapped = coords
    try:
        step = max(1, len(coords) // 40)
        waypoints = coords[::step]
        if waypoints[-1] != coords[-1]:
            waypoints.append(coords[-1])
        wp = [[c[0], c[1]] for c in waypoints]
        lons = [c[0] for c in wp]; lats = [c[1] for c in wp]
        brouter_downloader.ensure_segments([min(lons), min(lats), max(lons), max(lats)])
        gj = await brouter_client.snap_route(wp, "trekking")
        line = gj.get("features", [{}])[0].get("geometry", {}).get("coordinates")
        if line and len(line) >= 3:
            snapped = line
    except Exception as exc:
        log.info("check-turns: snap failed, using raw geometry (%s)", exc)

    compatible, n, reason = is_turn_compatible(snapped)
    return {"compatible": compatible, "turn_count": n, "reason": reason}
