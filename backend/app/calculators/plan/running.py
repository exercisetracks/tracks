# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Running workout builders.

Builds structured workout step lists for all running workout types.
Uses three-tier run/walk break system based on continuous-run capacity,
with 6-way variation cycling to prevent session repetition.

References
----------
- Daniels, J. (2013). *Daniels' Running Formula* (3rd ed.). Human Kinetics.
  VDOT zones, interval/repetition prescriptions, E/M/T/I/R pace zones.
- Pfitzinger, P., & Douglas, S. (2014). *Advanced Marathoning* (3rd ed.).
  Long run with marathon-pace block, recovery run protocols.
- Paavolainen et al. (1999). Explosive-strength training improves 5-km
  running time by improving running economy and muscle power. *J Appl Physiol*,
  86(5), 1527-1533. — Strides improve running economy ~2-3% with no VO₂max change.
- Barnes et al. (2013). Running economy: a review of the evidence.
  *Sports Med*, 43(8), 689-707. — Hill sprints improve RE ~2-3% in trained runners.
- Midgley et al. (2006). Training to enhance the physiological determinants
  of long-distance running performance. *Sports Med*, 36(2), 117-142.
  HIIT at ≥90% VO₂max for 2-4 min produces 5-15% VO₂max gains.

Workout type hierarchy (effect on performance, Midgley/Laursen/Seiler synthesis):
  1. Intervals (HIIT, 4-8 min @ 90-100% VO₂max) → strongest VO₂max driver
  2. Tempo/threshold (20-60 min @ 85-90%) → strongest lactate threshold driver
  3. Long run → capillarization, fat oxidation, durability
  4. Fartlek → mixed aerobic/anaerobic stimulus, lower monotony
  5. Race-pace → specificity, neural patterning
  6. Strides → running economy maintenance, neuromuscular freshness
  7. Hill sprints → running economy (~2-3%), low-injury-risk power work
  8. Easy/recovery → mitochondrial biogenesis, recovery, volume base

Polarized 80/20 principle (Seiler 2010, Stöggl & Sperlich 2014):
  ~80% of sessions at low intensity (easy, long easy), ~20% at high intensity
  (intervals, tempo, race-pace). Very little threshold/moderate work.
  This distribution outperforms threshold-dominant and HIIT-only approaches
  for endurance performance (POL: +11.7% VO₂max vs HIIT-only: +4.8%;
  Stöggl & Sperlich 2014, n=48, p<0.001).
