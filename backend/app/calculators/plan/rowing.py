# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Rowing workout builders — erg-first, in the training bands rowers use.

  band  rate (spm)  split vs 2k pace   what it trains
  UT2   18-20       +18-22 s/500 m     aerobic base; the bulk of the volume
  UT1   20-22       +12-16 s/500 m     upper aerobic
  AT    22-24       +6-10 s/500 m      anaerobic threshold
  TR    26-32       +0-4 s/500 m       VO2 / transport, the 2k-pace pieces
  AN    32-36+      faster than 2k     anaerobic, sprints and starts

Stroke rate is the target the watch carries (a cadence target, strokes per
minute) on every piece: it is how rowers prescribe and self-limit intensity
on the water and the erg alike, it needs no test to set, and a rate cap is
what keeps UT2 from creeping into UT1. UT2 and UT1 steps carry a heart-rate
target instead when a threshold HR is set, since the aerobic bands are
defined physiologically and the rate alone lets a strong rower go too hard.
Splits appear in the notes relative to 2k pace, which Tracks does not know.

References
----------
- Steinacker, J. M. (1993). Physiological aspects of training in rowing.
  *Int J Sports Med*, 14(S1), S3-S10. Band structure; volume mostly low.
- Fiskerstrand, Å., & Seiler, K. S. (2004). Training and performance
  characteristics among Norwegian international rowers 1970-2001. *Scand J
  Med Sci Sports*, 14(5), 303-310. Volume up, high-intensity share down,
  performance up — the polarised shift.
- Ingham, S. A., et al. (2002). Determinants of 2000 m rowing ergometer
  performance in elite rowers. *Eur J Appl Physiol*, 88(3), 243-246.
  VO2max and power at lactate threshold explain most of 2k performance.
