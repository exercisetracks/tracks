# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Situation catalogue: the ~20 training situations the no-goal recommender can
match, across cardio / strength / mobility / rest.

Each Situation is a *reason to train a certain way today*. It knows:
  - when it applies (``fires``), from the RecommenderContext signals,
  - how strongly it fits (``score`` — higher wins the hero slot; safety-first
    situations like rest and active-recovery score highest so they can't be
    out-ranked by a hard session on a bad day),
  - the concrete session shape (modality, intensity, title, focus, duration).

The prose lives separately in ``data/coaching_paragraphs/*.json`` (keyed by the
situation key); ``library.py`` renders it and ``select.py`` picks a hero plus
alternates of *different* modalities. Keeping firing logic here and copy there
means wording can be edited without touching the engine.

Fundamentals stay shared with the plan/ system: intensities map onto the same
zone vocabulary, duration scales off the coaching ``_base_duration_minutes``,
and strength focuses read like ``strength_plan`` splits.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from app.calculators.coaching.context import RecommenderContext


# ── Duration factories ────────────────────────────────────────────────────────
def _factor(f: float, lo: int = 20) -> Callable[[RecommenderContext], int]:
    """Cardio duration as a factor of the CTL-calibrated base session."""
    return lambda ctx: max(lo, int(round(ctx.base_duration * f)))


def _fixed(n: int) -> Callable[[RecommenderContext], int]:
    return lambda ctx: n


# ── Shared predicates ─────────────────────────────────────────────────────────
def _has_cardio(ctx: RecommenderContext) -> bool:
    return ctx.dominant_family is not None


def _strength_due(ctx: RecommenderContext) -> bool:
    """At least ~48h since the last strength session (or none on record)."""
    return ctx.days_since_strength is None or ctx.days_since_strength >= 2


@dataclass(frozen=True)
class Situation:
    key: str
    modality: str                 # cardio | strength | mobility | rest
    intensity: str                # zone/label used for copy + card badge
    title: str                    # short heading, e.g. "Aerobic base"
    duration: Callable[[RecommenderContext], int]
    fires: Callable[[RecommenderContext], bool]
    score: Callable[[RecommenderContext], float]
    focus: str | None = None      # strength split / mobility target label


# ── The catalogue ─────────────────────────────────────────────────────────────
# Scores are banded so safety (rest, active recovery) outranks productive
# training, which outranks maintenance/default work. Small context bonuses break
# ties within a band without crossing it.
SITUATIONS: list[Situation] = [
    # ---- Rest -----------------------------------------------------------------
    Situation(
        "rest.injury_risk_ramp", "rest", "rest", "Rest day",
        _fixed(0),
        fires=lambda c: c.injury_risk,
        score=lambda c: 100.0,
    ),
    Situation(
        "rest.overdue", "rest", "rest", "Rest day",
        _fixed(0),
        fires=lambda c: c.consecutive_training_days >= 6 or (c.tsb < -30 and c.readiness < 50),
        score=lambda c: 94.0 + min(c.consecutive_training_days, 12) * 0.3,
    ),

    # ---- Mobility -------------------------------------------------------------
    Situation(
        "mobility.active_recovery", "mobility", "mobility", "Recovery mobility",
        _fixed(15),
        fires=lambda c: c.tsb < -20 or c.readiness < 50,
        score=lambda c: 88.0 + max(0.0, (50 - c.readiness) * 0.2),
        focus="recovery",
    ),
    Situation(
        "mobility.post_long_flush", "mobility", "mobility", "Targeted mobility",
        _fixed(15),
        fires=lambda c: c.last_session_long and (c.days_since_cardio is not None and c.days_since_cardio <= 1),
        score=lambda c: 58.0,
        focus="sport",
    ),
    Situation(
        "mobility.neglected", "mobility", "mobility", "Mobility",
        _fixed(12),
        fires=lambda c: (c.days_since_mobility is not None and c.days_since_mobility >= 14)
        or (c.days_since_mobility is None and c.total_sessions_14d >= 4),
        # A tracked, genuinely-lapsed streak can earn the hero slot; the softer
        # "you never log mobility" nudge stays an alternate (below an easy day).
        score=lambda c: 55.0 if (c.days_since_mobility is not None and c.days_since_mobility >= 14) else 42.0,
        focus="sport",
    ),
    Situation(
        "mobility.sport_specific_tightness", "mobility", "mobility", "Mobility",
        _fixed(15),
        fires=lambda c: c.has_history,
        score=lambda c: 38.0,
        focus="sport",
    ),
    Situation(
        "mobility.rest_day_gentle", "mobility", "mobility", "Gentle mobility",
        _fixed(10),
        fires=lambda c: c.consecutive_training_days >= 6 or c.injury_risk or (c.tsb < -30),
        score=lambda c: 20.0,
        focus="sport",
    ),

    # ---- Strength -------------------------------------------------------------
    Situation(
        "strength.posterior_caution", "strength", "strength", "Upper-body strength",
        _fixed(45),
        fires=lambda c: c.last_session_posterior and _strength_due(c) and c.readiness >= 55,
        score=lambda c: 67.0,
        focus="upper body",
    ),
    Situation(
        "strength.overdue", "strength", "strength", "Strength",
        _fixed(45),
        fires=lambda c: c.days_since_strength is not None and c.days_since_strength >= 7 and c.readiness >= 55,
        score=lambda c: 66.0 + min(c.days_since_strength or 7, 21) * 0.2,
        focus="full body",
    ),
    Situation(
        "strength.heavy_fresh", "strength", "heavy", "Heavy strength",
        _fixed(50),
        fires=lambda c: c.tsb >= 5 and c.readiness >= 75 and _strength_due(c),
        score=lambda c: 64.0,
        focus="lower body",
    ),
    Situation(
        "strength.return_after_layoff", "strength", "moderate", "Strength restart",
        _fixed(35),
        fires=lambda c: c.days_since_strength is not None and c.days_since_strength >= 21,
        score=lambda c: 62.0,
        focus="full body",
    ),
    Situation(
        "strength.maintenance", "strength", "strength", "Strength",
        _fixed(45),
        fires=lambda c: c.strength_sessions_14d >= 2 and _strength_due(c) and c.readiness >= 55,
        score=lambda c: 57.0,
        focus="full body",
    ),
    Situation(
        "strength.sport_support", "strength", "strength", "Support strength",
        _fixed(40),
        fires=lambda c: _has_cardio(c) and c.strength_sessions_14d == 0
        and c.days_since_strength is None and c.readiness >= 55,
        score=lambda c: 54.0,
        focus="lower body",
    ),

    # ---- Cardio ---------------------------------------------------------------
    Situation(
        "cardio.recovery_flush", "cardio", "recovery", "Recovery",
        _factor(0.5),
        fires=lambda c: _has_cardio(c) and c.last_session_long and c.tsb < -5 and c.readiness >= 50,
        score=lambda c: 70.0,
    ),
    Situation(
        "cardio.quality_intervals", "cardio", "quality", "Intervals",
        _factor(0.7),
        fires=lambda c: _has_cardio(c) and c.tsb >= 5 and c.readiness >= 78 and c.total_sessions_14d >= 3,
        score=lambda c: 63.0,
    ),
    Situation(
        "cardio.tempo_productive", "cardio", "tempo", "Tempo",
        _factor(0.8),
        fires=lambda c: _has_cardio(c) and -30 <= c.tsb <= -8 and c.readiness >= 70,
        score=lambda c: 61.0,
    ),
    Situation(
        "cardio.big_aerobic_base", "cardio", "aerobic", "Aerobic base",
        _factor(1.3),
        fires=lambda c: _has_cardio(c) and c.tsb > 5 and c.readiness >= 65,
        score=lambda c: 60.0 + min(max(c.tsb, 0.0), 20.0) * 0.1,
    ),
    Situation(
        "cardio.rebuild_base", "cardio", "aerobic", "Rebuild base",
        _factor(1.0),
        fires=lambda c: _has_cardio(c) and c.tsb > 25,
        score=lambda c: 56.0,
    ),
    Situation(
        "cardio.cross_train_swap", "cardio", "easy", "Cross-train",
        _factor(0.9),
        fires=lambda c: _has_cardio(c) and c.same_sport_streak >= 4 and c.readiness >= 55,
        score=lambda c: 52.0,
    ),
    Situation(
        "cardio.consistency_nudge", "cardio", "easy", "Easy start",
        _factor(0.7, lo=25),
        fires=lambda c: c.has_history and c.total_sessions_14d <= 2 and c.tsb > -5,
        score=lambda c: 46.0,
    ),
    Situation(
        "cardio.easy_conversational", "cardio", "easy", "Easy session",
        _factor(1.0),
        fires=lambda c: _has_cardio(c) and c.readiness >= 50,
        score=lambda c: 44.0,
    ),

    # ---- Cold start -----------------------------------------------------------
    Situation(
        "onboarding.no_history", "cardio", "easy", "Getting started",
        _fixed(30),
        fires=lambda c: not c.has_history,
        score=lambda c: 15.0,
    ),
]


SITUATIONS_BY_KEY: dict[str, Situation] = {s.key: s for s in SITUATIONS}
