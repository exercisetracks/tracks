# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the Garmin meta API package.

Just the one piece used by more than one sub-module: upserting an
animation_confirmations row. Both the single-pair POST /confirmations and
the bulk POST /recap/{id} need identical write semantics (insert-or-update,
keyed by product_id + category + subtype + user), so it lives here to avoid
the two copies drifting apart.
"""

from sqlalchemy.orm import Session

from app.models.strength import AnimationConfirmation


def upsert_confirmation_row(
    db: Session,
    *,
    product_id: int,
    garmin_category: str,
    garmin_subtype: int,
    animates: bool,
    user_id: int,
) -> None:
    """Insert or update one (product, category, subtype, user) confirmation.

    Does NOT commit — the caller batches commits. `garmin_category` is
    expected already lower-cased by the caller (it's the storage form)."""
    existing = (
        db.query(AnimationConfirmation)
        .filter_by(
            product_id=product_id,
            garmin_category=garmin_category,
            garmin_subtype=garmin_subtype,
            user_id=user_id,
        )
        .first()
    )
    if existing:
        existing.animates = bool(animates)
    else:
        db.add(AnimationConfirmation(
            product_id=product_id,
            garmin_category=garmin_category,
            garmin_subtype=garmin_subtype,
            animates=bool(animates),
            user_id=user_id,
        ))
