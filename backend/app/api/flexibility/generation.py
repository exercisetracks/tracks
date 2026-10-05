# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Post-activity stretch flow generation.

A pure (no-router) algorithm that builds a short, sport-tailored stretch flow
after a completed activity. Imported by api/training_plan.py and re-exported
from the package as `generate_post_activity_stretch_flow`.

The selection logic lives in `app.calculators.flexibility` (shared with the
weekly mobility session). This module's job is the DB/device-aware part: pull
the library + the user's custom stretches, apply the per-device animation gate
and the user's preferences/exclusions, and hand the eligible pool to the
selector with the right target muscles.
"""

from sqlalchemy.orm import Session

from app.calculators.flexibility import (
    StretchCandidate,
    select_stretch_flow,
    sport_target_muscles,
    strength_target_muscles,
    to_step,
)
from app.calculators.garmin_animations import (
    build_confirmation_lookup, state_from_lookup,
)
from app.models.flexibility import (
    StretchLibrary, UserCustomStretch, UserFlexibilityPreference,
)

from .animation import yoga_animatable


def generate_post_activity_stretch_flow(
    sport: str,
    primary_muscles: list[str] | None = None,
    db: Session | None = None,
    user_id: int | None = None,
    regen_salt: str = "",
    primary_device_product_id: int | None = None,
    variety_key: str = "",
    is_strength: bool = False,
    count: int = 5,
    candidates: list[StretchCandidate] | None = None,
    cooldown_theme: str | None = None,
    return_meta: bool = False,
) -> list[dict] | dict:
    """
    Generate a short stretch flow (5–10 min) tailored to a completed activity.

    Targets the muscle groups the activity loaded — resolved from the sport for
    endurance work, or from the session's actually-trained muscles for strength
    (`primary_muscles` + `is_strength`). Selection prefers recovery-appropriate
    static/PNF holds, spreads across the library, and rotates week to week
    (pass a per-day `variety_key`, e.g. the scheduled date, so consecutive days
    don't get identical flows).

    Animation filter: auto-generated flows draw only from stretches that animate
    on the user's watch (Yoga app pose/move). Bypassed for user-created custom
    stretches and stretches the user marked `preferred`; `excluded` stretches
    are always dropped. Pass `user_id` to enable the per-user rules.

    Returns a list of `mobility_exercise` step dicts (empty if `db` is None).
    """
    if candidates is None:
        if db is None:
            return []
        candidates = build_stretch_candidates(db, user_id, primary_device_product_id)
    if not candidates:
        return []

    if is_strength or "strength" in (sport or "").lower():
        target_muscles = strength_target_muscles(primary_muscles or [])
    else:
        target_muscles = sport_target_muscles(sport, fallback=primary_muscles)

    # Choose a named flow archetype for the cooldown — gives the flow an identity
    # and a calming closer, and sequences it into a wind-down position arc.
    from app.calculators.strength_plan.flow_archetypes import select_flow_archetype
    from app.calculators.training_plan import _sport_family
    sport_family = "strength" if is_strength else _sport_family(sport)
    try:
        variety_int = int(variety_key.replace("-", "")[-6:]) if variety_key else 0
    except (ValueError, AttributeError):
        variety_int = 0
    archetype = select_flow_archetype(
        "post_workout", sport_family, cooldown_theme, variety_int,
    )
    closer_muscles = list(archetype.closer_muscles) if archetype else None

    selected = select_stretch_flow(
        candidates, target_muscles,
        count=count,
        seed=f"{sport}-{regen_salt}-{variety_key}",
        # Post-activity cool-downs are often done wherever training ended (a
        # trailhead, a park), so keep them prop-free — no straps/rollers/blocks.
        equipment_free=True,
        order_by_position=True,
        closer_muscles=closer_muscles,
    )
    steps = [to_step(c) for c in selected]
    if return_meta:
        return {
            "steps": steps,
            "title": (archetype.name if archetype else None),
            "tagline": (archetype.tagline if archetype else None),
        }
    return steps


def build_stretch_candidates(
    db: Session,
    user_id: int | None,
    primary_device_product_id: int | None,
) -> list[StretchCandidate]:
    """Assemble the eligible StretchCandidate pool for a user straight from the
    DB — the built-in library plus the user's custom stretches, filtered by the
    per-device animation gate and the user's preferences/exclusions.

    Shared by the post-activity flow generator and the weekly-mobility injector
    so both draw from exactly the same pool of watch-renderable stretches.
    """
    library_rows = db.query(StretchLibrary).all()
    custom_rows: list[UserCustomStretch] = []
    preferred: set[str] = set()
    excluded: set[str] = set()
    if user_id is not None:
        custom_rows = db.query(UserCustomStretch).filter_by(user_id=user_id).all()
        for p in db.query(UserFlexibilityPreference).filter_by(user_id=user_id).all():
            if p.preference == "preferred":
                preferred.add(p.exercise_name)
            elif p.preference == "excluded":
                excluded.add(p.exercise_name)
    confirm_lookup = build_confirmation_lookup(db, primary_device_product_id, user_id)
    return _build_candidates(library_rows, custom_rows, confirm_lookup, preferred, excluded)


def _build_candidates(
    library_rows,
    custom_rows,
    confirm_lookup: dict,
    preferred: set[str],
    excluded: set[str],
) -> list[StretchCandidate]:
    """Build the eligible StretchCandidate pool: apply the animation gate and
    the user's exclusion list here (the DB/device-aware part), so the selector
    only ever sees stretches the user can actually see animate on their watch.

    Eligibility, per row:
      - excluded by the user            → drop
      - user's own custom stretch       → keep (bypasses the animation gate)
      - marked preferred by the user     → keep (bypasses the animation gate)
      - marked non-animating on device   → drop (user-observed reality wins)
      - otherwise                        → keep iff yoga-renderable
    """
    out: list[StretchCandidate] = []

    def add(r, is_custom: bool) -> None:
        if r.name in excluded:
            return
        is_pref = r.name in preferred
        if not is_custom and not is_pref:
            state = state_from_lookup(r.garmin_category, r.garmin_subtype, confirm_lookup)
            if state == "confirmed_no":
                return
            if not yoga_animatable(r.garmin_category, r.garmin_subtype):
                return
        out.append(StretchCandidate(
            name=r.name,
            primary_muscles=tuple(r.primary_muscles or []),
            secondary_muscles=tuple(r.secondary_muscles or []),
            movement_pattern=r.movement_pattern or "static_stretch",
            difficulty=r.difficulty or 1,
            duration_per_side_sec=r.duration_per_side_sec or 60,
            sets=r.sets or 1,
            each_side=bool(r.each_side),
            description=r.description or "",
            garmin_category=r.garmin_category,
            garmin_subtype=r.garmin_subtype,
            is_custom=is_custom,
            preferred=is_pref,
            equipment=tuple(r.equipment or ["bodyweight"]),
            cues=tuple(getattr(r, "cues", None) or []),
            breath_cue=getattr(r, "breath_cue", None),
            position=getattr(r, "position", None),
        ))

    for r in library_rows:
        add(r, False)
    for r in custom_rows:
        add(r, True)
    return out
