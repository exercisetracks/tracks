# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race splits that follow the course instead of the kilometre markers.

With a GPX course a plan used to pace one split per kilometre, each at the
kilometre's mean grade. A 300 m wall in the middle of an otherwise flat
kilometre was averaged into it: the target asked for nearly flat pace up the
wall and gave the time back on the flat either side, which is the opposite of
running it well. Runners pace by terrain — ease up the climb, hold effort over
the top, let the descent come — so the plan's splits now are the terrain:
stretches of one kind of ground, each with its own target.

## How the course is cut

The course's ~100 m samples (course.parse_gpx) are lightly smoothed, then each
is put in a band: steep descent, descent, flat, climb, steep climb. Runs of
one band become segments. A segment too short to be worth a separate target
is folded away: shared between its neighbours when they are different kinds
of ground (the top of a climb that runs straight into a descent: each takes
its own half, see ``_share``), else into the one closest to it in grade. A
steep segment needs
only ``MIN_STEEP_M`` to stand on its own and a climb ``MIN_CLIMB_M``, against
``MIN_M`` for flats and descents, because a short hill is exactly the case
that matters: a 200 m bridge or rise is a change of effort a runner paces,
where a 200 m dip is not worth a target of its own. Long uniform
stretches are cut into pieces of at most ``MAX_M``, so a long flat still gets
checkpoints. Finally, while there are more segments than a Garmin workout can
carry (the race plan becomes one), the most alike neighbours are merged.

## How a segment is priced

A segment's target is the cost of the ground it covers, sample by sample —
the mean of its samples' Minetti multipliers, on their own grades (the
smoothing only decides where segments fall) — not the cost of its mean grade. Until 2026-11 it was the latter, and the two differ wherever a
segment holds more than one kind of ground. A 200 m climb folded into two
kilometres of flat raised that segment's target by under 1 % (its grade
averaged into the flat's); 200 m rises and dips read as dead flat, adding
nothing to the finish where the ground costs about 7 %, because the descent
does not give back what the climb took. Priced by its samples, a segment
carries every hill in it, and the finish — the sum of the samples — no
longer depends on where the course was cut: kilometres, miles or terrain
give one time. A flat segment priced well above its own grade is named
"Rolling", so a slower target on it says why.

Each segment becomes one distance step on the watch, named for its terrain,
so the watch moves between them by distance and announces each as it starts;
the phone's race guide moves between them by position along the course.

