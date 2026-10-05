# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Experience-level mapping: turn the one-time "how familiar are you with
strength training?" answer into generator inputs.

The answer lives in user_settings.strength_experience:
    brand_new | returning | regular | advanced   (None = never asked)

It shapes three things, all conservative-by-default:
  - the default strength tier suggested when a goal enables strength
  - a difficulty ceiling layered UNDER the tier cap (a brand-new lifter on an
    aggressive tier still never sees an Olympic lift)
  - a periodization-stage floor so a self-declared experienced lifter with no
    logged history doesn't start on the novice linear scheme

Real training history always wins over the floor: the generator takes
max(actual sessions, floor).
"""

from __future__ import annotations

from app.spec.strength import EXPERIENCE_LEVELS, EXPERIENCE_TABLE, UNKNOWN_FALLBACK_TIER


def experience_default_tier(experience: str | None, endurance_goal: bool) -> int:
    """Suggested strength_tier for a new goal. Endurance-primary goals keep
    strength supplementary regardless of lifting pedigree; strength-primary
    goals scale sessions/week with experience."""
    entry = EXPERIENCE_TABLE.get(experience) if experience else None
    if entry is None:
        return UNKNOWN_FALLBACK_TIER
    if endurance_goal:
        return entry["default_tier"]["endurance"]
    return entry["default_tier"]["strength"]


def experience_max_difficulty(experience: str | None) -> int | None:
    """Difficulty ceiling from experience, applied on top of the tier cap
    (min of the two). None = no extra cap."""
    entry = EXPERIENCE_TABLE.get(experience) if experience else None
    return entry["max_difficulty"] if entry else None


def experience_stage_floor(experience: str | None) -> int:
    """Floor on the cumulative-session count used to pick the periodization
    stage (generator: <20 linear, <100 weekly_undulating, >=100 dup). A
    regular lifter with an empty logbook starts undulating, an advanced one
    starts DUP; logged history beyond the floor takes over naturally."""
    entry = EXPERIENCE_TABLE.get(experience) if experience else None
    return entry["stage_floor"] if entry else 0


def starting_weight_factor(experience: str | None) -> float:
    """Extra conservatism on cold-start loads for someone who has never
    trained: the calibration sessions should feel almost too easy."""
    entry = EXPERIENCE_TABLE.get(experience) if experience else None
    return entry["starting_weight_factor"] if entry else 1.0


# Order of levels for one-step-at-a-time suggestions.
#
# Derived from the spec rather than restated. The order is load-bearing here in
# a way it is not elsewhere — this list is indexed, and idx +/- 1 IS the
# "one step at a time" rule — so a hand-maintained copy that fell out of sync
# with spec/strength.yaml would not raise, it would suggest the wrong level.
# That is the exact drift the spec exists to prevent, and leaving a duplicate
# here would have reintroduced it in the one file that just stopped restating
# these tables.
_LEVEL_ORDER = list(EXPERIENCE_LEVELS)


def infer_experience_suggestion(current: str | None, signals: dict) -> dict | None:
    """Suggest (never apply) an experience-level change from training history.

    Deliberately conservative — a wrong nudge is worse than a missed one, and
    the user always confirms:
      * Upgrade at most ONE level, and only with a genuine base of recent work
        (>= 12 logged strength sessions in the last 12 weeks) AND a non-falling
        e1RM trend on the main lifts.
      * Suggest a downgrade only after a long lay-off (>= 8 weeks with zero
        strength sessions) — and never below "returning".

    `signals` keys:
      sessions_12wk:  int   strength sessions in the last 12 weeks
      e1rm_trend:     str   "rising" | "flat" | "falling"
      weeks_since_last: int weeks since the last strength session

    Returns {"suggested": level, "reason": str} or None when no change is
    warranted. A suggestion equal to `current` returns None.
    """
    cur = current if current in _LEVEL_ORDER else "returning"
    idx = _LEVEL_ORDER.index(cur)

    sessions = signals.get("sessions_12wk", 0)
    trend = signals.get("e1rm_trend", "flat")
    idle = signals.get("weeks_since_last", 0)

    # Downgrade after a long lay-off (but never below "returning").
    if idle >= 8 and idx > 1:
        return {
            "suggested": _LEVEL_ORDER[idx - 1],
            "reason": f"No strength sessions in {idle} weeks — ease back in a level down.",
        }

    # Upgrade one level on a solid, progressing base.
    if idx < len(_LEVEL_ORDER) - 1 and sessions >= 12 and trend != "falling":
        return {
            "suggested": _LEVEL_ORDER[idx + 1],
            "reason": f"{sessions} strength sessions logged this block with steady progress — ready to level up.",
        }

    return None
