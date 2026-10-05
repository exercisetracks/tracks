# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Saved places: CRUD, and the flag that asks for one on the watch."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.waypoint import Waypoint

router = APIRouter(prefix="/maps/waypoints", tags=["maps"])

_MAX_NAME = 80


class WaypointIn(BaseModel):
    name: str = Field(default="Waypoint", max_length=_MAX_NAME)
    lat: float = Field(..., ge=-90, le=90)
    lng: float = Field(..., ge=-180, le=180)
    ele_m: float | None = None
    # See the note on Waypoint.color: the symbol is drawn in this, so an
    # unstyled place should be ink rather than highlighted.
    color: str = "#0f172a"
    icon: str = "marker"
    notes: str | None = None
    load_to_device: bool = False


class WaypointUpdate(BaseModel):
    """Every field optional; omitted means "leave it alone" rather than "clear it"."""

    name: str | None = Field(default=None, max_length=_MAX_NAME)
    lat: float | None = Field(default=None, ge=-90, le=90)
    lng: float | None = Field(default=None, ge=-180, le=180)
    ele_m: float | None = None
    color: str | None = None
    icon: str | None = None
    notes: str | None = None
    load_to_device: bool | None = None


def _out(w: Waypoint) -> dict:
    return {
        "id": w.id,
        "name": w.name,
        "lat": w.lat,
        "lng": w.lng,
        "ele_m": w.ele_m,
        "color": w.color,
        "icon": w.icon,
        "notes": w.notes,
        "load_to_device": w.load_to_device,
        # Desired state and actual state are both reported, because "asked for"
        # and "on the wrist" are different answers and the UI has to be able to
        # say which one it is showing.
        "on_watch": w.watch_uploaded_at is not None and w.watch_deleted_at is None,
    }


def _owned_or_404(waypoint_id: int, user: User, db: Session) -> Waypoint:
    w = db.get(Waypoint, waypoint_id)
    if w is None or w.user_id != user.id:
        raise HTTPException(404, "Waypoint not found")
    return w


@router.get("")
def list_waypoints(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    rows = (
        db.query(Waypoint)
        .filter(Waypoint.user_id == user.id)
        .order_by(Waypoint.created_at.desc())
        .all()
    )
    return [_out(w) for w in rows]


@router.post("", status_code=201)
def create_waypoint(
    body: WaypointIn,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = Waypoint(
        user_id=user.id,
        name=(body.name or "Waypoint").strip()[:_MAX_NAME] or "Waypoint",
        lat=body.lat,
        lng=body.lng,
        ele_m=body.ele_m,
        color=body.color,
        icon=body.icon,
        notes=body.notes,
        load_to_device=body.load_to_device,
    )
    db.add(w)
    db.commit()
    db.refresh(w)
    return _out(w)


@router.patch("/{waypoint_id}")
def update_waypoint(
    waypoint_id: int,
    body: WaypointUpdate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = _owned_or_404(waypoint_id, user, db)
    on_device = w.watch_uploaded_at is not None and w.watch_deleted_at is None

    if body.name is not None:
        w.name = body.name.strip()[:_MAX_NAME] or w.name
    if body.lat is not None:
        w.lat = body.lat
    if body.lng is not None:
        w.lng = body.lng
    if body.ele_m is not None:
        w.ele_m = body.ele_m
    if body.color is not None:
        w.color = body.color
    if body.icon is not None:
        w.icon = body.icon
    if body.notes is not None:
        w.notes = body.notes
    if body.load_to_device is not None:
        w.load_to_device = body.load_to_device

    # Anything the watch can see changed, so the copy on it is stale. Clearing
    # the upload stamp is what puts it back on the next sync's list — the same
    # rule a course follows when its geometry is edited.
    if on_device and any(
        v is not None for v in (body.name, body.lat, body.lng, body.ele_m, body.icon)
    ):
        w.watch_uploaded_at = None

    db.commit()
    db.refresh(w)
    return _out(w)


@router.delete("/{waypoint_id}", status_code=204)
def delete_waypoint(
    waypoint_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = _owned_or_404(waypoint_id, user, db)
    db.delete(w)
    db.commit()
