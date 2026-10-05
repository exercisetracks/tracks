# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
RecommenderContext: the situational snapshot the no-goal recommender matches on.

This is the input to the situation-matching engine (``situations.py`` /
``select.py``). It bundles three families of signal the user chose to drive the
no-goal dashboard card:

  1. Training-load / readiness  — TSB, CTL ramp, injury-risk, readiness score.
  2. Modality balance           — days since last strength / mobility / cardio,
                                  and recent per-modality session counts. This is
                                  the dimension that lets strength and mobility
                                  surface, not just cardio.
  3. Recency & rest             — consecutive-day streak, days since a rest day,
                                  same-sport streak, and whether the last session
                                  was long/hard or posterior-chain heavy.

Goal / periodisation is deliberately absent: when a goal is active the
goal-scheduled ``plan/`` system owns the dashboard card instead of this one.

Fundamentals are shared with that ``plan/`` system on purpose: sport
classification comes from ``plan.base._sport_family``, mobility muscle targets
from ``flexibility.sport_target_muscles``, and base session duration from the
existing coaching ``_base_duration_minutes``. Nothing here re-derives a metric
another calculator already owns.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Sequence

from app.calculators.coaching.selection import _base_duration_minutes
from app.calculators.flexibility import sport_target_muscles
from app.calculators.plan.base import _sport_family

# Sports that read as mobility/recovery work rather than cardio or strength.
# _sport_family maps these to "generic", so we classify modality ourselves.
_MOBILITY_FRAGMENTS = (
    "yoga", "pilates", "stretch", "mobility", "flexibility", "breath",
    "meditation", "tai_chi", "tai chi", "barre",
)

# Cardio family → short noun used in copy ("go easy on the {sport}").
_SPORT_NOUN = {
    "running": "run",
    "cycling": "ride",
    "mountain_biking": "ride",
    "swimming": "swim",
    "rowing": "row",
    "hiking": "hike",
    "walking": "walk",
    "paddling": "paddle",
}

# Families whose long/hard sessions load the posterior chain (informs the
# strength.posterior_caution situation, per the concurrent-training guidance
# against stacking heavy hinges on a fatigued posterior chain).
_POSTERIOR_FAMILIES = {"running", "hiking", "rowing"}

# A "long/hard" cardio session for last-session heuristics (minutes).
_LONG_SESSION_MIN = 75


def _activity_modality(sport: str | None) -> str:
    """Classify a raw sport label into cardio | strength | mobility."""
    s = (sport or "").lower().replace(" ", "_")
    if _sport_family(s) == "strength":
        return "strength"
    if any(frag.replace(" ", "_") in s for frag in _MOBILITY_FRAGMENTS):
        return "mobility"
    return "cardio"


def sport_noun(family: str | None) -> str:
    if not family:
        return "session"
    return _SPORT_NOUN.get(family, family.replace("_", " "))


def humanize_muscles(muscles: Sequence[str], limit: int = 3) -> str:
    """'hip_flexors', 'calves', 'hamstrings' -> 'hip flexors, calves, and hamstrings'."""
    words = [m.replace("_", " ") for m in muscles[:limit]]
    if not words:
        return "the areas you train most"
    if len(words) == 1:
        return words[0]
    if len(words) == 2:
        return f"{words[0]} and {words[1]}"
    return f"{', '.join(words[:-1])}, and {words[-1]}"


@dataclass
class RecommenderContext:
    today: date

    # ── load / readiness ──────────────────────────────────────────────
    ctl: float
    atl: float
    tsb: float
    ctl_ramp: float | None
    injury_risk: bool
    readiness: float
    readiness_confidence: str

    # ── cardio profile ────────────────────────────────────────────────
    dominant_family: str | None          # e.g. "running"
    alt_family: str | None               # a different cardio family to suggest
    cardio_families: list[str] = field(default_factory=list)

    # ── recency / rest ────────────────────────────────────────────────
    consecutive_training_days: int = 0   # current streak ending at latest session
    same_sport_streak: int = 0           # consecutive most-recent sessions, one family
    last_family: str | None = None
    last_modality: str | None = None
    last_session_long: bool = False
    last_session_posterior: bool = False

    # ── modality balance ──────────────────────────────────────────────
    days_since_cardio: int | None = None
    days_since_strength: int | None = None
    days_since_mobility: int | None = None
    strength_sessions_7d: int = 0
    strength_sessions_14d: int = 0
    cardio_sessions_7d: int = 0
    mobility_sessions_14d: int = 0
    total_sessions_14d: int = 0

    # ── derived helpers ───────────────────────────────────────────────
    base_duration: int = 50
    threshold_hr: float | None = None
    has_history: bool = False

    def mobility_muscles(self) -> str:
        """Human muscle string for the dominant sport (for mobility copy)."""
        target = self.dominant_family or self.last_family or "strength"
        return humanize_muscles(sport_target_muscles(target))


def _days_since(latest: date | None, today: date) -> int | None:
    if latest is None:
        return None
    return max((today - latest).days, 0)


