# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Animation manifest endpoints (read-only).

Exposes Garmin's published list of which exercises/stretches animate on the
watch. The data is bundled (backend/app/data/garmin_animations.json) and
loaded by app.calculators.garmin_animations, so these are fast and
offline-safe.

  /garmin/animations         the manifest as filterable records
  /garmin/animations/check   does a single (category, subtype) pair animate?
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from app.auth import require_auth
from app.calculators.fit_workout import _CATEGORY_INT
from app.calculators.garmin_animations import (
    APP_STRENGTH, APP_YOGA, all_animations, get_animation,
)
from app.models.activity import User


router = APIRouter()


# Frontend filter term → manifest app key. "any" disables app filtering.
_APP_ALIAS = {
    "yoga":     APP_YOGA,
    "strength": APP_STRENGTH,
    "any":      None,
}


@router.get("/animations")
def list_animations(
    category: str | None = Query(
        None,
        description="Filter to a single FIT category (e.g. 'pose', 'squat')",
    ),
    app: str = Query(
        "any",
        description="Filter by Garmin app context: 'yoga' | 'strength' | 'any'",
    ),
    user: User = Depends(require_auth),
):
    """Return the animation manifest as a list of
    `{cat, name, name_int, display, apps}` records, optionally filtered by
    category and/or app. Used by the custom-exercise/stretch picker UIs."""
    app_key = _APP_ALIAS.get(app, None)
    out = []
    for a in all_animations():
        if category and a.cat != category.lower():
            continue
        if app_key and app_key not in a.apps:
            continue
        out.append({
            "cat":      a.cat,
            "cat_int":  a.cat_int,
            "name":     a.name,
            "name_int": a.name_int,
            "display":  a.display,
            "apps":     list(a.apps),
        })
    return out


@router.get("/animations/check")
def check_animation(
    category: str = Query(..., description="snake_case category, e.g. 'pose'"),
    subtype: int = Query(..., description="exercise_name int"),
    user: User = Depends(require_auth),
):
    """Return whether a (category, subtype) pair maps to a known Garmin
    animation. Used by custom-form previews."""
    cat_int = _CATEGORY_INT.get(category.lower())
    if cat_int is None:
        return {"has_animation": False, "reason": "unknown_category"}
    info = get_animation(cat_int, subtype)
    if info is None:
        return {"has_animation": False, "reason": "not_in_manifest"}
    return {
        "has_animation": True,
        "name":          info.name,
        "display":       info.display,
        "apps":          list(info.apps),
    }
