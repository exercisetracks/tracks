# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Stretch library read endpoint + shared library-assembly helpers.

Serves the merged stretch catalogue (built-in StretchLibrary rows + the user's
UserCustomStretch rows) decorated with the per-user preference flag and a
4-state animation status. The two helpers here (_merge_custom_stretches,
_attach_preferences) are also imported by the flows/generation code.
"""

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.garmin_animations import (
    build_confirmation_lookup, state_from_lookup,
)
from app.database import get_db
from app.models.activity import User
from app.models.flexibility import (
    StretchLibrary, UserCustomStretch, UserFlexibilityPreference,
)

router = APIRouter()


def _merge_custom_stretches(db: Session, user_id: int, library: dict,
                            confirm_lookup: dict | None = None) -> dict:
    """Merge user's custom stretches into the library dict."""
    customs = db.query(UserCustomStretch).filter_by(user_id=user_id).all()
    for ce in customs:
        library[ce.name] = {
            "id": ce.id,
            "name": ce.name,
            "primary_muscles": ce.primary_muscles or [],
            "secondary_muscles": ce.secondary_muscles or [],
            "equipment": ce.equipment or ["bodyweight"],
            "movement_pattern": ce.movement_pattern or "static_stretch",
            "is_compound": ce.is_compound,
            "difficulty": ce.difficulty,
            "duration_per_side_sec": ce.duration_per_side_sec,
            "each_side": ce.each_side,
            "sets": ce.sets,
            "description": ce.description,
            "instructions": ce.instructions,
            "cautions": ce.cautions,
            "cues": ce.cues or [],
            "breath_cue": ce.breath_cue,
            "position": ce.position,
            "viewer_slug": ce.viewer_slug,
            "sport_relevance": {"generic": 3},
            "garmin_category": ce.garmin_category,
            "garmin_subtype": ce.garmin_subtype,
            "has_animation": bool(ce.has_animation),
            "animation_state": state_from_lookup(
                ce.garmin_category, ce.garmin_subtype, confirm_lookup or {},
            ),
            "_is_custom": True,
            "custom_id": ce.id,
        }
    return library


def _attach_preferences(db: Session, user_id: int, items: list[dict]) -> None:
    """Attach user preference flags to a list of stretch dicts (mutates in place)."""
    prefs = db.query(UserFlexibilityPreference).filter_by(user_id=user_id).all()
    pref_map = {p.exercise_name: p.preference for p in prefs}
    for item in items:
        nm = item["name"]
        item["preference"] = pref_map.get(nm) or "neutral"   # a cleared row is null


@router.get("/stretches")
def get_stretches(
    muscle: str | None = Query(None, description="Filter by muscle group"),
    search: str | None = Query(None, description="Search by name"),
    difficulty: int | None = Query(None, ge=1, le=3),
    movement_pattern: str | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Return the merged stretch library (built-in + custom + user preferences).
    """
    rows = db.query(StretchLibrary).all()
    confirm_lookup = build_confirmation_lookup(
        db, user.primary_device_product_id, user.id,
    )
    library = {}
    for r in rows:
        library[r.name] = {
            "id": r.id,
            "name": r.name,
            "primary_muscles": r.primary_muscles or [],
            "secondary_muscles": r.secondary_muscles or [],
            "equipment": r.equipment or ["bodyweight"],
            "movement_pattern": r.movement_pattern or "static_stretch",
            "is_compound": r.is_compound,
            "difficulty": r.difficulty,
            "duration_per_side_sec": r.duration_per_side_sec,
            "each_side": r.each_side,
            "sets": r.sets,
            "description": r.description,
            "instructions": r.instructions,
            "cautions": r.cautions,
            "cues": r.cues or [],
            "breath_cue": r.breath_cue,
            "position": r.position,
            "sport_relevance": r.sport_relevance or {},
            "garmin_category": r.garmin_category,
            "garmin_subtype": r.garmin_subtype,
            "has_animation": bool(r.has_animation),
            "animation_state": state_from_lookup(
                r.garmin_category, r.garmin_subtype, confirm_lookup,
            ),
        }

    library = _merge_custom_stretches(db, user.id, library, confirm_lookup)
    items = list(library.values())
    _attach_preferences(db, user.id, items)

    if muscle:
        items = [i for i in items
                 if muscle in (i.get("primary_muscles") or [])
                 or muscle in (i.get("secondary_muscles") or [])]
    if search:
        q = search.lower()
        items = [i for i in items if q in i["name"].lower()]
    if difficulty is not None:
        items = [i for i in items if i.get("difficulty") == difficulty]
    if movement_pattern:
        items = [i for i in items if i.get("movement_pattern") == movement_pattern]

    items.sort(key=lambda x: (x.get("difficulty", 1), x["name"]))
    return items