"""

from __future__ import annotations

from app.calculators.plan.base import (
    _fmt_dist_m,
    _fmt_pace,
    _walk_cooldown,
    _walk_warmup,
)

# ─────────────────────────────────────────
# Variation tables — 6 variants per type
# Indexed by variation % 6 so no two consecutive sessions are identical.
# Previously 4 variants; expanded to 6 for greater variety.
# ─────────────────────────────────────────

_EASY_T1_VAR: tuple[int, ...] = (0, +5, -5, +3, +8, -3)

_EASY_T2_VAR: tuple[tuple[int, int], ...] = (
    (2, 60),
    (2, 45),
    (3, 60),
    (2, 75),
    (3, 45),
    (2, 90),
)

_EASY_T3_VAR: tuple[tuple[int, int], ...] = (
    ( 0,   0),
    (+1, -15),
    ( 0, +15),
    (-1,   0),
    (+1, +10),
    (-1, -10),
)

_FARTLEK_VAR: tuple[tuple[int, int], ...] = (
    (3, 2),
    (2, 2),
    (3, 1),
    (4, 2),
    (2, 1),
    (4, 3),
)

_INTERVAL_VAR: tuple[tuple[int, int], ...] = (
    ( 0,   0),
    (+1, -15),
    ( 0, +15),
    (-1,   0),
    (+1, +10),
    (-1, -10),
)

_TEMPO_VAR: tuple[tuple[int, int], ...] = (
    (15, 10),
    (12, 10),
    (15, 12),
    (10, 10),
    (12, 12),
    (10, 12),
)

# Hill sprint variants: (reps, work_sec, rest_sec)
# Barnes et al. (2013): 6 weeks, 2-3×/week of 10-14 × 10s hill sprints
# improved running economy ~2-3%. Ferley et al. (2014): 10 × 30s @ 10% grade.
_HILL_SPRINT_VAR: tuple[tuple[int, int, int], ...] = (
    (10, 10, 90),
    (12,  8, 60),
    ( 8, 15, 90),
    (10, 12, 60),
    ( 6, 20, 120),
    (12, 10, 60),
)


# ─────────────────────────────────────────
# Run/walk break system
# ─────────────────────────────────────────

def _run_with_breaks(duration_min: int, paces: dict, capacity_km: float,
                     variation: int = 0, structural_min: int | None = None,
                     imperial: bool = False) -> list[dict]:
    """
    Three-tier run format based on how far the session exceeds capacity.

    ≤ 85 % of capacity  → single continuous run (Tier 1)
    85–150 % of capacity → 2–3 segments with a short walk break (Tier 2)
    > 150 % of capacity  → N × sub-capacity reps with scaled walk breaks (Tier 3)

    structural_min: pre-intensity-scaling duration used only for tier selection.
    When provided, lowering intensity never removes breaks — the break structure
    is locked to what the unscaled workload would require.
    """
    pace_sec_km = paces["easy"]
    ep = _fmt_pace(pace_sec_km, imperial)
    check_min  = structural_min if structural_min is not None else duration_min
    session_km = check_min / 60 * (3600 / pace_sec_km)
    actual_km  = duration_min / 60 * (3600 / pace_sec_km)
    ratio = session_km / capacity_km
    v = variation % 6

    if ratio <= 0.85:
        var_min = max(15, duration_min + _EASY_T1_VAR[v])
        return [
            _walk_warmup(),
            {"type": "run", "duration_min": var_min, "pace": "easy",
             "note": f"Conversational pace · {ep} (Zone 2)"},
            _walk_cooldown(),
        ]

    if ratio <= 1.5:
        var_reps, var_rest = _EASY_T2_VAR[v]
        # A segment may be as long as the runner can run continuously. It was
        # capped at 2 km, which for a trained runner near capacity (a 20 km
        # long run) quietly cut the session to 3 × 2 km — the break structure
        # deciding the distance instead of the plan.
        seg_cap = max(2000, round(capacity_km * 850 / 100) * 100)
        seg_m = max(400, min(seg_cap, round(actual_km * 1000 / var_reps / 100) * 100))
        return [
            _walk_warmup(),
            {
                "type": "interval_set",
                "reps": var_reps,
                "distance_m": seg_m,
                "rest_sec": var_rest,
                "pace": "easy",
                "note": f"{var_reps}× {_fmt_dist_m(seg_m, imperial)} at {ep} / {var_rest} s walk",
            },
            _walk_cooldown(),
        ]

    base_dist_m = max(400, min(2000, round(capacity_km * 4) * 100))
    deficit_ratio = (session_km - capacity_km) / session_km
    base_walk_sec = max(30, min(120, round(90 * deficit_ratio / 30) * 30))
    run_sec   = base_dist_m / 1000 * pace_sec_km
    base_reps = max(3, int(duration_min * 60 / (run_sec + base_walk_sec)))
    d_reps, d_rest = _EASY_T3_VAR[v]
    var_reps = max(2, base_reps + d_reps)
    var_rest = max(30, min(120, base_walk_sec + d_rest))
    var_dist_m = max(200, min(2000, round(base_reps * base_dist_m / var_reps / 100) * 100))

    return [
        _walk_warmup(),
        {
            "type": "interval_set",
            "reps": var_reps,
            "distance_m": var_dist_m,
            "rest_sec": var_rest,
            "pace": "easy",
            "note": f"{var_reps}× {_fmt_dist_m(var_dist_m, imperial)} at {ep} / {var_rest} s walk",
        },
        _walk_cooldown(),
    ]


def _run_recovery(duration_min: int, paces: dict, variation: int = 0,
                  imperial: bool = False) -> list[dict]:
    """Active recovery jog — stays below VT1 (62% VO₂max velocity). Capped at 40 min."""
    rp = _fmt_pace(paces.get("recovery", paces["easy"]), imperial)
    run_min = max(20, min(40, duration_min + _EASY_T1_VAR[variation % 6]))
    return [
        _walk_warmup(),
        {"type": "run", "duration_min": run_min, "pace": "recovery",
         "note": f"Recovery jog · {rp} · below VT1 · fully conversational"},
        _walk_cooldown(),
    ]


def _run_easy(duration_min: int, paces: dict,
              capacity_km: float | None = None, variation: int = 0,
              structural_min: int | None = None, imperial: bool = False) -> list[dict]:
    """Easy conversational run; uses run/walk if session exceeds capacity."""
    if capacity_km is not None:
        return _run_with_breaks(duration_min, paces, capacity_km, variation=variation,
                                structural_min=structural_min, imperial=imperial)
    p = _fmt_pace(paces["easy"], imperial)
    return [
        _walk_warmup(),
        {"type": "run", "duration_min": duration_min, "pace": "easy",
         "note": f"Conversational pace · {p} (Zone 2)"},
        _walk_cooldown(),
    ]


# The shortest race whose long run carries a marathon-pace block.
_MP_BLOCK_MIN_RACE_M = 21000


def _run_long(total_min: int, phase: str, paces: dict,
              capacity_km: float | None = None,
              structural_min: int | None = None, imperial: bool = False,
              race_distance_m: float = 42195) -> list[dict]:
    """
    Long run. In build/peak phases of a half-marathon or longer, includes a
    marathon-pace block (~28% of duration) for race specificity (Pfitzinger &
    Douglas 2014). If session exceeds running capacity, uses run/walk
    regardless of phase.

    A 5K or 10K plan's long run stays easy. The marathon-pace block is
    specific preparation for races run near marathon pace; below the half
    it is not race-specific, and it made the long run a third hard session in
    a build week that already held intervals and a tempo run — against the
    polarised ~80/20 the rest of the plan keeps (Seiler 2010). Pfitzinger's
    own 5K–15K schedules (*Faster Road Racing*) keep the long run aerobic.
    """
    mp, ep = _fmt_pace(paces["marathon"], imperial), _fmt_pace(paces["easy"], imperial)

    if capacity_km is not None:
        pace_sec_km = paces["easy"]
        check_min   = structural_min if structural_min is not None else total_min
        session_km  = check_min / 60 * (3600 / pace_sec_km)
        if session_km > capacity_km * 1.05:
            return _run_with_breaks(total_min, paces, capacity_km,
                                    structural_min=structural_min, imperial=imperial)

    if phase in ("build", "peak") and total_min >= 70 and race_distance_m >= _MP_BLOCK_MIN_RACE_M:
        mp_min   = min(30, int(total_min * 0.28))
        warmup   = int((total_min - mp_min) * 0.75)
        cooldown = total_min - mp_min - warmup
        return [
            _walk_warmup(),
            {"type": "run", "duration_min": warmup,   "pace": "easy",     "note": f"Easy warm-up · {ep}"},
            {"type": "run", "duration_min": mp_min,   "pace": "marathon", "note": f"Marathon goal pace · {mp}"},
            {"type": "run", "duration_min": cooldown, "pace": "easy",     "note": f"Easy cool-down · {ep}"},
            _walk_cooldown(),
        ]
    return [
        _walk_warmup(),
        {"type": "run", "duration_min": total_min, "pace": "easy",
         "note": f"Easy conversational pace · {ep}"},
        _walk_cooldown(),
    ]


def _run_tempo(total_min: int, paces: dict, variation: int = 0,
               imperial: bool = False) -> list[dict]:
    """
    Threshold-pace tempo run. 4-6 structural variants cycle across occurrences.
    Threshold work has the strongest effect on lactate threshold (Midgley et al. 2006).
    """
    rp = _fmt_pace(paces.get("recovery", paces["easy"]), imperial)
    tp = _fmt_pace(paces["threshold"], imperial)
    warmup, cooldown = _TEMPO_VAR[variation % 6]
    tempo = max(15, total_min - warmup - cooldown)
    return [
        _walk_warmup(),
        {"type": "warmup",   "duration_min": warmup,   "pace": "recovery",  "note": f"Easy jog · {rp}"},
        {"type": "run",      "duration_min": tempo,    "pace": "threshold", "note": f"Comfortably hard · {tp} (threshold)"},
        {"type": "cooldown", "duration_min": cooldown, "pace": "recovery",  "note": f"Easy jog · {rp}"},
        _walk_cooldown(),
    ]


def _run_intervals(build_idx: int, paces: dict, variation: int = 0,
                   imperial: bool = False) -> list[dict]:
    """
    VO₂max intervals — progressive ladder over the build phase.
    Daniels (2013): 4-8 min at 95-100% VO₂max with equal jog recovery.
    6 structural variants cycle each occurrence.

    Ladder: 6×400m → 6×800m → 5×1000m → 4×1500m as build_idx increases.
    """
    ip  = _fmt_pace(paces["interval"], imperial)
    tp  = _fmt_pace(paces["threshold"], imperial)
    rp  = _fmt_pace(paces.get("recovery", paces["easy"]), imperial)
    if build_idx <= 1:   base_reps, dist, base_rest, zone = 6,  400,  90, "interval"
    elif build_idx <= 3: base_reps, dist, base_rest, zone = 6,  800,  90, "interval"
    elif build_idx <= 5: base_reps, dist, base_rest, zone = 5, 1000,  90, "threshold"
    else:                base_reps, dist, base_rest, zone = 4, 1500, 120, "interval"
    pace_str = ip if zone == "interval" else tp

    d_reps, d_rest = _INTERVAL_VAR[variation % 6]
    reps = max(2, base_reps + d_reps)
    rest = max(30, base_rest + d_rest)
    desc = f"{reps}× {_fmt_dist_m(dist, imperial)} at {pace_str} / {rest} s rest"
    return [
        _walk_warmup(),
        {"type": "warmup",       "duration_min": 15,  "pace": "recovery", "note": f"Easy jog · {rp}"},
        {"type": "interval_set", "reps": reps, "distance_m": dist,
         "rest_sec": rest, "pace": zone, "note": desc},
        {"type": "cooldown",     "duration_min": 10,  "pace": "recovery", "note": f"Easy jog · {rp}"},
        _walk_cooldown(),
    ]


def _run_race_pace(paces: dict, race_distance_m: float,
                   imperial: bool = False) -> list[dict]:
    """Race-pace specific workout — distance-dependent pace and structure."""
    ep = _fmt_pace(paces["easy"], imperial)
    if race_distance_m > 80000:
        pp, zone = _fmt_pace(paces["easy"], imperial), "easy"
        reps, dist, note = 3, 2000, f"{_fmt_dist_m(2000, imperial)} at ultra effort · {pp} (easy, sustained)"
    elif race_distance_m > 42200:
        pp, zone = _fmt_pace(paces["marathon"], imperial), "marathon"
        reps, dist, note = 3, 2000, f"{_fmt_dist_m(2000, imperial)} at ultra race effort · {pp}"
    elif race_distance_m >= 21000:
        pp, zone = _fmt_pace(paces["marathon"], imperial), "marathon"
        reps, dist, note = 4, 2000, f"{_fmt_dist_m(2000, imperial)} at marathon goal pace · {pp}"
    else:
        pp, zone = _fmt_pace(paces["threshold"], imperial), "threshold"
        reps, dist, note = 5, 1000, f"{_fmt_dist_m(1000, imperial)} at race pace · {pp}"
    return [
        _walk_warmup(),
        {"type": "warmup",       "duration_min": 15, "pace": "easy", "note": f"Easy jog · {ep}"},
        {"type": "interval_set", "reps": reps, "distance_m": dist, "rest_sec": 120, "pace": zone, "note": note},
        {"type": "cooldown",     "duration_min": 10, "pace": "easy", "note": f"Easy jog · {ep}"},
        _walk_cooldown(),
    ]


def _run_fartlek(duration_min: int, paces: dict, variation: int = 0,
                 imperial: bool = False) -> list[dict]:
    """
    Fartlek (speed play) — unstructured intensity variation.
    6 structural variants rotate intervals, reducing monotony while providing
    a mixed aerobic/anaerobic stimulus.
    """
    rp = _fmt_pace(paces.get("recovery", paces["easy"]), imperial)
    tp = _fmt_pace(paces["threshold"], imperial)
    main = max(20, duration_min - 15)
    hard_min, easy_min = _FARTLEK_VAR[variation % 6]
    block = hard_min + easy_min
    reps = max(2, main // block)
    return [
        _walk_warmup(),
        {"type": "warmup",   "duration_min": 10,           "pace": "recovery",  "note": f"Easy jog · {rp}"},
        {"type": "fartlek",  "duration_min": reps * block,
         "hard_min": hard_min, "easy_min": easy_min, "reps": reps,
         "pace": "threshold",
         "note": f"{hard_min} min hard ({tp}) / {easy_min} min easy — {reps} rounds"},
        {"type": "cooldown", "duration_min": 5,            "pace": "recovery",  "note": f"Easy jog · {rp}"},
        _walk_cooldown(),
    ]


def _run_short_quality(paces: dict, imperial: bool = False) -> list[dict]:
    """Short strides session — neuromuscular maintenance (Paavolainen et al. 1999)."""
    ep, rp = _fmt_pace(paces["easy"], imperial), _fmt_pace(paces["repetition"], imperial)
    return [
        _walk_warmup(),
        {"type": "run",          "duration_min": 30, "pace": "easy",      "note": f"Easy jog · {ep}"},
        {"type": "interval_set", "reps": 6, "distance_m": 100, "rest_sec": 60,
         "pace": "repetition",   "note": f"{_fmt_dist_m(100, imperial)} stride · {rp} · full recovery between"},
        _walk_cooldown(),
    ]


def _run_hill_sprints(paces: dict, variation: int = 0,
                      imperial: bool = False) -> list[dict]:
    """
    Hill sprints — running economy booster with low injury risk.

    Barnes et al. (2013): 6 weeks of 10-14 × 10s hill sprints 2-3×/week
    improved running economy ~2-3% (ES = 0.4 for 5K TT improvement).
    Ferley et al. (2014): uphill sprinting produces less mechanical strain
    than flat sprinting due to lower impact forces.

    Prescribed in late base / early build phase only. 6 variation schemes.
    """
    ep = _fmt_pace(paces["easy"], imperial)
    reps, work_sec, rest_sec = _HILL_SPRINT_VAR[variation % 6]
    return [
        _walk_warmup(),
        {"type": "warmup",       "duration_min": 15, "pace": "easy",
         "note": f"Easy jog to a moderate hill (~6-10% grade) · {ep}"},
        {"type": "effort_set",   "reps": reps, "duration_sec_each": work_sec, "rest_sec": rest_sec,
         "intensity": "anaerobic",
         "note": (
             f"{reps}× {work_sec}s max-effort hill sprint · walk/jog back recovery. "
             f"Focus on high knees and powerful arm drive."
         )},
        {"type": "cooldown",     "duration_min": 10, "pace": "easy",
         "note": f"Easy jog back · {ep}"},
        _walk_cooldown(),
    ]


def _run_brick(duration_min: int, phase: str, paces: dict, race_distance_m: float,
               imperial: bool = False) -> list[dict]:
    """
    Brick run — straight off the bike, the second half of a linked pair.

    The adaptation is the transition itself: legs that have just ridden run
    with a shorter, stiffer stride and a racing heart for the first minutes
    (Millet & Vleck 2000; Bentley et al. 2002), and only rehearsing it teaches
    pacing through that. So the first 5 minutes are for finding cadence, not
    pace. In base the rest is easy — the brick is practice at transitioning;
    from build on it is at the race run's pace (threshold for sprint/Olympic,
    marathon pace for 70.3/full: Friel, *The Triathlete's Training Bible*),
    and in taper it stays short.

    ``duration_min`` is the week's quality-session length; a brick run is
    half of it, 10–30 min. ``race_distance_m`` is the race's *run leg*.
    """
    dur = max(10, min(30, duration_min // 2))
    if phase == "taper":
        dur = min(dur, 15)
    ep = _fmt_pace(paces["easy"], imperial)
    settle = 5
    main = max(5, dur - settle)
    if phase in ("build", "peak"):
        zone = "threshold" if race_distance_m <= 10000 else "marathon"
        note = f"Race-run pace · {_fmt_pace(paces[zone], imperial)} — hold it through heavy legs"
    else:
        zone = "easy"
        note = f"Settle into easy running · {ep}"
    return [
        {"type": "run", "duration_min": settle, "pace": "easy",
         "note": f"Off the bike: quick short steps, find your cadence · {ep}"},
        {"type": "run", "duration_min": main, "pace": zone, "note": note},
        _walk_cooldown(),
    ]
