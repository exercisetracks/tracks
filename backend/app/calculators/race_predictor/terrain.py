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
is folded into the neighbour closest to it in grade — but a steep one needs
only ``MIN_STEEP_M`` to stand on its own, against ``MIN_M`` for the rest,
because a short steep hill is exactly the case that matters. Long uniform
stretches are cut into pieces of at most ``MAX_M``, so a long flat still gets
checkpoints. Finally, while there are more segments than a Garmin workout can
carry (the race plan becomes one), the most alike neighbours are merged.

A segment's target is its own grade's Minetti cost, as a lap's was; nothing
about the effort model changes, only where the boundaries fall. Each segment
becomes one distance step on the watch, named for its terrain, so the watch
moves between them by distance and announces each as it starts; the phone's
race guide moves between them by position along the course.

Mirrored by ``com.tracks.core.race.Terrain`` on the phone, held to this by
spec/fixtures/race_predictor.json.
"""

from __future__ import annotations

# Band edges (rise over run). ±2.5 % is where Minetti's cost leaves "flat" by
# more than a runner would notice; 6 % is where a climb stops being runnable at
# anything like the flat effort's pace.
_EDGES = ((-0.06, "steep_down"), (-0.025, "down"), (0.025, "flat"), (0.06, "up"))
_TOP = "steep_up"

MIN_M = 300.0
MIN_STEEP_M = 150.0
MAX_M = 2000.0
# A Garmin workout carries 50 steps on the watches this targets; the race plan
# spends two on its open warm-up and cool-down.
MAX_SEGMENTS = 48

LABELS = {
    "steep_up": "Steep climb", "up": "Climb", "flat": "Flat",
    "down": "Descent", "steep_down": "Steep descent",
}


def band(gradient: float) -> str:
    for edge, name in _EDGES:
        if gradient < edge:
            return name
    return _TOP


def _min_len(kind: str) -> float:
    return MIN_STEEP_M if kind.startswith("steep") else MIN_M


def _merge(a: dict, b: dict) -> dict:
    d = a["distance_m"] + b["distance_m"]
    g = (a["gradient"] * a["distance_m"] + b["gradient"] * b["distance_m"]) / d
    return {"distance_m": d, "gradient": g, "kind": band(g)}


def terrain_segments(course_segments: list[dict], distance_m: float,
                     max_segments: int = MAX_SEGMENTS) -> list[dict]:
    """The course as terrain segments: ``[{distance_m, gradient, kind}]`` in
    course order, summing to ``distance_m`` (the course is scaled to it when
    the plan's distance is the event's rather than the GPX's)."""
    raw = [(float(s["distance_m"]), float(s["gradient"])) for s in course_segments
           if float(s["distance_m"]) > 0]
    total = sum(d for d, _ in raw)
    if not raw or total <= 0 or distance_m <= 0:
        return [{"distance_m": float(distance_m), "gradient": 0.0, "kind": "flat"}]
    scale = distance_m / total

    # Light smoothing (¼ ½ ¼): one noisy sample should not open a segment,
    # but a real 100 m wall survives it at three-quarters of its grade or more.
    n = len(raw)
    smooth = []
    for i, (d, g) in enumerate(raw):
        lo = raw[i - 1][1] if i > 0 else g
        hi = raw[i + 1][1] if i < n - 1 else g
        smooth.append((d * scale, 0.25 * lo + 0.5 * g + 0.25 * hi))

    segs: list[dict] = []
    for d, g in smooth:
        k = band(g)
        if segs and segs[-1]["kind"] == k:
            last = segs[-1]
            tot = last["distance_m"] + d
            last["gradient"] = (last["gradient"] * last["distance_m"] + g * d) / tot
            last["distance_m"] = tot
        else:
            segs.append({"distance_m": d, "gradient": g, "kind": k})

    # Fold away what is too short to pace separately, shortest first, into
    # the neighbour nearest in grade.
    while len(segs) > 1:
        short = [i for i, s in enumerate(segs) if s["distance_m"] < _min_len(s["kind"])]
        if not short:
            break
        i = min(short, key=lambda j: segs[j]["distance_m"])
        j = _nearest(segs, i)
        lo, hi = min(i, j), max(i, j)
        segs[lo:hi + 1] = [_merge(segs[lo], segs[hi])]
        segs = _coalesce(segs)

    # Long uniform stretches get checkpoints.
    cut: list[dict] = []
    for s in segs:
        pieces = max(1, int(-(-s["distance_m"] // MAX_M)))
        cut.extend({**s, "distance_m": s["distance_m"] / pieces} for _ in range(pieces))
    segs = cut

    # Within the watch's step budget: merge the most alike neighbours, the
    # pieces of one cut stretch first (they are identical).
    while len(segs) > max_segments:
        i = min(range(len(segs) - 1),
                key=lambda k: (abs(segs[k]["gradient"] - segs[k + 1]["gradient"]),
                               segs[k]["distance_m"] + segs[k + 1]["distance_m"]))
        segs[i:i + 2] = [_merge(segs[i], segs[i + 1])]

    return [{"distance_m": s["distance_m"], "gradient": s["gradient"], "kind": s["kind"]} for s in segs]


def _nearest(segs: list[dict], i: int) -> int:
    if i == 0:
        return 1
    if i == len(segs) - 1:
        return i - 1
    g = segs[i]["gradient"]
    return i - 1 if abs(segs[i - 1]["gradient"] - g) <= abs(segs[i + 1]["gradient"] - g) else i + 1


def _coalesce(segs: list[dict]) -> list[dict]:
    """Join neighbours a merge has left in the same band."""
    out: list[dict] = []
    for s in segs:
        if out and out[-1]["kind"] == s["kind"]:
            out[-1] = _merge(out[-1], s)
        else:
            out.append(dict(s))
    return out


def label(kind: str, gradient: float) -> str:
    """The segment's name on the watch and in the table: "Climb 4%"."""
    pct = round(abs(gradient) * 100)
    return LABELS[kind] if kind == "flat" or pct == 0 else f"{LABELS[kind]} {pct}%"
