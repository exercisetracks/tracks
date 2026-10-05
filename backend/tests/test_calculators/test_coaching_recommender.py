# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for the no-goal recommender: context building, situation firing, modality
diversity, and (critically) that every authored paragraph renders with no
unfilled slots.

Pure-calc: no app/DB deps. Run with
    cd backend && PYTHONPATH=. uv run --with pytest pytest --noconftest \
        tests/test_calculators/test_coaching_recommender.py
"""

from datetime import date, datetime, timedelta
from types import SimpleNamespace

from app.calculators.coaching import compute_recommendations
from app.calculators.coaching.context import build_context
from app.calculators.coaching.library import PARAGRAPHS, _SafeSlots
from app.calculators.coaching.select import _build_slots, select_recommendations
from app.calculators.coaching.situations import SITUATIONS, SITUATIONS_BY_KEY

TODAY = date(2026, 7, 20)


def _act(days_ago, sport, minutes=50):
    return SimpleNamespace(
        started_at=datetime(2026, 7, 20) - timedelta(days=days_ago),
        sport=sport,
        duration_seconds=int(minutes * 60),
    )


def _readiness(score, conf="high"):
    return SimpleNamespace(score=score, confidence=conf)


def _ctx(*, ctl=50, atl=45, tsb=None, ramp=3, injury=False, hist=None,
         readiness=70, thr=160, strength_dates=None):
    if hist is None:
        hist = [_act(d, "running", 55) for d in (1, 3, 5, 8)]
    if tsb is None:
        tsb = ctl - atl
    return build_context(
        readiness_score=readiness, readiness_confidence="high",
        ctl=ctl, atl=atl, tsb=tsb, ctl_ramp=ramp, injury_risk=injury,
        activity_history=hist, strength_session_dates=strength_dates,
        threshold_hr=thr, today=TODAY,
    )


# ── Context building ──────────────────────────────────────────────────────────

def test_days_since_by_modality():
    hist = [_act(2, "running"), _act(9, "strength_training"), _act(1, "cycling")]
    ctx = _ctx(hist=hist)
    assert ctx.days_since_cardio == 1        # cycling yesterday
    assert ctx.days_since_strength == 9
    assert ctx.dominant_family in {"running", "cycling"}


def test_in_app_strength_folds_into_days_since():
    hist = [_act(2, "running"), _act(4, "running")]
    ctx = _ctx(hist=hist, strength_dates=[TODAY - timedelta(days=1)])
    assert ctx.days_since_strength == 1      # logged gym session, never an Activity


def test_consecutive_and_same_sport_streak():
    hist = [_act(d, "running") for d in (0, 1, 2, 3)] + [_act(4, "cycling")]
    ctx = _ctx(hist=hist)
    assert ctx.consecutive_training_days == 5   # 5 consecutive calendar days
    assert ctx.same_sport_streak == 4           # 4 most-recent sessions are running


def test_no_history_flags_cold_start():
    ctx = _ctx(hist=[])
    assert ctx.has_history is False
    assert ctx.dominant_family is None


# ── Situation firing / scoring ────────────────────────────────────────────────

def test_injury_ramp_wins_hero():
    ctx = _ctx(ctl=60, atl=58, ramp=12, injury=True, readiness=70)
    recs = select_recommendations(ctx, n=3, seed="s")
    assert recs[0].situation == "rest.injury_risk_ramp"
    assert recs[0].modality == "rest"


def test_deep_fatigue_recommends_rest_or_recovery():
    hist = [_act(d, "running", 90) for d in range(7)]
    ctx = _ctx(ctl=60, atl=95, hist=hist, readiness=42, ramp=8)
    recs = select_recommendations(ctx, n=3, seed="s")
    assert recs[0].modality in {"rest", "mobility"}


def test_strength_overdue_fires_for_lapsed_lifter():
    hist = [_act(d, "running", 55) for d in (1, 3, 6)] + [_act(12, "strength_training", 45)]
    ctx = _ctx(hist=hist, ctl=45, atl=40, readiness=72)
    keys = {s.key for s in SITUATIONS if s.fires(ctx)}
    assert "strength.overdue" in keys


def test_cold_start_only_onboarding():
    ctx = _ctx(hist=[], ctl=0, atl=0, ramp=None, readiness=50)
    recs = select_recommendations(ctx, n=3, seed="s")
    assert recs[0].situation == "onboarding.no_history"


# ── Modality diversity ────────────────────────────────────────────────────────

def test_alternates_are_distinct_modalities():
    ctx = _ctx(ctl=55, atl=40, readiness=82)   # fresh, high readiness
    recs = select_recommendations(ctx, n=3, seed="s")
    mods = [r.modality for r in recs]
    assert len(set(mods)) == len(mods), f"repeated modality: {mods}"


def test_rest_hero_only_spawns_mobility_alternates():
    ctx = _ctx(ctl=60, atl=58, ramp=12, injury=True, readiness=70)
    recs = select_recommendations(ctx, n=3, seed="s")
    assert recs[0].modality == "rest"
    assert all(r.modality == "mobility" for r in recs[1:])


# ── Paragraph library integrity ───────────────────────────────────────────────

def test_every_situation_has_paragraphs():
    assert set(PARAGRAPHS) == set(SITUATIONS_BY_KEY), (
        f"missing copy: {set(SITUATIONS_BY_KEY) - set(PARAGRAPHS)}; "
        f"orphan copy: {set(PARAGRAPHS) - set(SITUATIONS_BY_KEY)}"
    )


def test_no_unfilled_slots_in_any_paragraph():
    """Every authored variant, both forms, must fully render for its situation."""
    ctx = _ctx(ctl=55, atl=45, ramp=9, readiness=70)
    for key, forms in PARAGRAPHS.items():
        sit = SITUATIONS_BY_KEY[key]
        slots = _build_slots(sit, ctx, sit.duration(ctx))
        for form, variants in forms.items():
            for i, template in enumerate(variants):
                out = template.format_map(_SafeSlots(slots))
                assert "{" not in out and "}" not in out, f"unfilled slot in {key}/{form}[{i}]: {out}"
                assert out.strip(), f"empty render for {key}/{form}[{i}]"


def test_no_paragraph_contains_emoji_or_em_dash():
    for key, forms in PARAGRAPHS.items():
        for variants in forms.values():
            for t in variants:
                assert "—" not in t and " -- " not in t, f"em dash in {key}"
                assert all(ord(ch) < 0x2190 for ch in t), f"suspect glyph/emoji in {key}"


# ── Full pipeline ─────────────────────────────────────────────────────────────

def test_compute_recommendations_end_to_end():
    hist = [_act(d, "running", 60) for d in (1, 3, 5, 8, 11)]
    res = compute_recommendations(
        readiness_result=_readiness(80), ctl=55, atl=42, ctl_7d_ago=52,
        activity_history=hist, goal=None, threshold_hr=160, today=TODAY,
        n_recommendations=3,
    )
    assert 1 <= len(res.recommendations) <= 3
    for r in res.recommendations:
        assert r.modality in {"cardio", "strength", "mobility", "rest"}
        assert r.description and "{" not in r.description
