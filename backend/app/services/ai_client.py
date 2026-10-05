# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Generic LLM client for AI-enhanced coaching recommendations.

Supports three providers:
  - ollama     local inference via Ollama REST API
  - anthropic  Anthropic Messages API
  - openai     OpenAI Chat Completions API (and compatible endpoints)

The rule engine always runs first and produces structured recommendations.
This client takes that output as context and asks the LLM for richer,
plain-English guidance.  The AI is an enhancer, not the decision-maker.
"""

from __future__ import annotations

import logging

import httpx

log = logging.getLogger(__name__)

_TIMEOUT = 30.0  # seconds

_SYSTEM_PROMPT = """You are an expert endurance sports coach with deep knowledge of
exercise physiology, periodisation, and injury prevention.  You will be given a
structured training readiness report and a list of rule-based workout recommendations.
Your job is to enhance these recommendations with specific, practical guidance:
- Explain the physiological reasoning in plain language
- Add terrain, pacing, or technique cues specific to each sport
- Flag any concerns (e.g. high ramp rate, poor sleep) with actionable advice
- Keep each recommendation to 2-3 sentences
Be encouraging but honest.  Do not invent metrics or override the rule-based plan."""


def _build_prompt(context: dict) -> str:
    """Build the user-facing prompt from the coaching context dict."""
    r = context.get("readiness", {})
    s = context.get("signal", {})
    recs = context.get("recommendations", [])
    goal = context.get("goal")

    lines = [
        f"**Readiness:** {r.get('score', '?')}/100",
        f"  - Primary driver: {r.get('primary_driver', 'default')} ({r.get('confidence', 'low')} confidence)",
        f"  - HRV: {r.get('hrv_today', 'N/A')} (baseline {r.get('hrv_baseline', 'N/A')})",
        f"  - Sleep: {r.get('sleep_hours', 'N/A')}h (score: {r.get('garmin_sleep_score', 'N/A')})",
        f"  - Resting HR: {r.get('resting_hr_today', 'N/A')} bpm (baseline {r.get('resting_hr_baseline', 'N/A')})",
        "",
        f"**Training load:** CTL {s.get('ctl', '?')}, ATL {s.get('atl', '?')}, TSB {s.get('tsb', '?')}",
    ]
    if s.get("ctl_ramp") is not None:
        lines.append(f"  - CTL ramp: {s['ctl_ramp']:+.1f} pts/week"
                     + (" (HIGH)" if s.get("injury_risk_warning") else ""))
    if s.get("phase"):
        lines.append(f"  - Training phase: {s['phase']}")
    if goal:
        lines.append(f"  - Active goal: {goal}")

    lines += ["", "**Rule-based recommendations:**"]
    for i, rec in enumerate(recs, 1):
        lines.append(
            f"{i}. {rec.get('sport', '?').replace('_', ' ').title()} — "
            f"{rec.get('intensity', '?')} — "
            f"{rec.get('duration_minutes', '?')} min"
            + (f" (~{rec['distance_km']:.1f} km)" if rec.get("distance_km") else "")
            + (f", HR {rec['hr_min']}–{rec['hr_max']} bpm" if rec.get("hr_min") else "")
        )
        lines.append(f"   Reasoning: {rec.get('reasoning', '')}")

    lines += [
        "",
        "Please provide enhanced coaching notes for each recommendation above.",
        "Keep each to 2-3 sentences with specific, actionable guidance.",
    ]
    return "\n".join(lines)


async def enhance_recommendations(
    context: dict,
    provider: str,
    endpoint: str,
    model: str,
    api_key: str | None,
) -> str | None:
    """
    Send the coaching context to an LLM and return its enhanced text response.
    Returns None on any error (the caller falls back to rule-based output).
    """
    prompt = _build_prompt(context)

    try:
        if provider == "ollama":
            return await _call_ollama(endpoint, model, prompt)
        if provider == "anthropic":
            return await _call_anthropic(endpoint or "https://api.anthropic.com", model, api_key, prompt)
        if provider == "openai":
            return await _call_openai(endpoint or "https://api.openai.com", model, api_key, prompt)
    except Exception as exc:
        log.warning(f"AI enhancement failed ({provider}): {exc}")

    return None


# ─────────────────────────────────────────
# Provider implementations
# ─────────────────────────────────────────

async def _call_ollama(base_url: str, model: str, prompt: str) -> str:
    url = base_url.rstrip("/") + "/api/chat"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user",   "content": prompt},
        ],
        "stream": False,
    }
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(url, json=payload)
        resp.raise_for_status()
        return resp.json()["message"]["content"]


async def _call_anthropic(base_url: str, model: str, api_key: str | None, prompt: str) -> str:
    url = base_url.rstrip("/") + "/v1/messages"
    headers = {
        "x-api-key": api_key or "",
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
    }
    payload = {
        "model": model,
        "max_tokens": 1024,
        "system": _SYSTEM_PROMPT,
        "messages": [{"role": "user", "content": prompt}],
    }
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["content"][0]["text"]


async def _call_openai(base_url: str, model: str, api_key: str | None, prompt: str) -> str:
    url = base_url.rstrip("/") + "/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key or ''}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user",   "content": prompt},
        ],
        "max_tokens": 1024,
    }
    async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
        resp = await client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]