def build_context(
    *,
    readiness_score: float,
    readiness_confidence: str,
    ctl: float,
    atl: float,
    tsb: float,
    ctl_ramp: float | None,
    injury_risk: bool,
    activity_history: Sequence,          # Activity rows, last ~90 days, any order
    strength_session_dates: Sequence[date] | None = None,  # in-app logged sessions
    threshold_hr: float | None,
    today: date,
) -> RecommenderContext:
    """Assemble a RecommenderContext from load signal + activity/session history."""
    # Normalise activities to (date, family, modality, minutes), newest first.
    rows = []
    for act in activity_history:
        if not act.started_at or not act.sport:
            continue
        d = act.started_at.date() if hasattr(act.started_at, "date") else act.started_at
        family = _sport_family(act.sport)
        modality = _activity_modality(act.sport)
        minutes = (act.duration_seconds or 0) / 60.0
        rows.append((d, family, modality, minutes))
    rows.sort(key=lambda r: r[0], reverse=True)

    # Fold in in-app strength sessions (logged via the guided player) so
    # "days since strength" reflects gym work that never became an Activity.
    for sd in (strength_session_dates or []):
        rows.append((sd, "strength", "strength", 45.0))
    rows.sort(key=lambda r: r[0], reverse=True)

    has_history = bool(rows)

    # Per-modality most-recent dates + rolling counts.
    last_by_modality: dict[str, date] = {}
    cardio_family_last: dict[str, date] = {}
    strength_7 = strength_14 = cardio_7 = mobility_14 = total_14 = 0
    d7 = today - timedelta(days=7)
    d14 = today - timedelta(days=14)
    for d, family, modality, _minutes in rows:
        if modality not in last_by_modality or d > last_by_modality[modality]:
            last_by_modality[modality] = d
        if modality == "cardio" and (family not in cardio_family_last or d > cardio_family_last[family]):
            cardio_family_last[family] = d
        if d >= d14:
            total_14 += 1
            if modality == "strength":
                strength_14 += 1
            if modality == "mobility":
                mobility_14 += 1
        if d >= d7:
            if modality == "strength":
                strength_7 += 1
            if modality == "cardio":
                cardio_7 += 1

    # Dominant cardio family = most sessions in the window (recency-agnostic here;
    # recency already shaped which rows exist via the 90-day fetch).
    cardio_counts: dict[str, int] = {}
    for _d, family, modality, _m in rows:
        if modality == "cardio":
            cardio_counts[family] = cardio_counts.get(family, 0) + 1
    cardio_families = sorted(cardio_counts, key=lambda f: cardio_counts[f], reverse=True)
    dominant_family = cardio_families[0] if cardio_families else None
    alt_family = next((f for f in cardio_families if f != dominant_family), None)
    if alt_family is None and dominant_family:
        alt_family = "cycling" if dominant_family != "cycling" else "running"

    # Consecutive-day training streak ending at the most recent session (only
    # counts as a live streak if that session was today or yesterday).
    training_days = sorted({r[0] for r in rows}, reverse=True)
    streak = 0
    if training_days and (today - training_days[0]).days <= 1:
        cursor = training_days[0]
        for d in training_days:
            if d == cursor:
                streak += 1
                cursor = cursor - timedelta(days=1)
            elif d < cursor:
                break

    # Same-sport streak across the most-recent sessions (by session, not day).
    same_sport_streak = 0
    if rows:
        top_family = rows[0][1]
        for _d, family, _mod, _m in rows:
            if family == top_family:
                same_sport_streak += 1
            else:
                break

    last_family = rows[0][1] if rows else None
    last_modality = rows[0][2] if rows else None
    last_minutes = rows[0][3] if rows else 0.0
    last_long = last_minutes >= _LONG_SESSION_MIN
    last_posterior = last_long and last_family in _POSTERIOR_FAMILIES

    return RecommenderContext(
        today=today,
        ctl=ctl,
        atl=atl,
        tsb=tsb,
        ctl_ramp=ctl_ramp,
        injury_risk=injury_risk,
        readiness=readiness_score,
        readiness_confidence=readiness_confidence,
        dominant_family=dominant_family,
        alt_family=alt_family,
        cardio_families=cardio_families,
        consecutive_training_days=streak,
        same_sport_streak=same_sport_streak,
        last_family=last_family,
        last_modality=last_modality,
        last_session_long=last_long,
        last_session_posterior=last_posterior,
        days_since_cardio=_days_since(last_by_modality.get("cardio"), today),
        days_since_strength=_days_since(last_by_modality.get("strength"), today),
        days_since_mobility=_days_since(last_by_modality.get("mobility"), today),
        strength_sessions_7d=strength_7,
        strength_sessions_14d=strength_14,
        cardio_sessions_7d=cardio_7,
        mobility_sessions_14d=mobility_14,
        total_sessions_14d=total_14,
        base_duration=_base_duration_minutes(ctl),
        threshold_hr=threshold_hr,
        has_history=has_history,
    )
