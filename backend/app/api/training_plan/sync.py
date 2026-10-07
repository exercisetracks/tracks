# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Garmin device sync — internal service endpoints.

Authenticated with a per-agent bearer pairing token (see
app.services.sync_agent_auth) instead of a single instance-wide secret; this
router is mounted WITHOUT the main JWT auth dependency so a sync agent
(garmin-sync, a future mobile app) can reach it without user credentials.

Endpoints under /training-plan/sync cover the watch upload/delete/schedule
cycle, AGPS (CPE.bin) config, and live sync status/trigger. FIT bytes for each
planned workout are built on demand (strength/mobility/flexibility use a
dedicated encoder; endurance workouts resolve pace/HR/power coaching ranges).
This data is all in the plaintext-classified tier (see the branch plan) by a
hard constraint, not just convenience: a scheduled workout has to be ready
on the watch overnight with no user session open, so building it can never
depend on a decryption key being available.
"""

import base64
import logging
from datetime import date, datetime, timedelta, timezone

import redis
from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.calculators.local_day import user_today
from app.auth import require_auth
from app.calculators.fit_workout import (
    generate_schedule_fit,
    generate_strength_workout_fit,
    generate_workout_fit,
)
from app.calculators.training_plan import _sport_family, vdot_to_paces
from app.config import settings
from app.database import get_db
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.sync_agents import SyncAgent
from app.models.training_plan import PlannedWorkout, TrainingPlan, WatchPendingDelete
from app.models.user_settings import UserSettings
from app.services.redis_client import get_redis
from app.services.sync_agent_auth import require_sync_agent, resolve_sync_user_id

from .helpers import _effective_ftp, _effective_threshold_hr

_log = logging.getLogger(__name__)

sync_router = APIRouter(prefix="/training-plan/sync", tags=["garmin-sync"])

_SYNC_DAYS_AHEAD = 4  # upload workouts scheduled this many days into the future

# Where a pushed file is written on a cable sync. app.api.device_sync declares
# the same string for the lists it assembles; it is repeated rather than
# imported because device_sync imports this module, and BLE has no filesystem
# to write to anyway — there the folder is only a hint, used when sniffing the
# FIT bytes fails to name a type.
NEWFILES = "GARMIN/NewFiles"


# ── Cross-worker sync signalling ─────────────────────────────────────────────
# Two short-lived instance-wide flags: "a watch sync is in progress" and "the
# user pressed Sync now". Both used to be module globals, which meant they only
# existed in whichever of UVICORN_WORKERS processes happened to serve the write
# — POST /trigger could land in worker A while the agent's GET /should-trigger
# hit worker B and saw nothing. Redis is the only state every worker shares.
#
# The TTL does the staleness work that an explicit timestamp comparison used to:
# a key that outlives its window simply isn't there any more. That also fixes
# the crash case, where a worker holding "syncing" in memory took its flag to
# the grave and left the spinner latched.
#
# Fail-OPEN on any Redis error, meaning "nothing happening" — a missing spinner
# or a missed trigger is a far better failure than a stuck one.
_SYNC_ACTIVE_KEY = "tracks:sync:active"
_SYNC_ACTIVE_TTL = 300  # a sync that hasn't reported in 5 min is presumed dead

_TRIGGER_KEY = "tracks:sync:trigger"
_TRIGGER_WINDOW_SECONDS = 300  # how long a trigger request stays "pending"


def _mark_sync_active() -> None:
    try:
        get_redis().set(_SYNC_ACTIVE_KEY, "1", ex=_SYNC_ACTIVE_TTL)
    except redis.RedisError:
        _log.warning("Could not record sync start — the UI spinner will not show")


def _clear_sync_active() -> None:
    try:
        get_redis().delete(_SYNC_ACTIVE_KEY)
    except redis.RedisError:
        _log.warning("Could not clear sync state — it will expire on its own in %ss",
                     _SYNC_ACTIVE_TTL)


def _is_sync_active() -> bool:
    try:
        return bool(get_redis().exists(_SYNC_ACTIVE_KEY))
    except redis.RedisError:
        return False


def _get_sync_user(db: Session, agent: SyncAgent, device_serial: str | None = None) -> int | None:
    """Resolve which user a sync-agent call should be scoped to — see
    app.services.sync_agent_auth.resolve_sync_user_id. Kept as a thin
    same-named wrapper because app/routes/courses_api/sync.py imports this
    directly (re-exported via app.api.training_plan.__init__)."""
    return resolve_sync_user_id(db, agent, device_serial)


# ── FIT file builders ────────────────────────────────────────────────────────

def _workout_filename(w: PlannedWorkout) -> str:
    return f"WKT_{w.scheduled_date.strftime('%Y%m%d')}_{w.id}.fit"


def _workout_display_name(w: PlannedWorkout) -> str:
    return w.title or "Workout"


def _ensure_fit_time_created(w: PlannedWorkout, db: Session) -> int:
    """Return the workout's file_id.time_created in Unix-epoch MILLISECONDS,
    assigning a fresh `now()` on first call and persisting so future schedule
    regenerations reuse the same value (required for the schedule_msg FK to
    keep resolving). Units are ms because the fit-tool encoder expects ms for
    date_time fields (scale=0.001, offset=-631065600000)."""
    if w.fit_time_created is not None:
        return w.fit_time_created
    ts_ms = round(datetime.now(timezone.utc).timestamp() * 1000)
    w.fit_time_created = ts_ms
    db.add(w)
    db.commit()
    return ts_ms


def _build_fit_b64(w: PlannedWorkout, db: Session) -> str:
    # Strength, mobility, and flexibility get a dedicated FIT encoder
    if w.workout_type in ("strength", "mobility", "flexibility"):
        file_ts = _ensure_fit_time_created(w, db)
        us = db.query(UserSettings).filter_by(user_id=w.user_id).first()
        fit_bytes = generate_strength_workout_fit(
            name=_workout_display_name(w),
            exercises=w.steps or [],
            workout_id=w.id,
            time_created=file_ts,
            description=w.description,
            units=(us.units if us and us.units else "metric"),
            workout_type=w.workout_type,
        )
        return base64.b64encode(fit_bytes).decode()

    sport = (w.sport or "running").lower()
    family = _sport_family(sport)

    us = db.query(UserSettings).filter_by(user_id=w.user_id).first()
    coaching = bool(us and us.pace_coaching)

    # Running coaching needs VDOT-derived pace zones; MTB + road-cycling
    # coaching needs LTHR and FTP so the FIT encoder can resolve HR / power
    # ranges from each step's intensity tag.
    paces = None
    lthr  = None
    ftp   = None
    if coaching:
        if family == "running" and w.plan_id:
            plan = db.query(TrainingPlan).filter_by(id=w.plan_id).first()
            if plan and plan.vdot:
                paces = vdot_to_paces(plan.vdot)
        if family in ("mountain_biking", "cycling"):
            lthr_f = _effective_threshold_hr(us)
            ftp_f  = _effective_ftp(us)
            lthr   = int(lthr_f) if lthr_f else None
            ftp    = int(ftp_f)  if ftp_f  else None

    file_ts = _ensure_fit_time_created(w, db)
    fit_bytes = generate_workout_fit(
        name=_workout_display_name(w),
        sport=sport,
        plan_steps=w.steps or [],
        workout_id=w.id,
        time_created=file_ts,
        pace_coaching=coaching,
        paces=paces,
        lthr=lthr,
        ftp=ftp,
    )
    return base64.b64encode(fit_bytes).decode()


# ── Upload / delete / schedule cycle ─────────────────────────────────────────

def _upload_items_for_user(db: Session, user_id: int) -> list[dict]:
    """Workouts scheduled in the next 7 days (plus pending race plans) that
    have not yet been uploaded to the watch, each carrying its FIT as base64.
    Shared by the garmin-sync (secret-auth) and browser (user-auth) routes."""
    today = user_today(db, user_id)
    cutoff = today + timedelta(days=_SYNC_DAYS_AHEAD)

    workouts = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user_id,
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.scheduled_date <= cutoff,
            PlannedWorkout.workout_type != "rest",
            PlannedWorkout.watch_uploaded_at.is_(None),
        )
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    )

    # One workout at a time rather than a list comprehension, so a FIT
    # encoding failure on one skips that workout instead of taking the whole
    # endpoint down with a 500 — which is what happened before this loop
    # existed: a single overlong description raised inside garmin_fit_sdk and
    # every other pending workout failed to push alongside it, with nothing
    # in the response to say why.
    items = []
    for w in workouts:
        try:
            fit_b64 = _build_fit_b64(w, db)
        except Exception:
            _log.exception("Could not build FIT for workout %s; skipping it this sync", w.id)
            continue
        items.append({
            "id":       w.id,
            "type":     "workout",
            "filename": _workout_filename(w),
            "title":    w.title,
            "date":     str(w.scheduled_date),
            "fit_b64":  fit_b64,
        })

    # Include race plan FIT files that haven't been uploaded yet
    race_plans = (
        db.query(RacePlan)
        .filter(
            RacePlan.fit_b64.isnot(None),
            RacePlan.watch_uploaded_at.is_(None),
        )
        .all()
    )
    for rp in race_plans:
        items.append({
            "id":       rp.id,
            "type":     "race_plan",
            "filename": rp.watch_filename or f"RACE_{rp.goal_id}.fit",
            "title":    "Race Plan",
            "date":     None,
            "fit_b64":  rp.fit_b64,
        })

    return items


@sync_router.get("/upload-list")
def get_upload_list(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return _upload_items_for_user(db, user_id)


def _apply_mark_uploaded(db: Session, uploads: list[dict], user_id: int) -> int:
    """Record pushed workout/race-plan FIT files — only rows belonging to
    `user_id`. Required, not optional: an id in the body is the caller's word,
    and an agent once marked any account's workouts by naming their ids."""
    now = datetime.now(timezone.utc)
    for item in uploads:
        if item.get("type") == "race_plan":
            rp = db.query(RacePlan).filter_by(id=item["id"], user_id=user_id).first()
            if rp is not None:
                rp.watch_filename    = item["filename"]
                rp.watch_uploaded_at = now
        else:
            w = db.query(PlannedWorkout).filter_by(id=item["id"], user_id=user_id).first()
            if w is not None:
                w.watch_filename    = item["filename"]
                w.watch_uploaded_at = now
    db.commit()
    return len(uploads)


