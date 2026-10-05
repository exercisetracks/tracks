# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the courses API package.

Constants + palette, the owned-or-404 ownership guard, the CustomTrack
serializers (summary / detail / GeoJSON feature), the stat/turn recompute
helpers, and the activity-track loader — all used across the tracks / merge /
folders / sync sub-modules.
"""
from __future__ import annotations

import random

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.models.activity import DataPoint, User
from app.models.custom_track import CustomTrack
from app.services import course_fit
from app.services.course_turns import detect_turns

_DEFAULT_COLOR = "#2563eb"
_MAX_NAME = 80

# Preset track palette (mirrors TRACK_COLORS in the frontend). New tracks created
# without an explicit colour get a random one so a fresh batch is easy to tell apart.
_PALETTE = [
    "#2563eb", "#e11d48", "#16a34a", "#d97706", "#7c3aed",
    "#0891b2", "#db2777", "#ca8a04", "#475569", "#ea580c",
]


def _random_color() -> str:
    return random.choice(_PALETTE)


def _owned_or_404(track_id: int, user: User, db: Session) -> CustomTrack:
    t = db.get(CustomTrack, track_id)
    if t is None or t.user_id != user.id:
        raise HTTPException(404, "Track not found")
    return t


def _course_filename(t: CustomTrack) -> str:
    return f"TRK_{t.id}.fit"


def _device_status(t: CustomTrack) -> str:
    on_device = t.watch_uploaded_at is not None and t.watch_deleted_at is None
    if t.is_external:
        return "pending_remove" if t.purge_after_delete else "external"
    if on_device:
        return "pending_remove" if not t.load_to_device else "on_device"
    if t.load_to_device:
        return "pending_upload"
    return "off"


def _summary(t: CustomTrack) -> dict:
    return {
        "id": t.id, "name": t.name, "color": t.color, "sport": t.sport,
        "source": t.source, "distance_m": t.distance_m,
        "ascent_m": t.ascent_m, "descent_m": t.descent_m,
        "turn_by_turn": t.turn_by_turn, "is_external": t.is_external,
        "load_to_device": t.load_to_device, "device_status": _device_status(t),
        "folder_id": t.folder_id, "hidden": t.hidden,
        "bounds": t.bounds, "created_at": t.created_at.isoformat() if t.created_at else None,
    }


def _detail(t: CustomTrack) -> dict:
    return {**_summary(t), "geometry": t.geometry, "profile": t.profile,
            "course_points": t.course_points or [], "notes": t.notes,
            "activity_id": t.activity_id}


def _feature(t: CustomTrack) -> dict:
    return {
        "type": "Feature",
        "id": t.id,
        "properties": {
            "id": t.id, "name": t.name, "color": t.color,
            "on_device": t.watch_uploaded_at is not None and t.watch_deleted_at is None,
            "turn_by_turn": t.turn_by_turn, "is_external": t.is_external,
        },
        "geometry": {"type": "LineString",
                     "coordinates": [[c[0], c[1]] for c in (t.geometry or [])]},
    }


def _apply_stats(t: CustomTrack, coords: list[list[float]]) -> None:
    """Compute + store distance/ascent/descent/bounds/profile/geometry on a row."""
    stats = course_fit.compute_stats(coords)
    t.geometry = stats["coords"]
    t.distance_m = stats["distance_m"]
    t.ascent_m = stats["ascent_m"]
    t.descent_m = stats["descent_m"]
    t.bounds = stats["bounds"]
    t.profile = stats["profile"]


def _refresh_turns(t: CustomTrack) -> None:
    """(Re)compute cached course points iff this track is turn-by-turn."""
    t.course_points = detect_turns(t.geometry) if t.turn_by_turn else None


def _activity_coords(activity_id: int, db: Session) -> list[list[float]]:
    """[[lng,lat,ele], ...] from an activity's GPS track, ordered by record time."""
    rows = (db.query(DataPoint.lng, DataPoint.lat, DataPoint.altitude)
            .filter(DataPoint.activity_id == activity_id,
                    DataPoint.lat.isnot(None), DataPoint.lng.isnot(None))
            .order_by(DataPoint.recorded_at).all())
    return [[r.lng, r.lat, r.altitude] for r in rows]