- British Rowing / Concept2 training guides: UT2/UT1/AT/TR/AN rate bands.
"""

from __future__ import annotations

_DEFAULT_2K_SEC_PER_KM = 240   # a 2:00/500 m split; sizes distance pieces only


def _rate(lo: int, hi: int) -> str:
    return f"rate {lo}-{hi}"


def _piece(reps: int, minutes: int, rest_min: int, intensity: str, label: str,
           spm: tuple[int, int], note: str) -> dict:
    return {"type": "effort_set", "reps": reps, "duration_min_each": minutes, "rest_min": rest_min,
            "intensity": intensity, "label": label, "spm_low": spm[0], "spm_high": spm[1],
            "note": note}


def _warm(minutes: int = 10) -> dict:
    return {"type": "warmup", "duration_min": minutes, "intensity": "easy",
            "note": "Easy rowing, rate 18 · 3× 10 strokes building pressure"}


def _cool(minutes: int = 8) -> dict:
    return {"type": "cooldown", "duration_min": minutes, "intensity": "easy",
            "note": "Easy paddle, rate 18"}


def _row_ut2(duration_min: int, variation: int = 0) -> list[dict]:
    """
    UT2 steady state — long, low, conversational. Rotated between one
    continuous piece and two or three shorter ones with a short paddle
    between, which breaks a long erg session without changing its dose.
    """
    total = max(30, duration_min)
    shape = variation % 3
    if shape == 0:
        return [{"type": "activity", "duration_min": total, "intensity": "ut2",
                 "spm_low": 18, "spm_high": 20,
                 "note": f"{total} min UT2 · {_rate(18, 20)} · split ~20 s/500 m slower than 2k pace"
                         " · long, relaxed strokes; you can talk"}]
    reps = shape + 1
    each = max(10, (total - 2 * shape) // reps)
    return [_piece(reps, each, 2, "ut2", "UT2", (18, 20),
                   f"{reps}× {each} min UT2 · {_rate(18, 20)} · 2 min easy paddle between"
                   " · you can talk")]


def _row_ut1(duration_min: int, variation: int = 0) -> list[dict]:
    """UT1 — upper aerobic, three pieces with an easy row between."""
    each = max(8, (duration_min - 24) // 3)
    return [
        _warm(),
        _piece(3, each, 3, "ut1", "UT1", (20, 22),
               f"3× {each} min UT1 · {_rate(20, 22)} · split ~14 s/500 m slower than 2k pace"
               " · 3 min easy between"),
        _cool(),
    ]


_AT_LADDER: tuple[tuple[int, int], ...] = ((3, 8), (4, 8), (3, 10), (4, 10), (3, 12), (2, 20))


def _row_threshold(build_idx: int, variation: int = 0) -> list[dict]:
    """
    AT pieces — power at the lactate threshold is the second of the two
    things that decide a 2k (Ingham 2002). The ladder adds time at AT across
    the block: 24 min → 40 min.
    """
    reps, each = _AT_LADDER[min(len(_AT_LADDER) - 1, build_idx + variation % 2)]
    return [
        _warm(),
        _piece(reps, each, 3, "threshold", "AT", (22, 24),
               f"{reps}× {each} min AT · {_rate(22, 24)} · split ~8 s/500 m slower than 2k pace"
               " · hard but controlled · 3 min easy between"),
        _cool(),
    ]


_TR_LADDER: tuple[tuple[int, int, int], ...] = (
    (6, 500, 120), (8, 500, 120), (5, 750, 150), (4, 1000, 180), (3, 1000, 150),
)


def _row_race_pace(build_idx: int) -> list[dict]:
    """
    2k-pace pieces (TR) — the VO2 work of rowing, done at the pace and rate of
    the race so both are rehearsed together. From 6×500 m to 4×1000 m.
    """
    reps, dist, rest = _TR_LADDER[min(len(_TR_LADDER) - 1, build_idx // 2)]
    pace = _DEFAULT_2K_SEC_PER_KM
    return [
        _warm(12),
        {"type": "interval_set", "reps": reps, "distance_m": dist, "rest_sec": rest,
         "intensity": "race_pace", "sec_per_km": pace, "label": f"2k pace {dist}",
         "spm_low": 28, "spm_high": 32,
         "note": f"{reps}× {dist} m at 2k race pace · {_rate(28, 32)} · {rest // 60} min"
                 f"{'' if rest % 60 == 0 else ' 30 s'} easy between"},
        _cool(),
    ]


def _row_sprint(variation: int = 0) -> list[dict]:
    """AN — short, very hard, long rest: starts and the final sprint."""
    shapes = ((10, 60, 120), (8, 45, 135), (12, 30, 90))
    reps, on, off = shapes[variation % 3]
    return [
        _warm(12),
        {"type": "effort_set", "reps": reps, "duration_sec_each": on, "rest_sec": off,
         "intensity": "sprint", "label": "AN", "spm_low": 32, "spm_high": 38,
         "note": f"{reps}× {on} s AN · rate 32+ · faster than 2k pace · {off} s easy between"},
        _cool(),
    ]


_TECHNIQUE_DRILLS: tuple[str, ...] = (
    "legs-only, then legs-and-body, then full stroke — sequence the drive",
    "pause at the finish, hands away — clean release and body swing",
    "pause at half-slide — control the recovery, don't rush the catch",
    "feet out of the straps — hang off the handle, no yanking at the finish",
)


def _row_technique(duration_min: int, variation: int = 0) -> list[dict]:
    """
    Low-rate technique pieces. A rate cap of 18-20 forces length and
    connection; the drill rotates by week.
    """
    drill = _TECHNIQUE_DRILLS[variation % 4]
    each = 5
    reps = max(3, min(6, (duration_min - 18) // (each + 1)))
    return [
        _warm(),
        _piece(reps, each, 1, "easy", "Drill", (16, 20),
               f"{reps}× {each} min · {drill} · rate 16-20 · 1 min easy between"),
        _cool(),
    ]


def _row_long(duration_min: int) -> list[dict]:
    """
    Long UT2. Past an hour it is split in two with a short break, to stand
    up and move — sustained flexion on the erg loads the lumbar spine, the
    commonest rowing injury site.
    """
    if duration_min <= 60:
        return [{"type": "activity", "duration_min": duration_min, "intensity": "ut2",
                 "spm_low": 18, "spm_high": 20,
                 "note": f"{duration_min} min UT2 · {_rate(18, 20)} · steady, you can talk"}]
    each = duration_min // 2
    return [_piece(2, each, 3, "ut2", "Long UT2", (18, 20),
                   f"2× {each} min UT2 · {_rate(18, 20)} · 3 min off the erg between: stand, walk,"
                   " stretch the hips")]


def _row_short_quality() -> list[dict]:
    """Taper sharpener: little volume at race rate and pace (Bosquet 2007)."""
    return [
        _warm(12),
        {"type": "effort_set", "reps": 5, "duration_sec_each": 20, "rest_sec": 100,
         "intensity": "sprint", "label": "Start", "spm_low": 36, "spm_high": 42,
         "note": "5× race start (20 s: 5 short, 10 long, then settle) · 100 s easy between"},
        {"type": "interval_set", "reps": 3, "distance_m": 500, "rest_sec": 180,
         "intensity": "race_pace", "sec_per_km": _DEFAULT_2K_SEC_PER_KM, "label": "2k pace 500",
         "spm_low": 30, "spm_high": 34,
         "note": "3× 500 m at 2k race pace · rate 30-34 · 3 min easy between"},
        _cool(),
    ]
