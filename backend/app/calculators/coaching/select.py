# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Selection: turn a RecommenderContext into a hero recommendation plus alternates
of *different* modalities, each carrying rendered coaching copy.

Flow:
  1. Every Situation whose ``fires`` predicate holds is scored.
  2. The highest score becomes the hero (full paragraph).
  3. Alternates are the next-best situations from *other* modalities (compact
     paragraphs), so the card never shows three variations of one workout, which
     was the whole point of the overhaul. On a rest-day hero, alternates are
     limited to mobility so the card never suggests a hard session on a bad day.

Zone descriptors, TSS estimates, and HR targets reuse the coaching load
vocabulary so a recommended "tempo" reads consistently with a scheduled one.
"""

from __future__ import annotations

from app.calculators.coaching.context import RecommenderContext, sport_noun
from app.calculators.coaching.library import render
from app.calculators.coaching.models import WorkoutRecommendation
from app.calculators.coaching.situations import SITUATIONS, Situation

# Intensity → (%LTHR low, %LTHR high, human zone label). Shared vocabulary with
# the coaching HR-zone tables so copy lines up with the rest of the app.
_ZONE: dict[str, tuple[float, float, str]] = {
    "recovery": (0.60, 0.70, "Zone 1, very easy"),
    "easy":     (0.70, 0.80, "Zone 2, easy"),
    "aerobic":  (0.75, 0.83, "Zone 2 aerobic"),
    "tempo":    (0.83, 0.91, "Zone 3 tempo"),
    "quality":  (0.92, 1.02, "Zone 4-5, hard"),
}

# Rough TSS per hour by intensity, for the weekly-plan projection loop.
_TSS_PER_HOUR: dict[str, int] = {
    "recovery": 30, "easy": 45, "aerobic": 60, "tempo": 80, "quality": 100,
    "heavy": 50, "strength": 45, "moderate": 40, "mobility": 15, "rest": 0,
}


def _zone_desc(intensity: str, lthr: float | None) -> str:
    z = _ZONE.get(intensity)
    if not z:
        return "an easy pace"
    lo, hi, name = z
    if lthr:
        return f"{name} ({int(lthr * lo)} to {int(lthr * hi)} bpm)"
    return name


def _hr_range(intensity: str, lthr: float | None) -> tuple[int | None, int | None]:
    z = _ZONE.get(intensity)
    if not z or not lthr:
        return None, None
    lo, hi, _ = z
    return int(lthr * lo), int(lthr * hi)


def _days_since_for(sit: Situation, ctx: RecommenderContext) -> int:
    if sit.modality == "strength":
        return ctx.days_since_strength or 0
    if sit.modality == "mobility":
        return ctx.days_since_mobility or 0
    return 0


def _sport_display(sit: Situation, ctx: RecommenderContext) -> str:
    if sit.modality == "cardio":
        return ctx.dominant_family or "cardio"
    return sit.modality


def _focus_display(sit: Situation, ctx: RecommenderContext) -> str | None:
    if sit.modality == "strength":
        return sit.focus
    if sit.modality == "mobility" and sit.focus == "sport":
        return ctx.mobility_muscles()
    return None


def _build_slots(sit: Situation, ctx: RecommenderContext, duration: int) -> dict:
    return {
        "tsb": ctx.tsb,
        "readiness": int(round(ctx.readiness)),
        "ramp": round(ctx.ctl_ramp or 0.0, 1),
        "streak": ctx.consecutive_training_days,
        "recent_count": ctx.total_sessions_14d,
        "duration": duration,
        "sport": sport_noun(ctx.dominant_family) if ctx.dominant_family else "cardio session",
        "alt_sport": sport_noun(ctx.alt_family) if ctx.alt_family else "something different",
        "zone_desc": _zone_desc(sit.intensity, ctx.threshold_hr),
        "focus": sit.focus if sit.modality == "strength" else "the work you do",
        "muscle_targets": ctx.mobility_muscles(),
        "days_since": _days_since_for(sit, ctx),
        "_fallback": f"{sit.title}: about {duration} min.",
    }


def _to_recommendation(sit: Situation, ctx: RecommenderContext, form: str, seed: str) -> WorkoutRecommendation:
    duration = sit.duration(ctx)
    slots = _build_slots(sit, ctx, duration)
    text = render(sit.key, form, slots, seed=seed)
    hr_min, hr_max = (_hr_range(sit.intensity, ctx.threshold_hr) if sit.modality == "cardio" else (None, None))
    projected_tss = round(_TSS_PER_HOUR.get(sit.intensity, 45) * duration / 60.0, 1)
    return WorkoutRecommendation(
        sport=_sport_display(sit, ctx),
        intensity=sit.intensity,
        duration_minutes=duration,
        distance_km=None,
        hr_min=hr_min,
        hr_max=hr_max,
        description=text,
        reasoning="",
        projected_tss=projected_tss,
        modality=sit.modality,
        title=sit.title,
        focus=_focus_display(sit, ctx),
        situation=sit.key,
    )


def select_recommendations(
    ctx: RecommenderContext,
    n: int = 3,
    seed: str = "",
) -> list[WorkoutRecommendation]:
    """Pick a hero plus up to n-1 alternates of different modalities."""
    fired = [s for s in SITUATIONS if _safe_fires(s, ctx)]
    fired.sort(key=lambda s: (-_safe_score(s, ctx), s.key))

    if not fired:
        # Should not happen (onboarding/easy_conversational are broad), but keep
        # the card populated rather than empty.
        fired = [SITUATIONS[-1]]  # onboarding.no_history

    hero = fired[0]
    recs = [_to_recommendation(hero, ctx, "full", seed)]

    # Rest days must not spawn hard alternates.
    allowed = {"mobility"} if hero.modality == "rest" else None
    used_modalities = {hero.modality}
    for sit in fired[1:]:
        if len(recs) >= n:
            break
        if sit.modality in used_modalities:
            continue
        if allowed is not None and sit.modality not in allowed:
            continue
        recs.append(_to_recommendation(sit, ctx, "compact", seed))
        used_modalities.add(sit.modality)

    return recs


def _safe_fires(sit: Situation, ctx: RecommenderContext) -> bool:
    try:
        return bool(sit.fires(ctx))
    except Exception:
        return False


def _safe_score(sit: Situation, ctx: RecommenderContext) -> float:
    try:
        return float(sit.score(ctx))
    except Exception:
        return 0.0