@sync_router.post("/mark-uploaded")
def mark_uploaded(
    uploads: list[dict],
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Record that a batch of workout FIT files was successfully pushed to the watch."""
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return {"marked": _apply_mark_uploaded(db, uploads, user_id)}


def _delete_items_for_user(db: Session, user_id: int) -> list[dict]:
    """Workouts to delete from the watch: completed uploaded workouts plus
    any orphaned files queued by plan regeneration."""
    completed = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user_id,
            PlannedWorkout.is_complete == True,
            PlannedWorkout.watch_filename.isnot(None),
            PlannedWorkout.watch_deleted_at.is_(None),
        )
        .all()
    )
    result = [{"id": w.id, "filename": w.watch_filename, "type": "workout"}
              for w in completed]

    for p in db.query(WatchPendingDelete).filter_by(user_id=user_id).all():
        result.append({"id": p.id, "filename": p.filename, "type": "pending"})

    return result


@sync_router.get("/delete-list")
def get_delete_list(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return _delete_items_for_user(db, user_id)


def _apply_mark_deleted(db: Session, payload: dict, user_id: int) -> int:
    """Record removed workout FIT files — only `user_id`'s (see
    _apply_mark_uploaded for why that is required)."""
    now = datetime.now(timezone.utc)
    for wid in payload.get("ids", []):
        w = db.query(PlannedWorkout).filter_by(id=wid, user_id=user_id).first()
        if w is not None:
            w.watch_deleted_at = now
    for pid in payload.get("pending_ids", []):
        p = db.query(WatchPendingDelete).filter_by(id=pid, user_id=user_id).first()
        if p is not None:
            # If any active workout still references this filename, clear its
            # upload state so it gets re-uploaded on the next sync. Row by row
            # rather than a bulk UPDATE, so each change is stamped and syncs.
            for w in (db.query(PlannedWorkout)
                        .filter(PlannedWorkout.user_id == p.user_id,
                                PlannedWorkout.watch_filename == p.filename,
                                PlannedWorkout.watch_deleted_at.is_(None),
                                PlannedWorkout.is_complete.is_(False))
                        .all()):
                w.watch_uploaded_at = None
                w.watch_filename = None
            db.delete(p)
    db.commit()
    return len(payload.get("ids", [])) + len(payload.get("pending_ids", []))


@sync_router.post("/mark-deleted")
def mark_deleted(
    payload: dict,
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Record that workout FIT files were removed from the watch."""
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return {"marked": _apply_mark_deleted(db, payload, user_id)}