Mirrored by ``com.tracks.core.race.Terrain`` on the phone, held to this by
spec/fixtures/race_predictor.json.
"""

from __future__ import annotations

from .grade import grade_cost_multiplier

# Band edges (rise over run). ±2.5 % is where Minetti's cost leaves "flat" by
# more than a runner would notice; 6 % is where a climb stops being runnable at
# anything like the flat effort's pace.
_EDGES = ((-0.06, "steep_down"), (-0.025, "down"), (0.025, "flat"), (0.06, "up"))
_TOP = "steep_up"

MIN_M = 300.0
MIN_CLIMB_M = 200.0
MIN_STEEP_M = 150.0
MAX_M = 2000.0
# A Garmin workout carries 50 steps on the watches this targets; the race plan
# spends two on its open warm-up and cool-down.
MAX_SEGMENTS = 48

LABELS = {
    "steep_up": "Steep climb", "up": "Climb", "flat": "Flat",
    "down": "Descent", "steep_down": "Steep descent",
}
# A flat segment this much dearer than its own mean grade is rolling ground.
ROLLING_EXCESS = 0.02


def band(gradient: float) -> str:
    for edge, name in _EDGES:
        if gradient < edge:
            return name
    return _TOP


def _min_len(kind: str) -> float:
    if kind.startswith("steep"):
        return MIN_STEEP_M
    return MIN_CLIMB_M if kind == "up" else MIN_M


def _merge(a: dict, b: dict) -> dict:
    d = a["distance_m"] + b["distance_m"]
    g = (a["gradient"] * a["distance_m"] + b["gradient"] * b["distance_m"]) / d
    return {"distance_m": d, "gradient": g, "kind": band(g),
            "gain": a["gain"] + b["gain"], "cost": a["cost"] + b["cost"]}


def _piece(smp: list[tuple[float, float, float]], kind: str | None = None) -> dict:
    """A run of samples as a segment: its length, its smoothed mean grade (by
    which it is banded and compared), and its raw rise and cost. ``kind``
    keeps a band the mean alone would not give it (see ``_share``)."""
    d = sum(x[0] for x in smp)
    g = sum(x[0] * x[2] for x in smp) / d
    return {"samples": smp, "distance_m": d, "gradient": g, "kind": kind or band(g),
            "gain": sum(x[0] * x[1] for x in smp),
            "cost": sum(x[0] * grade_cost_multiplier(x[1]) for x in smp)}


def _share(smp: list[tuple[float, float, float]], left: dict, right: dict) -> int:
    """Where a short piece between two kinds of ground divides: its first
    samples go to the left, the rest to the right, cut where they sit
    closest to the side they join (least squares on their own grades).

    A crest is the case: smoothed, the top of a climb that runs straight into
    a descent reads as 200 m of flat, too short to stand. Folded whole into
    one side it diluted that side below its band — a 500 m climb merged with
    its own top read as flat — and a rolling road lost every climb's target.
    Shared, the climb keeps its rising half and the descent its falling one."""
    gl = left["gain"] / left["distance_m"]
    gr = right["gain"] / right["distance_m"]
    best_t, best = 0, None
    for t in range(len(smp) + 1):
        err = (sum(x[0] * (x[1] - gl) * (x[1] - gl) for x in smp[:t])
               + sum(x[0] * (x[1] - gr) * (x[1] - gr) for x in smp[t:]))
        if best is None or err < best:
            best_t, best = t, err
    return best_t


def samples(course_segments: list[dict] | None, distance_m: float) -> list[tuple[float, float, float]]:
    """The course's ~100 m samples as ``(distance, gradient, smoothed)``,
    scaled to ``distance_m`` (the plan's distance can be the event's rather
    than the GPX's). Empty without a course.

    ``smoothed`` (¼ ½ ¼) decides where segments fall: one noisy sample should
    not open a segment, but a real 100 m wall survives it at three-quarters
    of its grade or more. The price is the raw gradient's: smoothing halves
    a 200 m rise, and every hill a running plan prices — its segments, their
    targets and the finish — is worked from the same samples. So a course
    whose elevation is GPS noise, not a map's or a barometer's, prices as
    rolling, a few per cent over a clean file of the same road."""
    raw = [(float(s["distance_m"]), float(s["gradient"])) for s in course_segments or []
           if float(s["distance_m"]) > 0]
    total = sum(d for d, _ in raw)
    if not raw or total <= 0 or distance_m <= 0:
        return []
    scale = distance_m / total
    n = len(raw)
    out = []
    for i, (d, g) in enumerate(raw):
        lo = raw[i - 1][1] if i > 0 else g
        hi = raw[i + 1][1] if i < n - 1 else g
        out.append((d * scale, g, 0.25 * lo + 0.5 * g + 0.25 * hi))
    return out


def ground(smp: list[tuple[float, float, float]], lengths: list[float]) -> list[tuple[float, float]]:
    """The rise and the cost (metres × multiplier) of each of ``lengths``,
    laid end to end along the samples, so each metre counts in exactly one;
    ground past the samples' end is flat. What a lap or a checkpoint is
    priced by."""
    out: list[tuple[float, float]] = []
    cursor   = 0
    consumed = 0.0
    for length in lengths:
        gain = cost = 0.0
        remaining = length
        while remaining > 0 and cursor < len(smp):
            d, g, _ = smp[cursor]
            take  = min(remaining, d - consumed)
            gain      += g * take
            cost      += grade_cost_multiplier(g) * take
            remaining -= take
            consumed  += take
            if consumed >= d - 0.001:
                cursor  += 1
                consumed = 0.0
        if remaining > 0:
            cost += remaining
        out.append((gain, cost))
    return out


def course_grade_factor(course_segments: list[dict] | None, distance_m: float) -> float:
    """What the course's ground does to a running finish: its samples' mean
    Minetti multiplier, 1.0 without a course. The finish compute_lap_paces
    works its splits to, whichever way the course is cut."""
    smp = samples(course_segments, distance_m)
    if not smp:
        return 1.0
    return sum(d * grade_cost_multiplier(g) for d, g, _ in smp) / sum(d for d, _, _ in smp)


def terrain_segments(course_segments: list[dict], distance_m: float,
                     max_segments: int = MAX_SEGMENTS) -> list[dict]:
    """The course as terrain segments: ``[{distance_m, gradient, kind,
    multiplier}]`` in course order, summing to ``distance_m``. ``gradient``
    is the segment's mean and ``multiplier`` its price, the mean of its
    samples' (see the header)."""
    smp = samples(course_segments, distance_m)
    if not smp:
        return [{"distance_m": float(distance_m), "gradient": 0.0, "kind": "flat", "multiplier": 1.0}]

    # Runs of one band, each a segment. Every segment is banded and compared
    # by its smoothed grade and carries its rise and its cost (metres ×
    # multiplier) from the raw samples, which every fold, cut and merge below
    # keeps whole: the course's total cost is the samples' total.
    runs: list[list[tuple[float, float, float]]] = []
    kinds: list[str] = []
    for x in smp:
        k = band(x[2])
        if runs and kinds[-1] == k:
            runs[-1].append(x)
        else:
            runs.append([x])
            kinds.append(k)
    segs = [_piece(r, k) for r, k in zip(runs, kinds)]

    # Fold away what is too short to pace separately, shortest first: shared
    # between two different neighbours, else into the one nearest in grade.
    while len(segs) > 1:
        short = [i for i, s in enumerate(segs) if s["distance_m"] < _min_len(s["kind"])]
        if not short:
            break
        i = min(short, key=lambda j: segs[j]["distance_m"])
        if 0 < i < len(segs) - 1 and segs[i - 1]["kind"] != segs[i + 1]["kind"]:
            left, mid, right = segs[i - 1], segs[i]["samples"], segs[i + 1]
            t = _share(mid, left, right)
            segs[i - 1:i + 2] = [_piece(left["samples"] + mid[:t], left["kind"]),
                                 _piece(mid[t:] + right["samples"], right["kind"])]
        else:
            j = _nearest(segs, i)
            lo, hi = min(i, j), max(i, j)
            segs[lo:hi + 1] = [_piece(segs[lo]["samples"] + segs[hi]["samples"])]
        segs = _coalesce(segs)

    # Long uniform stretches get checkpoints, each priced by its own ground:
    # a pitch folded into a long flat raises the checkpoint it is in.
    cut: list[dict] = []
    for s in segs:
        pieces = max(1, int(-(-s["distance_m"] // MAX_M)))
        if pieces == 1:
            cut.append({k: v for k, v in s.items() if k != "samples"})
            continue
        length = s["distance_m"] / pieces
        for gain, cost in ground(s["samples"], [length] * pieces):
            cut.append({"distance_m": length, "gradient": s["gradient"], "kind": s["kind"],
                        "gain": gain, "cost": cost})
    segs = cut

    # Within the watch's step budget: merge the most alike neighbours, the
    # pieces of one cut stretch first (they share its grade).
    while len(segs) > max_segments:
        i = min(range(len(segs) - 1),
                key=lambda k: (abs(segs[k]["gradient"] - segs[k + 1]["gradient"]),
                               segs[k]["distance_m"] + segs[k + 1]["distance_m"]))
        segs[i:i + 2] = [_merge(segs[i], segs[i + 1])]

    return [{"distance_m": s["distance_m"], "gradient": s["gain"] / s["distance_m"], "kind": s["kind"],
             "multiplier": s["cost"] / s["distance_m"]} for s in segs]


def _nearest(segs: list[dict], i: int) -> int:
    if i == 0:
        return 1
    if i == len(segs) - 1:
        return i - 1
    g = segs[i]["gradient"]
    return i - 1 if abs(segs[i - 1]["gradient"] - g) <= abs(segs[i + 1]["gradient"] - g) else i + 1


def _coalesce(segs: list[dict]) -> list[dict]:
    """Join neighbours a fold has left in the same band."""
    out: list[dict] = []
    for s in segs:
        if out and out[-1]["kind"] == s["kind"]:
            out[-1] = _piece(out[-1]["samples"] + s["samples"], s["kind"])
        else:
            out.append(s)
    return out


def label(kind: str, gradient: float, multiplier: float | None = None) -> str:
    """The segment's name on the watch and in the table: "Climb 4%", or
    "Rolling" for a flat segment priced ``ROLLING_EXCESS`` over its grade."""
    pct = round(abs(gradient) * 100)
    if kind == "flat":
        if multiplier is not None and multiplier >= grade_cost_multiplier(gradient) * (1 + ROLLING_EXCESS):
            return "Rolling"
        return LABELS[kind]
    return LABELS[kind] if pct == 0 else f"{LABELS[kind]} {pct}%"
