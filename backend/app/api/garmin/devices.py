# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Device endpoints — list synced watches + pick the primary one.

Watches are detected automatically from synced FIT files. The user marks one
as "primary"; we store its Garmin product_id (not the device row PK) on
users.primary_device_product_id, because animation confirmations are keyed by
product_id and are therefore portable across different physical units of the
same watch model.

  /garmin/devices          list this user's synced devices
  /garmin/devices/primary  set the primary watch (PUT)
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Device, User, UserDevice


router = APIRouter()


def _resolve_product_id(device: Device) -> int | None:
    """Return the Garmin product_id for a Device row, looking it up from
    product_name when the stored product_id is NULL.

    Some FIT files store the product as the named enum value (e.g.
    'fenix6x') without the matching integer — the parser writes
    product_name and leaves product_id NULL. Confirmations are keyed by
    integer so we resolve from the FIT SDK Profile when missing."""
    if device.product_id is not None:
        return int(device.product_id)
    if not device.product_name:
        return None
    from garmin_fit_sdk import Profile
    gp = Profile['types'].get('garmin_product') or {}
    target = device.product_name.lower()
    for k, v in gp.items():
        if v == target and str(k).isdigit():
            return int(k)
    return None


@router.get("/devices")
def list_devices(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """List Garmin devices this user has synced FIT files from. The watch
    is detected automatically on first sync; this endpoint surfaces them so
    the user can mark one as primary."""
    rows = (
        db.query(Device)
        .join(UserDevice, UserDevice.device_id == Device.id)
        .filter(UserDevice.user_id == user.id)
        .order_by(Device.product_name)
        .all()
    )
    out = []
    for d in rows:
        prod = _resolve_product_id(d)
        out.append({
            "id":               d.id,
            "serial_number":    d.serial_number,
            "manufacturer":     d.manufacturer,
            "product_id":       prod,
            "product_name":     d.product_name,
            "software_version": d.software_version,
            # Both sides must be non-null to match — without this guard,
            # any device with NULL product_id would falsely appear primary
            # for any user with primary_device_product_id=NULL.
            "is_primary":       prod is not None
                                  and user.primary_device_product_id is not None
                                  and prod == user.primary_device_product_id,
        })
    return out


class PrimaryDeviceBody(BaseModel):
    # Allow either the FK device id (preferred) OR the raw product_id.
    device_id: int | None = None
    product_id: int | None = None


@router.put("/devices/primary")
def set_primary_device(
    body: PrimaryDeviceBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Set the user's primary device. Stores the Garmin product_id (not the
    device row PK) on users.primary_device_product_id — confirmations are
    keyed by product_id so the lookup is portable across different physical
    units of the same watch model."""
    product_id: int | None = None
    if body.device_id is not None:
        d = (
            db.query(Device)
            .join(UserDevice, UserDevice.device_id == Device.id)
            .filter(UserDevice.user_id == user.id, Device.id == body.device_id)
            .first()
        )
        if not d:
            raise HTTPException(status_code=404, detail="Device not found for user")
        product_id = _resolve_product_id(d)
        if product_id is None:
            raise HTTPException(
                status_code=422,
                detail=("Cannot determine product_id for this device. "
                        "Re-sync a FIT file with full device info, or pass "
                        "product_id explicitly."),
            )
    elif body.product_id is not None:
        product_id = int(body.product_id)
    else:
        raise HTTPException(status_code=422, detail="Provide device_id or product_id")

    user.primary_device_product_id = product_id
    db.add(user)
    db.commit()
    return {"primary_device_product_id": product_id}