def _schedule_fit_for_user(db: Session, user_id: int) -> dict:
    """FIT type-7 schedule file placing all upcoming uploaded workouts in the
    watch training calendar."""
    today = user_today(db, user_id)
    workouts = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user_id,
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.watch_uploaded_at.isnot(None),
            PlannedWorkout.watch_deleted_at.is_(None),
            PlannedWorkout.workout_type != "rest",
        )
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    )
    # Workouts queued here all have watch_uploaded_at set, so _ensure_fit_time_created
    # has already been called on each and fit_time_created is populated. Use it
    # so the schedule_msg's FK exactly matches what was written in the workout's
    # file_id.time_created.
    items = [
        {
            "scheduled_date": w.scheduled_date,
            "workout_id":     w.id,
            "time_created":   w.fit_time_created,
        }
        for w in workouts if w.fit_time_created is not None
    ]

    # No device lookup: the watch is named nowhere in Connect's Schedule.fit.
    # This used to find the Garmin device behind the most recent activity and
    # write its serial and product into file_id, on the theory that the watch
    # matched them against itself. The decoded capture says otherwise — Connect
    # writes fixed constants — so the query was both wasted work and wrong.
    plan_name, plan_end = _plan_identity(db, user_id)
    fit_bytes = generate_schedule_fit(items, plan_name=plan_name, plan_end=plan_end)
    return {
        "fit_b64": base64.b64encode(fit_bytes).decode(),
        "count": len(items),
    }




