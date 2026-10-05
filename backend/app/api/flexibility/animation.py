# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Garmin animation-manifest checks shared across the flexibility API.

Flexibility workouts run in the watch's Yoga app, which only renders a subset
of Garmin's animation manifests. These helpers decide whether a given
(garmin_category, garmin_subtype) pair will actually animate, and are used by:
  - the custom-stretch CRUD (auto-set has_animation on save)
  - the post-activity flow generator (animated-only candidate filter)
"""

from app.calculators.garmin_animations import is_animatable, is_yoga_animatable


def yoga_animatable(category: str | None, subtype: int | None) -> bool:
    """Stretch-specific animation check. Flexibility workouts route to the
    watch's Yoga app, which only renders pose/move/plank category animations
    from Garmin's Yoga.json manifest. A (cat, subtype) pair that's animated
    in the strength app (Exercises.json) won't necessarily animate here, so
    has_animation alone is too permissive for the stretch flow generator."""
    from app.calculators.fit_workout import _CATEGORY_INT
    if not category or subtype is None:
        return False
    cat_int = _CATEGORY_INT.get(category.lower())
    return is_yoga_animatable(cat_int, subtype)


def resolve_animation_flag(cat: str | None, sub: int | None) -> bool:
    """True iff (cat, sub) is in Garmin's animation manifest. Auto-sets
    has_animation on custom stretches so the user sees feedback the moment
    they pick a garmin_category + garmin_subtype."""
    if not cat or sub is None:
        return False
    from app.calculators.fit_workout import _CATEGORY_INT
    cat_int = _CATEGORY_INT.get(cat.lower())
    return is_animatable(cat_int, sub)
