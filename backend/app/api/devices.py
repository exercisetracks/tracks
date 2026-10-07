# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging

from fastapi import APIRouter, Depends, HTTPException
import threading
import time
from sqlalchemy.orm import Session
from app.calculators.local_day import user_today
from app.database import SessionLocal

logger = logging.getLogger(__name__)

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, Device, User, UserDevice
from app.models.coaching import CoachingRecommendation
from app.schemas.device import DeviceOut
from app.api.activities import invalidate_heatmap_cache
from app.api.metrics import invalidate_dashboard_cache, warm_dashboard_cache, invalidate_training_load_cache, warm_training_load_cache

router = APIRouter(prefix="/devices", tags=["devices"])


def _invalidate_all_caches(db: Session, user_id: int) -> None:
    """Wipe all in-memory and DB caches that depend on which devices are claimed."""
    db.query(CoachingRecommendation).filter_by(user_id=user_id, date=user_today(db, user_id)).delete()
    db.commit()
    invalidate_heatmap_cache()
    invalidate_dashboard_cache()
    invalidate_training_load_cache()
    warm_dashboard_cache()
    warm_training_load_cache()

# ---------------------------------------------------------------------------
# Re-attribute activities when device claims change
# ---------------------------------------------------------------------------
# Used to also move FIT files between per-user folders on disk — there's no
# such folder structure anymore (ingestion always knows the target user
# upfront), so this is now a pure DB correction: an activity imported from a
# device before anyone claimed it, and so attributed to nobody, is filed under
# the person who claims that device.
#
# Only unattributed activities. This used to move every activity from the
# device that belonged to someone else, which together with an unrestricted
# claim let any household member take another's watch history — names, notes,
# times, heart rate — by claiming their watch. An activity that already has an
# owner keeps it; a claim is no way to change whose data something is.
def _reattribute_activities_for_user(user_id: int) -> None:
    db = SessionLocal()
    try:
        claimed_device_ids = [
            ud.device_id for ud in db.query(UserDevice).filter_by(user_id=user_id).all()
        ]
        if not claimed_device_ids:
            return
        corrected = (
            db.query(Activity)
            .filter(Activity.device_id.in_(claimed_device_ids), Activity.user_id.is_(None))
            .update({"user_id": user_id}, synchronize_session=False)
        )
        if corrected:
            db.commit()
            logger.info("Reattributed %d activities to user %s", corrected, user_id)
    finally:
        db.close()


def _trigger_reorg(user_id: int) -> None:
    """Start a delayed background thread to reattribute activities for a user."""
    def delayed():
        time.sleep(2)  # debounce period
        _reattribute_activities_for_user(user_id)

    threading.Thread(target=delayed, daemon=True).start()



def _with_claimed(devices: list[Device], user_id: int, db: Session) -> list[DeviceOut]:
    """Annotate a list of Device ORM objects with whether the user has claimed each one."""
    claimed_ids = {
        row.device_id
        for row in db.query(UserDevice).filter_by(user_id=user_id).all()
    }
    result = []
    for d in devices:
        out = DeviceOut.model_validate(d)
        out.claimed = d.id in claimed_ids
        result.append(out)
    return result


@router.get("/", response_model=list[DeviceOut])
def list_devices(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    devices = db.query(Device).order_by(Device.id).all()
    return _with_claimed(devices, user.id, db)


@router.get("/{device_id}", response_model=DeviceOut)
def get_device(
    device_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")
    return _with_claimed([device], user.id, db)[0]


@router.post("/{device_id}/claim", response_model=DeviceOut)
def claim_device(
    device_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Link this device to the current user.

    Refused while another account holds the device. A watch belongs to one
    person; the sync agents file its data by that one claim (two claims make
    the watch unsyncable rather than guessing), so a second claim would only
    ever be a mistake — or someone reaching for another person's data. The
    holder releases it first.
    """
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")

    held_elsewhere = (
        db.query(UserDevice)
        .filter(UserDevice.device_id == device_id, UserDevice.user_id != user.id)
        .first()
    )
    if held_elsewhere is not None:
        raise HTTPException(
            status_code=409,
            detail="This device belongs to another account. It has to be "
                   "released there before it can be claimed here.",
        )

    existing = db.query(UserDevice).filter_by(user_id=user.id, device_id=device_id).first()
    if existing is None:
        db.add(UserDevice(user_id=user.id, device_id=device_id))
        db.commit()
        _invalidate_all_caches(db, user.id)
        _trigger_reorg(user.id)

    return _with_claimed([device], user.id, db)[0]


@router.delete("/{device_id}/claim", response_model=DeviceOut)
def unclaim_device(
    device_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Remove the link between this device and the current user."""
    device = db.get(Device, device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="Device not found")

    row = db.query(UserDevice).filter_by(user_id=user.id, device_id=device_id).first()
    if row is not None:
        db.delete(row)
        db.commit()
        _invalidate_all_caches(db, user.id)
        _trigger_reorg(user.id)

    return _with_claimed([device], user.id, db)[0]


@router.post("/reorganize-files")
def reorganize_files(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Manually re-run activity reattribution based on current device claims."""
    _trigger_reorg(user.id)
    return {
        "message": "Reattribution started in background",
        "status": "processing"
    }
