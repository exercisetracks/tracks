# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Animation confirmation endpoints — the user's per-pair yes/no verdict.

A "confirmation" records whether one (garmin_category, garmin_subtype) pair
actually animates on the user's primary device. It's keyed by the device's
product_id, so verdicts are shared across every user on this instance who owns
the same watch model. A primary device MUST be set first (PUT
/garmin/devices/primary) so we know which product_id the verdict applies to.

  /garmin/confirmations  POST   record/update one verdict
  /garmin/confirmations  DELETE clear one verdict
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.garmin_animations import (
    build_confirmation_lookup, state_from_lookup,
)
from app.database import get_db
from app.models.activity import User
from app.models.strength import AnimationConfirmation

from .helpers import upsert_confirmation_row


router = APIRouter()


class ConfirmationBody(BaseModel):
    garmin_category: str
    garmin_subtype:  int
    animates:        bool


@router.post("/confirmations")
def upsert_confirmation(
    body: ConfirmationBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Record (or update) the user's verdict on whether a specific
    (garmin_category, garmin_subtype) animates on their primary device.

    The user MUST have a primary device set (so we know which product_id
    the confirmation applies to). Returns the new aggregate state for that
    pair on this device."""
    product_id = user.primary_device_product_id
    if product_id is None:
        raise HTTPException(
            status_code=400,
            detail="Set a primary device first (PUT /garmin/devices/primary)",
        )

    cat = body.garmin_category.lower()
    upsert_confirmation_row(
        db,
        product_id=product_id,
        garmin_category=cat,
        garmin_subtype=body.garmin_subtype,
        animates=body.animates,
        user_id=user.id,
    )
    db.commit()

    # Re-resolve the aggregate state and return it so the UI can update.
    lookup = build_confirmation_lookup(db, product_id, user.id)
    state = state_from_lookup(cat, body.garmin_subtype, lookup)
    return {
        "product_id":      product_id,
        "garmin_category": cat,
        "garmin_subtype":  body.garmin_subtype,
        "animation_state": state,
    }


@router.delete("/confirmations", status_code=204)
def remove_confirmation(
    garmin_category: str = Query(...),
    garmin_subtype:  int = Query(...),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Remove the user's confirmation for a (cat, subtype) pair on their
    primary device. The aggregate verdict falls back to other users' votes
    (if any) and ultimately to manifest-membership."""
    product_id = user.primary_device_product_id
    if product_id is None:
        return
    db.query(AnimationConfirmation).filter_by(
        product_id=product_id,
        garmin_category=garmin_category.lower(),
        garmin_subtype=garmin_subtype,
        user_id=user.id,
    ).delete()
    db.commit()
