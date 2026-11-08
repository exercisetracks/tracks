# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""garmin-sync reconciliation API — the `course_sync_router` (prefix /maps/sync).

NOT behind user auth: every route is guarded by a sync agent's bearer
pairing token (require_sync_agent — see app.services.sync_agent_auth).
garmin-sync.py calls these to pull the FIT files to upload, the files to
delete, and to report / ingest courses already on the device. Reuses the
workout-sync helper (_get_sync_user) so device-user resolution stays
consistent with training_plan.py.

Watch reconciliation mirrors the PlannedWorkout pattern: the CustomTrack row is
the source of truth, the watch_* columns track real device state, and the
upload/delete lists are derived from the difference.
"""
from __future__ import annotations

import base64
import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

# courses_api CONSUMES this sync helper (training_plan is now a package that
# re-exports it); the import path is preserved as-is.
from app.api.training_plan import _get_sync_user
from app.calculators.fit_course import generate_course_fit
from app.database import get_db
from app.models.custom_track import CustomTrack
from app.models.sync_agents import SyncAgent
from app.services import course_fit
from app.services.sync_agent_auth import require_sync_agent

from .helpers import _course_filename

log = logging.getLogger(__name__)

course_sync_router = APIRouter(prefix="/maps/sync", tags=["garmin-sync"])


def _sync_user_or_400(db: Session, agent: SyncAgent, serial: str | None) -> int:
    uid = _get_sync_user(db, agent, serial or None)
    if uid is None:
        raise HTTPException(400, "Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return uid


# A triathlon's legs as courses, each sent alone where the race has no whole
# course; the phone's LocalRacePlans.RACE_LEGS.
_RACE_LEGS = (("swim", "swimming"), ("bike", "cycling"), ("run", "running"))


def _race_course_items(db: Session, uid: int) -> list[dict]:
    """The courses of the races on the watch's calendar this week — from
    today to ``_SYNC_DAYS_AHEAD`` days on, as the calendar reaches — for the
    watch's course list, where its map shows them while they are followed.

    A Garmin workout file has no field for a course, so the race day (the
    calendar's race session, training_plan/sync.py ``_race_day_fit``) and its
    course travel side by side. The race plan's course under the race's
    name; a triathlon with no whole course sends each leg file of its own.
    The phone's LocalRacePlans.raceCourses chooses the same, for Explore sync.

    Derived, not stored: nothing marks one sent (each item's id is negative,
    so the course marks — a track's id, checked against its owner — pass it
    by), and a cable or the browser rewrites the file, which they delete
    first, on each sync in race week. Not taken off after the race: nothing
    records that it reached the watch, and a delete of a file that is not
    there fails the sync's report. It stays among the watch's courses until
    deleted there, as a course loaded from Garmin Connect does."""
    from datetime import timedelta

    from app.api.training_plan.sync import _SYNC_DAYS_AHEAD
    from app.calculators.local_day import user_today
    from app.models.coaching import RacePlan, TrainingGoal

    today = user_today(db, uid)
    rows = (db.query(TrainingGoal, RacePlan)
            .join(RacePlan, RacePlan.goal_id == TrainingGoal.id)
            .filter(TrainingGoal.user_id == uid, RacePlan.user_id == uid,
                    TrainingGoal.goal_type == "event",
                    TrainingGoal.event_date >= today,
                    TrainingGoal.event_date <= today + timedelta(days=_SYNC_DAYS_AHEAD))
            .order_by(TrainingGoal.event_date, TrainingGoal.id)
            .all())
    items = []
    for goal, rp in rows:
        name = (goal.event_name or "").strip() or "Race"
        courses = []
        if rp.course_path and len(rp.course_path) >= 2:
            courses.append((0, name, (goal.event_sport or "running").lower(), rp.course_path))
        else:
            for leg_i, (leg, sport) in enumerate(_RACE_LEGS, start=1):
                path = ((rp.tri_leg_courses or {}).get(leg) or {}).get("path") or []
                if len(path) >= 2:
                    courses.append((leg_i, f"{name} · {leg.capitalize()}", sport, path))
        for leg_i, course_name, sport, path in courses:
            key = goal.id * 4 + leg_i
            coords = [[p[1], p[0], p[2] if len(p) > 2 else None] for p in path]
            eles = [p[2] for p in path if len(p) > 2 and p[2] is not None]
            ascent = sum(b - a for a, b in zip(eles, eles[1:]) if b > a)
            try:
                fit = generate_course_fit(course_name, coords, sport=sport, course_id=key, ascent_m=ascent)
            except Exception:
                log.exception("course upload: encode failed for race %s", goal.id)
                continue
            items.append({"id": -key, "type": "course", "filename": f"RACE{key}.fit",
                          "fit_b64": base64.b64encode(fit).decode()})
    return items


def _course_upload_items(db: Session, uid: int) -> list[dict]:
    """Managed tracks the user has flagged for the watch that aren't on it
    yet, and the week's race courses (``_race_course_items``)."""
    tracks = (db.query(CustomTrack).filter(
        CustomTrack.user_id == uid,
        CustomTrack.is_external.is_(False),
        CustomTrack.load_to_device.is_(True),
        CustomTrack.watch_uploaded_at.is_(None),
    ).all())
    items = []
    for t in tracks:
        try:
            fit = generate_course_fit(
                t.name, t.geometry, t.distance_m, sport=t.sport, course_id=t.id,
                ascent_m=t.ascent_m, descent_m=t.descent_m,
                course_points=(t.course_points if t.turn_by_turn else None))
        except Exception:
            log.exception("course upload: encode failed for track %s", t.id)
            continue
        items.append({"id": t.id, "type": "course", "filename": _course_filename(t),
                      "fit_b64": base64.b64encode(fit).decode()})
    return items + _race_course_items(db, uid)


@course_sync_router.get("/course-upload-list")
def course_upload_list(db: Session = Depends(get_db),
                       x_garmin_device_serial: str = Header(default=""),
                       agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return _course_upload_items(db, uid)


def _apply_mark_course_uploaded(db: Session, uploads: list[dict], user_id: int) -> int:
    """Record pushed course FIT files — only `user_id`'s. Required: the ids
    are the caller's word, and an agent once marked any account's courses."""
    now = datetime.now(timezone.utc)
    for item in uploads:
        t = db.get(CustomTrack, item.get("id"))
        if t is None or t.user_id != user_id:
            continue
        t.watch_filename = item.get("filename") or _course_filename(t)
        t.watch_uploaded_at = now
        t.watch_deleted_at = None
    db.commit()
    return len(uploads)


@course_sync_router.post("/mark-course-uploaded")
def mark_course_uploaded(uploads: list[dict], db: Session = Depends(get_db),
                         x_garmin_device_serial: str = Header(default=""),
                         agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return {"marked": _apply_mark_course_uploaded(db, uploads, uid)}


def _course_delete_items(db: Session, uid: int) -> list[dict]:
    """Tracks whose file must be removed from the watch: managed tracks the user
    unloaded, plus external courses the user asked to remove."""
    rows = (db.query(CustomTrack).filter(
        CustomTrack.user_id == uid,
        CustomTrack.watch_filename.isnot(None),
        CustomTrack.watch_deleted_at.is_(None),
    ).all())
    out = []
    for t in rows:
        managed_unloaded = (not t.is_external and t.watch_uploaded_at is not None
                            and not t.load_to_device)
        external_remove = t.is_external and t.purge_after_delete
        if managed_unloaded or external_remove:
            out.append({"id": t.id, "filename": t.watch_filename})
    return out


@course_sync_router.get("/course-delete-list")
def course_delete_list(db: Session = Depends(get_db),
                       x_garmin_device_serial: str = Header(default=""),
                       agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return _course_delete_items(db, uid)


def _apply_mark_course_deleted(db: Session, payload: dict, user_id: int) -> int:
    """Record removed course FIT files, purging external courses flagged for
    it — only `user_id`'s. This one deletes rows, so an unscoped call was a
    way to delete another account's courses by id."""
    now = datetime.now(timezone.utc)
    for tid in payload.get("ids", []):
        t = db.get(CustomTrack, tid)
        if t is None or t.user_id != user_id:
            continue
        if t.purge_after_delete:
            db.delete(t)
        else:
            t.watch_deleted_at = now
            t.watch_uploaded_at = None
            t.watch_filename = None
    db.commit()
    return len(payload.get("ids", []))


@course_sync_router.post("/mark-course-deleted")
def mark_course_deleted(payload: dict, db: Session = Depends(get_db),
                        x_garmin_device_serial: str = Header(default=""),
                        agent: SyncAgent = Depends(require_sync_agent)):
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    return {"marked": _apply_mark_course_deleted(db, payload, uid)}


@course_sync_router.post("/course-inventory")
def course_inventory(items: list[dict], db: Session = Depends(get_db),
                     x_garmin_device_serial: str = Header(default=""),
                     agent: SyncAgent = Depends(require_sync_agent)):
    """garmin-sync reports every file in GARMIN/Courses as [{filename,size,mtime}].
    We return the filenames we haven't ingested yet (or whose size changed) so the
    sync only downloads new/changed external courses."""
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    known = {t.watch_filename: t for t in db.query(CustomTrack).filter(
        CustomTrack.user_id == uid, CustomTrack.watch_filename.isnot(None)).all()}
    present = set()
    needed = []
    for it in items:
        fn = it.get("filename")
        if not fn:
            continue
        present.add(fn)
        existing = known.get(fn)
        if existing is None or (existing.is_external
                                and existing.device_size != it.get("size")):
            needed.append(fn)
    return {"needed": needed}


@course_sync_router.post("/course-ingest")
def course_ingest(payload: dict, db: Session = Depends(get_db),
                  x_garmin_device_serial: str = Header(default=""),
                  agent: SyncAgent = Depends(require_sync_agent)):
    """Ingest one external course FIT the watch had that we didn't create."""
    uid = _sync_user_or_400(db, agent, x_garmin_device_serial)
    fn = payload.get("filename")
    if not fn or not payload.get("fit_b64"):
        raise HTTPException(400, "filename and fit_b64 required")
    try:
        parsed = course_fit.parse_course_fit(base64.b64decode(payload["fit_b64"]))
    except Exception as exc:
        log.warning("course ingest: parse failed for %s: %s", fn, exc)
        raise HTTPException(400, f"Could not parse course: {exc}")

    t = (db.query(CustomTrack).filter(CustomTrack.user_id == uid,
                                      CustomTrack.watch_filename == fn,
                                      CustomTrack.is_external.is_(True)).first())
    if t is None:
        t = CustomTrack(user_id=uid, is_external=True, source="fit",
                        color="#64748b", watch_filename=fn)
        db.add(t)
    t.name = parsed["name"] or fn
    t.sport = parsed["sport"]
    t.geometry = parsed["coords"]
    t.distance_m = parsed["distance_m"]
    t.ascent_m = parsed["ascent_m"]
    t.descent_m = parsed["descent_m"]
    t.bounds = parsed["bounds"]
    t.profile = parsed["profile"]
    t.device_size = payload.get("size")
    t.watch_uploaded_at = datetime.now(timezone.utc)  # it IS on the device
    db.commit()
    return {"id": t.id}