def _plan_identity(db: Session, user_id: int) -> tuple[str, date | None]:
    """A name and an end date for the training plan message the watch wants.

    Connect names the plan the user chose and dates it to the plan's own end,
    which is well past the last workout the schedule carries. Tracks builds its
    plans from a goal, so the goal's name and event date are the honest
    equivalents; when there is no goal, the caller falls back to the span of the
    entries themselves.
    """
    goal = (
        db.query(TrainingGoal)
        .filter(TrainingGoal.user_id == user_id, TrainingGoal.is_active.is_(True))
        .order_by(TrainingGoal.id.desc())
        .first()
    )
    if goal is None:
        return "Training Plan", None
    name = goal.event_name or (goal.goal_type or "Training").title()
    return name, goal.event_date


def _schedule_bundle_for_user(db: Session, user_id: int) -> dict:
    """The schedule *and* every workout it names, as one set.

    Garmin Connect does something that looks wasteful until you watch the watch:
    in a sync where a single workout had changed, it uploaded the schedule and
    then re-uploaded all eighteen workouts the schedule referenced, seventeen of
    which the watch already had. Eighteen entries, eighteen uploads, schedule
    first. It does that every sync.

    Tracks used to push only the workouts the server believed were owed and then
    a schedule naming workouts delivered in earlier sessions. The workouts
    arrived and were listed on the watch; the calendar stayed empty. The reading
    that fits both observations is that the watch builds a calendar out of what
    a schedule names *and what came with it*, so a reference to a file from a
    previous session resolves to nothing.

    So this returns them together, over one window, and the caller pushes them
    in Connect's order. Unlike _schedule_fit_for_user this does not require the
    workouts to have been uploaded already: the point is to send them.
    """
    today = user_today(db, user_id)
    cutoff = today + timedelta(days=_SYNC_DAYS_AHEAD)

    workouts = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user_id,
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.scheduled_date <= cutoff,
            PlannedWorkout.watch_deleted_at.is_(None),
            PlannedWorkout.workout_type != "rest",
        )
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    )

    # Same per-workout guard as _upload_items_for_user, and it matters more
    # here: a workout whose FIT will not encode must be left out of the
    # schedule too, or the schedule names a file that is never sent and the
    # bundle stops being self-consistent.
    files, items = [], []
    for w in workouts:
        try:
            fit_b64 = _build_fit_b64(w, db)
        except Exception:
            _log.exception("Could not build FIT for workout %s; leaving it out of the schedule", w.id)
            continue
        files.append({
            "id":       w.id,
            "type":     "workout",
            "filename": _workout_filename(w),
            # Named here rather than left to the endpoint the way
            # _upload_items_for_user's items are. Those are a list the caller
            # assembles from several sources and tags on the way past; this is a
            # finished response that already names the schedule's own folder, so
            # a workout inside it that did not name one was the odd entry out.
            #
            # Its absence cost a fortnight: the phone's PushItem requires
            # `folder`, so decoding the bundle threw on $.workouts[0], the whole
            # push was abandoned before the schedule went, and the only trace was
            # one "could not fetch the training schedule" line that read like a
            # network failure. Every sync between 2026-08-25 21:19 and this fix
            # sent nothing at all.
            "folder":   NEWFILES,
            "title":    w.title,
            "date":     str(w.scheduled_date),
            "fit_b64":  fit_b64,
        })
        items.append({
            "scheduled_date": w.scheduled_date,
            "workout_id":     w.id,
            "time_created":   w.fit_time_created,
        })

    plan_name, plan_end = _plan_identity(db, user_id)
    fit_bytes = generate_schedule_fit(items, plan_name=plan_name, plan_end=plan_end)
    return {
        "fit_b64":  base64.b64encode(fit_bytes).decode(),
        "count":    len(items),
        "filename": "SCHEDULE.fit",
        "folder":   NEWFILES,
        "workouts": files,
    }


