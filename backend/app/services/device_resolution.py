# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Device get-or-create — shared by the browser upload path and sync-agent
ingestion (formerly lived in app.watcher, which owned filesystem-based
import and no longer exists). Device rows are plaintext (see the branch
plan's encryption classification): device-serial -> user resolution for
household sync agents depends on being able to look these up without a
decryption key, and there's nothing personally sensitive about a device
make/model/serial on its own.
"""

import logging

from sqlalchemy.exc import IntegrityError

from app.models.activity import Device, UserDevice

log = logging.getLogger(__name__)


def get_or_create_device(db, device_info: dict, user_id: int | None) -> int | None:
    """Look up a device by serial+manufacturer, creating it if new. Uses a
    SAVEPOINT so concurrent callers don't crash on a UniqueViolation race.
    If user_id is given, also claims the device for that user — unless another
    account already holds it (see claim_if_unclaimed)."""
    serial = device_info.get("serial_number")
    if not serial:
        return None

    mfr_id = device_info.get("manufacturer_id")

    device = (
        db.query(Device)
        .filter_by(serial_number=serial, manufacturer_id=mfr_id)
        .first()
    )

    if not device and mfr_id is not None:
        # Fallback: match by serial alone (manufacturer_id may be NULL for
        # devices registered before multi-manufacturer support was added).
        # We do NOT backfill manufacturer_id on the existing record because
        # serial numbers are not guaranteed unique across manufacturers.
        device = (
            db.query(Device)
            .filter(Device.serial_number == serial, Device.manufacturer_id.is_(None))
            .first()
        )

    if not device:
        new_dev = Device(**{k: v for k, v in device_info.items() if k in {
            "serial_number", "manufacturer", "manufacturer_id", "product_name",
            "product_id", "software_version",
        }})
        db.add(new_dev)
        sp = db.begin_nested()  # SAVEPOINT — lets us roll back just this insert on conflict
        try:
            db.flush()
            sp.commit()
            log.info(
                "Registered new device: %s %s (s/n %s)",
                device_info.get("manufacturer"), device_info.get("product_name"), serial,
            )
            device = new_dev
        except IntegrityError:
            sp.rollback()
            db.expunge(new_dev)
            device = (
                db.query(Device)
                .filter_by(serial_number=serial, manufacturer_id=mfr_id)
                .first()
            )

    if user_id and device:
        claim_if_unclaimed(db, user_id, device.id)

    return device.id if device else None


def claim_if_unclaimed(db, user_id: int, device_id: int) -> bool:
    """Claim a watch for `user_id` unless another account already holds it.
    Returns whether `user_id` holds it afterwards.

    A watch belongs to one person. Importing a file from it, or a phone
    reporting it, used to add a claim for the importer regardless — and a
    second claim makes the watch unresolvable for a shared dock, while each
    claimant sees it as theirs. The first claim stands; a later one is
    refused here as POST /devices/{id}/claim refuses it.
    """
    holders = {uid for (uid,) in db.query(UserDevice.user_id).filter_by(device_id=device_id)}
    if user_id in holders:
        return True
    if holders:
        log.info("Device %s is held by another account; not claiming it for user %s",
                 device_id, user_id)
        return False
    db.add(UserDevice(user_id=user_id, device_id=device_id))
    return True
