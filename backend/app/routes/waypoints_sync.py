# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Waypoint → watch reconciliation, and the garmin-sync routes that drive it.

Mirrors courses_api.sync in shape, but not in unit of work, because a watch
does not store one file per saved place: every location lives in a single
``Locations.fit``. So the reconcilable thing here is the WHOLE SET, not the
individual waypoint. Pushing one point at a time would replace the file and
silently drop every other place the user had.

That makes the rule simple and total:

    desired = every waypoint with load_to_device
    actual  = every waypoint the watch is believed to hold

    desired != actual  →  rewrite the file with all of `desired`
    desired is empty and actual is not  →  delete the file

There is no partial state in between, which is also why an edit to a waypoint
already on the watch clears its upload stamp (see routes/waypoints.py): that
moves it out of `actual`, the sets stop matching, and the file is rebuilt with
the new name or position in it.
"""
from __future__ import annotations

import base64
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

from app.api.training_plan import _get_sync_user
from app.calculators.fit_locations import generate_locations_fit, parse_locations_fit
from app.database import get_db
from app.models.sync_agents import SyncAgent
from app.models.waypoint import Waypoint
from app.services.sync_agent_auth import require_sync_agent

log = logging.getLogger(__name__)

waypoint_sync_router = APIRouter(prefix="/maps/sync", tags=["garmin-sync"])

LOCATIONS_FILENAME = "Locations.fit"
# Where a Garmin watch keeps saved places. Isolated as a constant because it is
# the one part of this pipeline that is a convention rather than a decoded fact
# — the file format came from a known-good sample, the folder name did not.
LOCATIONS_FOLDER = "GARMIN/Locations"


def _sync_user_or_400(db: Session, agent: SyncAgent, serial: str | None) -> int:
    uid = _get_sync_user(db, agent, serial or None)
    if uid is None:
        raise HTTPException(400, "Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return uid


def _desired(db: Session, uid: int) -> list[Waypoint]:
    """What the user has asked the watch to hold, in a stable order.

    Ordered by id so the file's message_index values stay put across rebuilds;
    a device that reorders its list on every sync is technically correct and
    unusable.
    """
    return (db.query(Waypoint)
            .filter(Waypoint.user_id == uid, Waypoint.load_to_device.is_(True))
            .order_by(Waypoint.id)
            .all())


def _actual(db: Session, uid: int) -> list[Waypoint]:
    """What the watch is believed to hold right now."""
    return (db.query(Waypoint)
            .filter(Waypoint.user_id == uid,
                    Waypoint.watch_uploaded_at.isnot(None),
                    Waypoint.watch_deleted_at.is_(None))
            .order_by(Waypoint.id)
            .all())


def _waypoint_upload_items(db: Session, uid: int) -> list[dict]:
    """At most one item — the rebuilt Locations.fit, when the sets disagree.

    Carries `ids` so the mark step records exactly what went into the file
    rather than re-reading the flags. Between the two calls the user may have
    flagged something new, and recomputing would mark that one delivered while
    it sat in no file at all — the failure mode this codebase already learned
    from with workouts, where "marked but never sent" means never retried.
    """
    desired = _desired(db, uid)
    if not desired:
        return []
    if {w.id for w in desired} == {w.id for w in _actual(db, uid)}:
        return []

    try:
        fit = generate_locations_fit([
            {"name": w.name, "lat": w.lat, "lng": w.lng,
             "ele_m": w.ele_m, "icon": w.icon}
            for w in desired
        ])
    except Exception:
        log.exception("waypoint upload: encode failed for user %s", uid)
        return []

    return [{
        "id": 0,                       # the file is the unit, not any one row
        "ids": [w.id for w in desired],
        "type": "waypoint",
        "filename": LOCATIONS_FILENAME,
        "fit_b64": base64.b64encode(fit).decode(),
    }]


def _waypoint_clear_item(db: Session, uid: int) -> list[dict]:
    """An *empty* Locations.fit, for a transport that can write but not delete.

    Bluetooth can put a file on a Garmin watch and has no operation for
    removing one — the protocol simply has no delete. Which is why unloading
    the last saved place used to be answered with "on the next cable sync": a
    perfectly honest sentence, and a useless one to somebody standing in a car
    park with no cable.

    A file containing no locations is the same outcome by a route the link
    does support. The watch reads it, finds nothing in it, and shows no saved
    places — which is what "delete them" means from the wrist.

    Offered only to [app.api.device_sync], never to garmin-sync: over USB the
    file itself can be removed, and leaving an empty one behind where a real
    delete is available would be litter.
    """
    if _desired(db, uid):
        return []
    if not _actual(db, uid):
        return []
    return [{
        "id": 0,
        # Nothing survives this file, and the mark step reads exactly that:
        # an empty list means every place that was on the watch is now off it.
        "ids": [],
        "type": "waypoint",
        "filename": LOCATIONS_FILENAME,
        "fit_b64": base64.b64encode(_EMPTY_LOCATIONS).decode(),
    }]


def _apply_mark_waypoint_uploaded(db: Session, items: list[dict],
                                  user_id: int | None = None) -> int:
    """Record a pushed Locations.fit.

    Everything named in `ids` is now on the watch; everything that WAS on it and
    is not named is gone, because the file that replaced it did not mention it.
    Recording only the first half would leave unflagged waypoints looking
    present forever, and the delete list would keep offering a file that is
    already correct.
    """
    now = datetime.now(timezone.utc)
    marked = 0
    for item in items:
        ids = [int(i) for i in (item.get("ids") or []) if i is not None]
        rows = db.query(Waypoint).filter(Waypoint.id.in_(ids)).all() if ids else []
        pushed: set[int] = set()
        for w in rows:
            if user_id is not None and w.user_id != user_id:
                continue
            w.watch_filename = item.get("filename") or LOCATIONS_FILENAME
            w.watch_uploaded_at = now
            w.watch_deleted_at = None
            pushed.add(w.id)
            marked += 1

        owners = {user_id} if user_id is not None else {w.user_id for w in rows}
        for owner in owners:
            if owner is None:
                continue
            for w in _actual(db, owner):
                if w.id not in pushed:
                    w.watch_uploaded_at = None
                    w.watch_deleted_at = now
    db.commit()
    return marked


def _waypoint_delete_items(db: Session, uid: int) -> list[dict]:
    """The file itself, once the user has unloaded every place in it.

    Only this case: any other change is an overwrite, and asking the watch to
    delete a file we are about to rewrite loses places on a sync that is
    interrupted in between.
    """
    if _desired(db, uid):
        return []
    if not _actual(db, uid):
        return []
    return [{"id": 0, "type": "waypoint", "filename": LOCATIONS_FILENAME}]


def _apply_mark_waypoint_deleted(db: Session, payload: dict,
                                 user_id: int | None = None) -> int:
    """Record that Locations.fit is off the watch."""
    now = datetime.now(timezone.utc)
    uids = [user_id] if user_id is not None else [
        row[0] for row in db.query(Waypoint.user_id).distinct().all()
    ]
    marked = 0
    for uid in uids:
        if uid is None:
            continue
        for w in _actual(db, uid):
            w.watch_uploaded_at = None
            w.watch_deleted_at = now
            w.watch_filename = None
            marked += 1
    db.commit()
    return marked


# How close two places have to be to be the same place. About a metre — wide
# enough for the semicircle round-trip through a FIT file, far tighter than any
# two things a person would mark separately.
_SAME_PLACE_DEGREES = 1e-5


def _same_place(waypoint: Waypoint, place: dict) -> bool:
    return (waypoint.name == place["name"]
            and abs(waypoint.lat - place["lat"]) < _SAME_PLACE_DEGREES
            and abs(waypoint.lng - place["lng"]) < _SAME_PLACE_DEGREES)


def ingest_locations(db: Session, uid: int, data: bytes) -> dict:
    """Reconcile against the Locations.fit actually sitting on a watch.

    The file is the truth about the device, so this reads in both directions:
    places on it that Tracks has never seen become waypoints, and places Tracks
    believed were on the watch but which the file does not mention are recorded
    as gone. Without the second half a place deleted on the watch itself stays
    listed as "on the watch" forever, and the delete list keeps offering to
    remove a file that is already correct.

    Imported places arrive flagged for the device, because they are already on
    it — clearing the flag would make the very next sync rewrite the file
    without them, quietly deleting the user's own waypoints for the crime of
    having been created on the wrong screen.
    """
    places = parse_locations_fit(data)
    now = datetime.now(timezone.utc)

    known = (db.query(Waypoint).filter(Waypoint.user_id == uid).all())
    unmatched = list(known)
    present: set[int] = set()
    imported = 0

    for place in places:
        match = next((w for w in unmatched if _same_place(w, place)), None)
        if match is None:
            match = Waypoint(
                user_id=uid,
                name=place["name"],
                lat=place["lat"],
                lng=place["lng"],
                ele_m=place["ele_m"],
                icon=place["icon"],
            )
            db.add(match)
            imported += 1
        else:
            unmatched.remove(match)

        match.load_to_device = True
        match.watch_filename = LOCATIONS_FILENAME
        match.watch_uploaded_at = now
        match.watch_deleted_at = None
        db.flush()
        present.add(match.id)

    for w in known:
        if w.id not in present and w.watch_uploaded_at is not None and w.watch_deleted_at is None:
            w.watch_uploaded_at = None
            w.watch_deleted_at = now

    db.commit()
    return {"found": len(places), "imported": imported}


@waypoint_sync_router.post("/waypoint-ingest")
def waypoint_ingest(payload: dict, db: Session = Depends(get_db),
                    x_garmin_device_serial: str = Header(default=""),
                    agent: SyncAgent = Depends(require_sync_agent)):
    """garmin-sync hands over the watch's own Locations.fit, bytes and all.

    One file rather than a filename inventory like courses use, because there
    is only ever one — a listing would be a round trip to learn a name we
    already know.
    """
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    encoded = payload.get("fit_b64")
    if not encoded:
        # No file on the watch means no saved places on it. Recorded rather
        # than ignored: it is how a wipe on the device reaches Tracks.
        return ingest_locations(db, uid, _EMPTY_LOCATIONS)
    try:
        return ingest_locations(db, uid, base64.b64decode(encoded))
    except ValueError as exc:
        raise HTTPException(400, f"Could not read the watch's locations: {exc}")


# A valid, empty locations file, used to mean "the watch has none".
_EMPTY_LOCATIONS = generate_locations_fit([])


@waypoint_sync_router.get("/waypoint-upload-list")
def waypoint_upload_list(db: Session = Depends(get_db),
                         x_garmin_device_serial: str = Header(default=""),
                         agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return _waypoint_upload_items(db, uid)


@waypoint_sync_router.post("/mark-waypoint-uploaded")
def mark_waypoint_uploaded(uploads: list[dict], db: Session = Depends(get_db),
                           x_garmin_device_serial: str = Header(default=""),
                           agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return {"marked": _apply_mark_waypoint_uploaded(db, uploads, user_id=uid)}


@waypoint_sync_router.get("/waypoint-delete-list")
def waypoint_delete_list(db: Session = Depends(get_db),
                         x_garmin_device_serial: str = Header(default=""),
                         agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return _waypoint_delete_items(db, uid)


@waypoint_sync_router.post("/mark-waypoint-deleted")
def mark_waypoint_deleted(payload: dict, db: Session = Depends(get_db),
                          x_garmin_device_serial: str = Header(default=""),
                          agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return {"marked": _apply_mark_waypoint_deleted(db, payload, user_id=uid)}