@sync_router.get("/schedule-fit")
def get_schedule_fit(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return _schedule_fit_for_user(db, user_id)


@sync_router.get("/schedule-bundle")
def get_schedule_bundle(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """The cable counterpart of /device-sync/schedule-bundle.

    A schedule on its own only fills the calendar for workouts whose files
    arrive with it. Measured on a fenix 6X on 2026-08-27: a schedule naming
    sixteen workouts went into GARMIN/NewFiles beside two workout files, and
    exactly two calendar entries appeared — the two whose files were in that
    batch. The other fourteen were already sitting in GARMIN/Workouts from
    earlier syncs and resolved to nothing.

    So the cable path needs what BLE already does: send every workout the
    schedule names, every time, however many of them the watch already holds.
    """
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    if user_id is None:
        raise HTTPException(status_code=400, detail="Device not claimed by any user yet — provide X-Garmin-Device-Serial header")
    return _schedule_bundle_for_user(db, user_id)


# ── AGPS (CPE.bin) config ────────────────────────────────────────────────────

@sync_router.get("/agps-config")
def get_agps_config(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Return AGPS settings for the syncing user so the garmin-sync service
    can decide whether to push a fresh CPE.bin to the watch."""
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    # No silent "just grab someone's settings" fallback — an unresolved user
    # means AGPS is unavailable for this call, not "guess and maybe leak
    # another user's settings to the wrong device."
    us = db.query(UserSettings).filter_by(user_id=user_id).first() if user_id else None
    if us is None:
        return {"enabled": False}
    url = None
    if us.agps_source == "garmin":
        url = (
            "https://api.gcs.garmin.com/ephemeris/cpe/sony/lle"
            "?coverage=WEEKS_1&constellations=GPS,GLONASS,GALILEO,QZSS"
        )
    elif us.agps_source == "custom" and us.agps_custom_url:
        url = us.agps_custom_url
    return {
        "enabled": bool(us.agps_enabled),
        "url": url,
        "epo_path": us.agps_epo_path or "GARMIN/REMOTESW/CPE.bin",
        "max_age_hours": us.agps_max_age_hours or 24,
        "last_synced_at": us.agps_last_synced_at.isoformat() if us.agps_last_synced_at else None,
    }


@sync_router.post("/agps-synced")
def record_agps_synced(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Called by garmin-sync after it successfully writes CPE.bin to the watch."""
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    us = db.query(UserSettings).filter_by(user_id=user_id).first() if user_id else None
    if us:
        us.agps_last_synced_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True}


# ── Live sync status / trigger ───────────────────────────────────────────────

@sync_router.get("/import-status")
def get_import_status(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return whether any of *this user's* FIT imports are still queued.
    Polled by the frontend to show the import progress spinner. Reflects
    PendingImport rows awaiting a decryption key (see app.services.fit_import)
    rather than a live in-process "currently parsing" counter — there isn't
    one of those anymore now that processing runs per-user, on login.

    This router is mounted without a blanket auth dependency (most of its
    routes are guarded by require_sync_agent instead), so the dependency has
    to be declared per-route here. It previously had neither, which made an
    instance-wide "is anyone importing right now" signal readable by anyone
    who could reach the port. Scoping to the caller fixes the leak and makes
    the answer correct on a multi-user instance, where another user's queue
    would otherwise spin this user's UI."""
    from app.models.imports import PendingImport
    importing = (
        db.query(PendingImport)
        .filter_by(user_id=user.id, processed_at=None)
        .first()
        is not None
    )
    return {"importing": importing}


@sync_router.post("/start", dependencies=[Depends(require_sync_agent)])
def record_sync_start():
    """Called by garmin-sync the moment a watch is detected. Shows the spinner in the UI."""
    _mark_sync_active()
    return {"ok": True}


@sync_router.post("/abort", dependencies=[Depends(require_sync_agent)])
def record_sync_abort():
    """Called by garmin-sync when a sync attempt dies (watch unplugged
    mid-sync, worker crash) so is_syncing doesn't stay latched for the
    5-minute staleness window — that left the sidebar stuck on
    "Syncing watch…" after an unplug."""
    _clear_sync_active()
    return {"ok": True}


@sync_router.post("/watch-synced")
def record_watch_synced(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Called by garmin-sync after the workout upload/delete/schedule cycle completes."""
    _clear_sync_active()
    user_id = _get_sync_user(db, agent, x_garmin_device_serial or None)
    us = db.query(UserSettings).filter_by(user_id=user_id).first() if user_id else None
    if us:
        us.watch_last_synced_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True}


@sync_router.get("/status")
def get_sync_status(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Return the last watch sync timestamp, pending upload count, live sync state,
    and current import activity."""
    from app.models.imports import PendingImport
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    pending = (
        db.query(func.count(PlannedWorkout.id))
        .filter(
            PlannedWorkout.user_id == user.id,
            PlannedWorkout.watch_uploaded_at.is_(None),
            PlannedWorkout.workout_type != "rest",
            PlannedWorkout.scheduled_date >= user_today(db, user.id),
        )
        .scalar()
    )
    is_syncing = _is_sync_active()
    importing = db.query(PendingImport).filter_by(user_id=user.id, processed_at=None).first() is not None
    return {
        "last_synced_at": us.watch_last_synced_at.isoformat() if us and us.watch_last_synced_at else None,
        "pending_count": pending,
        "is_syncing": is_syncing,
        "importing": importing,
    }


# "Sync now" request from the web UI, consumed by a sync agent's next poll.
# Used to be a trigger *file* on the shared fit-files volume — garmin-sync no
# longer mounts that volume at all (it never touches shared disk now, see the
# branch plan), so this is API-driven instead.


@sync_router.post("/trigger")
def trigger_garmin_sync(user: User = Depends(require_auth)):
    try:
        get_redis().set(_TRIGGER_KEY, "1", ex=_TRIGGER_WINDOW_SECONDS)
    except redis.RedisError:
        _log.warning("Could not record sync trigger — the agent will sync on its next poll")
        return {"triggered": False}
    return {"triggered": True}


@sync_router.get("/should-trigger", dependencies=[Depends(require_sync_agent)])
def should_trigger_sync():
    """Polled by a sync agent to check for a pending "sync now" request.
    Consumes the flag on a positive read — one trigger, one immediate sync.

    DELETE returns the number of keys it removed, which makes read-and-consume
    a single atomic operation. That matters now that the flag is shared: two
    agents (or two workers serving one agent's retry) polling at the same
    instant must not both come away believing they own the trigger.
    """
    try:
        return {"trigger": bool(get_redis().delete(_TRIGGER_KEY))}
    except redis.RedisError:
        return {"trigger": False}
