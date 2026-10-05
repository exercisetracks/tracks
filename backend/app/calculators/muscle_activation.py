# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Muscle activation over the generated table (spec/muscle_groups.yaml ->
app/spec/muscle_groups.py).

Hand-written, unlike the table it walks. Ported from
frontend/src/utils/muscleGroups.js — the original, still-shipping
implementation that spec/fixtures/muscle_groups.json was baselined against —
and mirrored by frontend/src/spec/muscleActivation.js and
mobile/core's spec/MuscleGroups.kt. What keeps the three honest is that shared
fixture corpus: an implementation that diverges fails its own test suite.

There is no caller for compute_muscle_activation() in the backend yet. The web
app has always computed this client-side from an activity's parsed sets; this
module makes the same calculation available server-side for the first time,
in case a future endpoint wants a computed-not-just-displayed answer (e.g. a
weekly muscle-balance summary). It is complete and tested on its own.
"""
from __future__ import annotations

from app.spec.muscle_groups import CATEGORY_LABELS, CATEGORY_MUSCLES, MUSCLE_LABELS

__all__ = ["compute_muscle_activation", "category_label", "muscle_label"]

_PRIMARY_WEIGHT = 1.0
_SECONDARY_WEIGHT = 0.4


def compute_muscle_activation(sets: list[dict]) -> dict:
    """Given a session's sets, compute a 0..1 activation score per muscle key.

    Weighted by (sets x reps) when available so a 5x5 squat session loads
    quads/glutes more than a single isolated curl.

    Each `set` is expected to carry `set_type`, `exercise_category`, and
    `repetitions` (the shape the API already returns for a strength
    activity's sets). Non-"active" sets and unmapped/unknown categories ("",
    "65534", "unknown", or anything not in CATEGORY_MUSCLES) are skipped.

    `repetitions` is clamped to [1, 40] so a very-high-rep set can't dominate
    a session that also included heavy compounds. A missing OR ZERO
    `repetitions` both fall back to 10 — this mirrors the original
    JavaScript's `set.repetitions || 10`, where `0` is falsy and takes the
    same branch as missing/undefined. `x or default` in Python has the same
    falsy-zero behaviour, so this port keeps it deliberately rather than
    "fixing" it into a language-specific disagreement. See spec/README.md's
    rounding-differences table for the project's other examples of this.

    Returns {"activation": {muscle: 0..1}, "totals": {muscle: raw_score},
    "category_counts": {category: set_count}}.
    """
    totals: dict[str, float] = {}
    category_counts: dict[str, int] = {}

    active_sets = [s for s in (sets or []) if s.get("set_type") == "active"]

    for s in active_sets:
        cat = (s.get("exercise_category") or "").lower()
        if not cat or cat in ("65534", "unknown"):
            continue

        muscle_map = CATEGORY_MUSCLES.get(cat)
        if not muscle_map:
            continue

        category_counts[cat] = category_counts.get(cat, 0) + 1

        reps = max(1, min(40, s.get("repetitions") or 10))

        for m in muscle_map["primary"]:
            totals[m] = totals.get(m, 0.0) + reps * _PRIMARY_WEIGHT
        for m in muscle_map["secondary"]:
            totals[m] = totals.get(m, 0.0) + reps * _SECONDARY_WEIGHT

    peak = max([0.0, *totals.values()])
    activation: dict[str, float] = {}
    if peak > 0:
        activation = {k: v / peak for k, v in totals.items()}

    return {"activation": activation, "totals": totals, "category_counts": category_counts}


def category_label(key: str | None) -> str:
    """Human label for a FIT exercise_category, title-cased from the key when
    the category isn't in the table (e.g. Garmin firmware extensions like
    "hip_hinge"). None/empty returns "Unknown"."""
    if not key:
        return "Unknown"
    if key in CATEGORY_LABELS:
        return CATEGORY_LABELS[key]
    # Title-case each underscore-separated word, uppercasing only its first
    # character (mirrors the original JS's `\b\w` regex, which does not
    # lowercase the rest of a word — plain str.title() would).
    return " ".join(w[:1].upper() + w[1:] if w else w for w in key.split("_"))


def muscle_label(key: str) -> str:
    """Human label for a muscle key. Unlike category_label, the fallback is
    the raw key unchanged — not title-cased — matching the original JS."""
    return MUSCLE_LABELS.get(key, key)
