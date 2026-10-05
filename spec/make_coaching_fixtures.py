# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the coaching-engine corpus.

The backend's own no-goal recommender, run in-process:
calculators/coaching/ (signal, context, the situation catalogue, selection
and the paragraph library). The phone's port in mobile/core
(com.tracks.core.coaching) replays every case and must agree exactly — the
same situation chosen, the same paragraph variant, the same words.

Regenerable: the Python stays the independent oracle. Rerun after changing
the engine or any file in backend/app/data/coaching_paragraphs/ (and rerun
spec/make_coaching_data.py so the phone carries the same words).

Synthetic and deterministic: invented load values and histories chosen to
fire every situation, with fixed dates.

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_coaching_fixtures.py
"""

from __future__ import annotations

import json
import sys
from dataclasses import asdict
from datetime import date, datetime, timedelta
from pathlib import Path
from random import Random
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators.coaching import engine  # noqa: E402
from app.calculators.coaching.context import build_context  # noqa: E402
from app.calculators.coaching.library import PARAGRAPHS, render  # noqa: E402
from app.calculators.coaching.select import select_recommendations  # noqa: E402
from app.calculators.coaching.signal import _compute_ctl_atl  # noqa: E402

OUT = Path("/spec/fixtures/coaching.json")

SPORTS = ["running", "trail_running", "cycling", "mountain_biking", "swimming", "rowing", "hiking",
          "strength_training", "yoga", "Pilates Class", "breathwork", "walking", "kayaking", "tennis"]


def _plain(v):
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def _history(today: date, rng: Random) -> tuple[list, list]:
    acts = []
    for _ in range(rng.choice([0, 1, 3, 8, 20, 40])):
        d = today - timedelta(days=rng.choice([0, 0, 1, 1, 2, 3, 5, 8, 13, 20, 30, 60, 85]))
        started = datetime(d.year, d.month, d.day, rng.randint(5, 20)) if rng.random() < 0.5 else d
        acts.append(SimpleNamespace(
            sport=rng.choice(SPORTS + [None, ""]),
            started_at=rng.choice([started, started, None]),
            duration_seconds=rng.choice([None, 0, 1800, 3600, 4500, 5400, 9000]),
        ))
    sessions = [today - timedelta(days=rng.choice([0, 2, 6, 9, 15, 25]))
                for _ in range(rng.choice([0, 0, 1, 3]))]
    return acts, sessions


def _acts_json(acts):
    return [{"sport": a.sport,
             "started": (a.started_at.date() if isinstance(a.started_at, datetime) else a.started_at).isoformat()
             if a.started_at else None,
             "duration_seconds": a.duration_seconds} for a in acts]


def recommendation_cases() -> list:
    rng = Random("coaching")
    out = []
    todays = [date(2026, 3, 4), date(2026, 7, 19), date(2027, 1, 1)]
    for i in range(160):
        today = rng.choice(todays)
        acts, sessions = _history(today, rng)
        ctl = rng.choice([0.0, 8.5, 25.0, 45.3, 62.1, 90.0])
        atl = ctl + rng.choice([-40.0, -26.0, -12.0, -3.0, 0.0, 9.0, 20.0, 45.0])
        atl = max(atl, 0.0)
        ctl7 = rng.choice([None, ctl - 10.0, ctl - 2.0, ctl + 1.5])
        goal = rng.choice([None, SimpleNamespace(goal_type="event", event_date=today + timedelta(days=rng.randint(-5, 120))),
                           # The ramp from the loop index, not rng: another draw
                           # would reshuffle every case after it.
                           SimpleNamespace(goal_type="fitness", event_date=None,
                                           ctl_ramp_per_week=[-1.5, 0.0, 3.0, None][i % 4])])
        readiness = SimpleNamespace(score=rng.choice([20.0, 45.0, 55.5, 70.0, 77.9, 92.0, 50]),
                                    confidence=rng.choice(["low", "high"]))
        lthr = rng.choice([None, 0.0, 162.0, 171.5])
        n = rng.choice([1, 3, 4])
        res = engine.compute_recommendations(
            readiness_result=readiness, ctl=ctl, atl=atl, ctl_7d_ago=ctl7, activity_history=acts, goal=goal,
            threshold_hr=lthr, today=today, n_recommendations=n, strength_session_dates=sessions)
        ctx = build_context(
            readiness_score=readiness.score, readiness_confidence=readiness.confidence, ctl=ctl, atl=atl,
            tsb=res.signal.tsb, ctl_ramp=res.signal.ctl_ramp, injury_risk=res.signal.injury_risk_warning,
            activity_history=acts, strength_session_dates=sessions, threshold_hr=lthr, today=today)
        out.append({
            "today": today.isoformat(), "ctl": ctl, "atl": atl, "ctl7": ctl7,
            "goal": None if goal is None else {"goal_type": goal.goal_type,
                                               "event_date": goal.event_date.isoformat() if goal.event_date else None,
                                               "ctl_ramp_per_week": getattr(goal, "ctl_ramp_per_week", None)},
            "readiness": readiness.score, "confidence": readiness.confidence, "lthr": lthr, "n": n,
            "activities": _acts_json(acts), "sessions": [s.isoformat() for s in sessions],
            "context": _plain(asdict(ctx)),
            "signal": asdict(res.signal),
            "recommendations": [asdict(r) for r in res.recommendations],
        })
    return out


def weekly_cases() -> list:
    rng = Random("coaching-weekly")
    out = []
    for i in range(8):
        today = date(2026, 5, 10) + timedelta(days=i * 11)
        acts, _ = _history(today, rng)
        tss = {today - timedelta(days=d): round(rng.uniform(0, 160), 1)
               for d in rng.sample(range(1, 70), rng.choice([0, 5, 30]))}
        readiness = SimpleNamespace(score=rng.choice([40.0, 65.0, 85.0]), confidence="high")
        lthr = rng.choice([None, 165.0])
        plan = engine.compute_weekly_plan(readiness, tss, acts, None, lthr, today)
        out.append({
            "today": today.isoformat(), "readiness": readiness.score,
            "tss": [[d.isoformat(), v] for d, v in tss.items()],
            "activities": _acts_json(acts), "lthr": lthr,
            "plan": [{**_plain({k: v for k, v in p.items() if k != "recommendation"}),
                      "recommendation": asdict(p["recommendation"]) if p["recommendation"] else None}
                     for p in plan],
        })
    return out


def render_cases() -> list:
    slots = {"tsb": -12.46, "readiness": 71, "ramp": 8.5, "streak": 4, "recent_count": 9, "duration": 45,
             "sport": "run", "alt_sport": "ride", "zone_desc": "Zone 2, easy", "focus": "full body",
             "muscle_targets": "hip flexors, calves, and hamstrings", "days_since": 3,
             "_fallback": "Easy session: about 45 min."}
    out = []
    for key in sorted(PARAGRAPHS):
        for form in ("full", "compact", "missing_form"):
            for seed in ("2026-03-04", "2026-03-05", ""):
                out.append([key, form, seed, render(key, form, slots, seed=seed)])
    out.append(["no.such.key", "full", "x", render("no.such.key", "full", slots, seed="x")])
    for tsb in (0.4, -0.4, 0.5, 1.5, -2.5, 12.0):
        out.append(["cardio.tempo_productive", "full", f"tsb{tsb}",
                    render("cardio.tempo_productive", "full", {**slots, "tsb": tsb}, seed="2026-01-01")])
    return [{"slots": slots, "cases": out}]


def ctl_cases() -> list:
    rng = Random("ctl")
    out = []
    for _ in range(10):
        start = date(2026, 1, 1) + timedelta(days=rng.randint(0, 30))
        tss = {start + timedelta(days=rng.randint(0, 60)): round(rng.uniform(0, 200), 2) for _ in range(rng.randint(0, 25))}
        for end in (start - timedelta(days=1), start + timedelta(days=30), start + timedelta(days=90)):
            out.append({"tss": [[d.isoformat(), v] for d, v in tss.items()], "end": end.isoformat(),
                        "expect": _compute_ctl_atl(tss, end)})
    return out


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_coaching_fixtures.py from the backend's own coaching engine. Do not edit.",
        "recommendations": recommendation_cases(),
        "weekly": weekly_cases(),
        "render": render_cases(),
        "ctl_atl": ctl_cases(),
    }
    OUT.write_text(json.dumps(corpus, separators=(",", ":"), allow_nan=False, ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
