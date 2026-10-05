# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Checking a heart-rate recording sample by sample against everything else the
activity recorded, before its load is computed from it.

hrTSS trusts the average heart rate completely. A wrist sensor that locks on
to the runner's cadence, a chest strap that reads 90 bpm for the ten minutes
before it is wet, a strap that slips mid-climb — each moves that average, and
the load with it, and none of them is visible in the average itself. They are
visible against the rest of the file: a heart rate that tracks the step rate
beat for beat, or that sits at walking level while the runner climbs at
3 m/s, is not a heart rate.

So each sample is checked, and only the samples that fail are replaced. A
recording that is partly wrong keeps everything it measured correctly.

The checks, in order
────────────────────
1. **Present and possible.** Missing, or outside 30–230 bpm: a dropout.

2. **Artifacts the parser's smoothing leaves.** The parser already removes
   one-sample spikes and takes a 7-sample rolling median
   (parsers/smoother.py), so what reaches here is longer: a run of a few to a
   dozen samples off the trend. A Hampel filter — the rolling median ± three
   scaled median absolute deviations (Hampel 1974; the standard robust
   outlier test for physiological series) — flags those, with a 10 bpm floor
   so a steady recording's near-zero spread cannot make ordinary noise an
   outlier. The spread is taken from the samples within 40 bpm of the median
   (a one-step reweighting), so a cluster of artifacts cannot widen the scale
   it is judged by — which on a rising heart rate let the tail of one
   through, and left its value as the session's maximum.

3. **Cadence lock.** An optical sensor can lock on to the arm swing and
   report the step (or pedal) rate as the heart rate — the best-documented
   failure of wrist heart rate in running. A heart rate *equal* to cadence
   proves nothing, because a runner's heart rate and step rate genuinely sit
   in the same range. A heart rate that *follows* cadence as it changes does:
   a real heart does not track stride rate beat for beat. So a minute where
   nearly every sample equals the step or pedal rate, *and* that rate moved
   by several beats in the minute, is a lock. A steady lock that never moves
   cannot be told from a coincidence here, and is left to check 4.

4. **Disagreement with the effort.** Heart rate rises linearly with oxygen
   uptake over the submaximal range (Åstrand & Rodahl; the basis of every
   heart-rate zone), and oxygen uptake follows metabolic power. Metabolic
   power per kilogram is known from speed and gradient: on foot, Minetti et
   al.'s energy cost of walking and running on slopes (J Appl Physiol 2002,
   93:1039–46) times speed; on a bike, the road-cycling power model Martin et
   al. validated (J Appl Biomech 1998, 14:276–91) with typical rolling and
   drag constants. Heart rate lags a change in effort by roughly half a
   minute to a minute, so the effort is passed through a first-order lag
   before it is compared — starting from rest, and falling back towards
   rest across a pause, as a heart does. Nothing in the first two minutes
   is judged by this check: the lag and the gradient have no history yet.

   The athlete's own relation is then fitted *within this activity* —
   heart rate = a + b × lagged power, least squares over the samples that
   passed checks 1–3 — which is what makes the check personal: nothing here
   assumes a population's heart rate. A sample more than 25 bpm off that
   line, in a stretch lasting at least 30 seconds, is flagged. That margin is
   wider than cardiac drift over a long session and than the scatter
   intervals leave after the lag, so the check catches sensors, not
   physiology. The fit is used only when it can be trusted: enough samples,
   an effort that actually varied (a coefficient of variation of at least
   10%, or a flat line through a cloud says nothing), and a slope that is
   positive.

Replacing what failed
─────────────────────
A flagged sample is replaced by what the athlete's own fit predicts for the
effort at that moment, held within the range of heart rates the recording
got right; with no usable fit (an indoor session, a steady effort), by the
mean of the samples that passed. The recording's average and maximum are
then recomputed over the whole activity.

When nothing failed, ``review_heart_rate`` returns None and the load is
computed exactly as it always was — this only ever changes a number the
recording itself contradicts. When too little of the recording survives to
say anything (no sample at all, or under a quarter with no usable fit), the
review says so and the activity's load falls back to the estimate an
activity with no heart rate gets.

Only the load reads this. The activity's stored average and maximum heart
rate stay what the device measured: this is a judgement about which samples
to believe, not a correction to the record.

Every step is plain IEEE arithmetic in a fixed order, so the phone's port
(com.tracks.core.metrics.HrReview) computes the same doubles; held to it by
spec/fixtures/hr_review.json.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.spec.taxonomy import sport_type

MIN_HR = 30.0
MAX_HR = 230.0

HAMPEL_HALF = 15                 # samples either side
HAMPEL_K = 3.0
MAD_SCALE = 1.4826               # MAD → standard deviation for normal noise
HAMPEL_FLOOR = 10.0              # bpm
HAMPEL_INLIER = 40.0             # bpm: more than a heart moves within the window

LOCK_WINDOW = 60                 # samples
LOCK_SHARE = 0.9
LOCK_TOL = 2.0                   # bpm
LOCK_MIN_RANGE = 6.0             # bpm the locked rate must move in the window

GRADE_BASE_M = 30.0              # distance a gradient is measured over
GRADE_LIMIT = 0.45               # Minetti's measured range
LAG_S = 40.0                     # heart-rate response time constant
RESIDUAL_BPM = 25.0
RESIDUAL_MIN_S = 30.0
MIN_FIT_SAMPLES = 120
MIN_EFFORT_CV = 0.10
MIN_VALID_SHARE = 0.25
FIT_ROUNDS = 3
WARMUP_S = 300.0                 # heart rate still rising to a steady effort
SETTLE_S = 120.0                 # before the lag and the gradient window have data
PAUSE_S = 10.0                   # a gap longer than this is a stop, not a slow sample

# Minetti et al. 2002: energy cost (J kg⁻¹ m⁻¹) at gradient i, highest power first.
_RUN_COST = (155.4, -30.4, -43.3, 46.3, 19.5, 3.6)
_WALK_COST = (280.5, -58.7, -76.8, 51.9, 19.6, 2.5)

# Road-cycling power per kilogram of rider and bike (Martin et al. 1998):
# g·v·(Crr + gradient) + ½ρ·CdA/m·v³. ρ 1.2 kg/m³, CdA 0.35 m², 85 kg
# rider and bike; rolling resistance 0.005 on the road, 0.012 off it.
_G = 9.81
_AERO = 0.0025
_CRR = {"cycling": 0.005, "mtb": 0.012}


@dataclass(frozen=True)
class HrReview:
    """What a recording's heart rate says once its failed samples are replaced."""
    usable: bool
    avg_hr: float | None = None
    max_hr: float | None = None
    replaced: int = 0
    samples: int = 0


def _median(values: list[float]) -> float:
    s = sorted(values)
    n = len(s)
    mid = n // 2
    if n % 2:
        return s[mid]
    return (s[mid - 1] + s[mid]) / 2


def _horner(coeffs: tuple[float, ...], x: float) -> float:
    acc = 0.0
    for c in coeffs:
        acc = acc * x + c
    return acc


def _power(kind: str, speed: float, grade: float) -> float | None:
    """Metabolic (on foot) or mechanical (bike) power per kg; None for sports without a model."""
    if kind == "running":
        return _horner(_RUN_COST, grade) * speed
    if kind == "hiking":
        return _horner(_WALK_COST, grade) * speed
    crr = _CRR.get(kind)
    if crr is not None:
        p = speed * _G * (crr + grade) + _AERO * speed * speed * speed
        return p if p > 0 else 0.0
    return None


def _fit(xs: list[float], ys: list[float]) -> tuple[float, float] | None:
    """Least-squares (a, b) of y = a + b·x, or None when it cannot be trusted."""
    n = len(xs)
    if n < MIN_FIT_SAMPLES:
        return None
    sx = 0.0
    sy = 0.0
    for i in range(n):
        sx += xs[i]
        sy += ys[i]
    mx = sx / n
    my = sy / n
    sxx = 0.0
    sxy = 0.0
    for i in range(n):
        dx = xs[i] - mx
        sxx += dx * dx
        sxy += dx * (ys[i] - my)
    if mx <= 0 or sxx <= 0:
        return None
    # Coefficient of variation of the effort, squared on both sides.
    if sxx / n < (MIN_EFFORT_CV * mx) * (MIN_EFFORT_CV * mx):
        return None
    b = sxy / sxx
    if b <= 0:
        return None
    return my - b * mx, b


def _steady(times: list[float], effort: list[float | None], valid: list[bool]) -> bool:
    """Enough effort samples after the warm-up, and an effort too even for
    ``_fit`` to use. The warm-up is left out because the lag's rise from rest
    is not a change in what the athlete was doing."""
    xs = [effort[i] for i in range(len(effort))
          if valid[i] and effort[i] is not None and times[i] - times[0] >= WARMUP_S]
    n = len(xs)
    if n < MIN_FIT_SAMPLES:
        return False
    sx = 0.0
    for x in xs:
        sx += x
    mx = sx / n
    if mx <= 0:
        return False
    sxx = 0.0
    for x in xs:
        dx = x - mx
        sxx += dx * dx
    return sxx / n < (MIN_EFFORT_CV * mx) * (MIN_EFFORT_CV * mx)


def review_heart_rate(sport: str | None, times: list[float], hrs: list[float | None],
                      speeds: list[float | None], altitudes: list[float | None],
                      cadences: list[float | None]) -> HrReview | None:
    """Check each heart-rate sample; None when every one passed.

    Parallel lists, one entry per recorded sample in time order; ``times`` in
    seconds from any fixed origin.
    """
    n = len(times)
    if n == 0:
        return None
    present = [h is not None and MIN_HR <= h <= MAX_HR for h in hrs]
    if not any(h is not None for h in hrs):
        return None                      # no series to check: the device's average stands
    valid = list(present)

    # ── 2. Hampel filter over the samples that are present ──────────────────
    idx = [i for i in range(n) if present[i]]
    m = len(idx)
    for k in range(m):
        lo = max(0, k - HAMPEL_HALF)
        hi = min(m, k + HAMPEL_HALF + 1)
        window = [hrs[idx[j]] for j in range(lo, hi)]
        med = _median(window)
        # The spread from the samples near the median only: a cluster of
        # artifacts would otherwise widen the very scale it is judged
        # against, and escape on a rising heart rate.
        near = [abs(v - med) for v in window if abs(v - med) <= HAMPEL_INLIER]
        mad = _median(near) if near else _median([abs(v - med) for v in window])
        limit = HAMPEL_K * MAD_SCALE * mad
        if limit < HAMPEL_FLOOR:
            limit = HAMPEL_FLOOR
        if abs(hrs[idx[k]] - med) > limit:
            valid[idx[k]] = False

    # ── 3. Cadence lock ─────────────────────────────────────────────────────
    locked = [False] * n
    for i in range(n):
        c = cadences[i]
        if present[i] and c is not None and c > 0:
            h = hrs[i]
            locked[i] = abs(h - c) <= LOCK_TOL or abs(h - 2 * c) <= LOCK_TOL
    if n >= LOCK_WINDOW:
        prefix = [0] * (n + 1)
        for i in range(n):
            prefix[i + 1] = prefix[i] + (1 if locked[i] else 0)
        need = LOCK_SHARE * LOCK_WINDOW
        for s in range(n - LOCK_WINDOW + 1):
            if prefix[s + LOCK_WINDOW] - prefix[s] < need:
                continue
            lo_h = None
            hi_h = None
            for j in range(s, s + LOCK_WINDOW):
                if locked[j]:
                    h = hrs[j]
                    if lo_h is None or h < lo_h:
                        lo_h = h
                    if hi_h is None or h > hi_h:
                        hi_h = h
            if hi_h - lo_h >= LOCK_MIN_RANGE:
                for j in range(s, s + LOCK_WINDOW):
                    if locked[j]:
                        valid[j] = False

    # ── 4. The athlete's own heart rate against the effort ─────────────────
    kind = sport_type(sport)
    effort: list[float | None] = [None] * n
    graded = [False] * n
    if kind in ("running", "hiking", "cycling", "mtb"):
        dist = [0.0] * n
        for i in range(1, n):
            dt = times[i] - times[i - 1]
            if dt < 0:
                dt = 0.0
            v0 = speeds[i - 1]
            v1 = speeds[i]
            step = ((v0 + v1) / 2) * dt if v0 is not None and v1 is not None else 0.0
            dist[i] = dist[i - 1] + step
        j = 0
        # From rest: a recording starts before the heart has risen to meet
        # the effort, and the lag models exactly that rise.
        lagged = 0.0
        last_t = times[0]
        for i in range(n):
            v = speeds[i]
            if v is None:
                continue
            while j + 1 < i and dist[i] - dist[j + 1] >= GRADE_BASE_M:
                j += 1
            grade = 0.0
            if (j < i and dist[i] - dist[j] >= GRADE_BASE_M
                    and altitudes[i] is not None and altitudes[j] is not None):
                grade = (altitudes[i] - altitudes[j]) / (dist[i] - dist[j])
                if grade > GRADE_LIMIT:
                    grade = GRADE_LIMIT
                elif grade < -GRADE_LIMIT:
                    grade = -GRADE_LIMIT
            p = _power(kind, v, grade)
            dt = times[i] - last_t
            last_t = times[i]
            if dt < 0:
                dt = 0.0
            if dt > PAUSE_S:
                # Stopped: the heart recovers towards rest over the gap, and
                # the sample after it is one ordinary step on from there.
                lagged = lagged + (dt / (LAG_S + dt)) * (0.0 - lagged)
                dt = 1.0
            lagged = lagged + (dt / (LAG_S + dt)) * (p - lagged)
            effort[i] = lagged
            # Without an altitude the gradient is unknown, and on a bike a 5%
            # climb is four times the power of the flat: a fit that assumed
            # flat would call every hill a sensor fault. Such samples still
            # say whether the effort was steady (a treadmill, a turbo), but
            # only graded ones are fitted.
            graded[i] = altitudes[i] is not None

    def fit_valid():
        xs = []
        ys = []
        for i in range(n):
            if valid[i] and graded[i]:
                xs.append(effort[i])
                ys.append(hrs[i])
        return _fit(xs, ys)

    def flag_runs(off: list[bool]) -> bool:
        """Invalidate every stretch of ``off`` samples lasting RESIDUAL_MIN_S."""
        flagged = False
        i = 0
        while i < n:
            if not off[i]:
                i += 1
                continue
            start = i
            while i + 1 < n and off[i + 1]:
                i += 1
            if times[i] - times[start] >= RESIDUAL_MIN_S:
                for k in range(start, i + 1):
                    valid[k] = False
                flagged = True
            i += 1
        return flagged

    # Refit after each round: samples a sensor got wrong pull the first fit
    # towards themselves, and some only stand out once they no longer do.
    model = fit_valid()
    rounds = 0
    while model is not None and rounds < FIT_ROUNDS:
        a, b = model
        off = [valid[i] and graded[i] and times[i] - times[0] >= SETTLE_S
               and abs(hrs[i] - (a + b * effort[i])) > RESIDUAL_BPM for i in range(n)]
        if not flag_runs(off):
            break
        model = fit_valid()
        rounds += 1

    if model is None and _steady(times, effort, valid):
        # A steady effort gives no slope to fit, but it does say the heart
        # rate should be steady too: a stretch far off the recording's own
        # median is a sensor. Not in the first minutes, while heart rate is
        # still rising to meet the effort.
        typical = _median([hrs[i] for i in range(n) if valid[i] and times[i] - times[0] >= WARMUP_S])
        flag_runs([valid[i] and times[i] - times[0] >= WARMUP_S
                   and abs(hrs[i] - typical) > RESIDUAL_BPM for i in range(n)])

    # ── Replace what failed ────────────────────────────────────────────────
    good = [hrs[i] for i in range(n) if valid[i]]
    replaced = n - len(good)
    if replaced == 0:
        return None
    if not good or (model is None and len(good) < MIN_VALID_SHARE * n):
        return HrReview(usable=False, replaced=replaced, samples=n)
    total = 0.0
    for h in good:
        total += h
    mean_good = total / len(good)
    lo_good = min(good)
    hi_good = max(good)
    acc = 0.0
    for i in range(n):
        if valid[i]:
            acc += hrs[i]
        elif model is not None and graded[i]:
            pred = model[0] + model[1] * effort[i]
            if pred < lo_good:
                pred = lo_good
            elif pred > hi_good:
                pred = hi_good
            acc += pred
        else:
            acc += mean_good
    return HrReview(usable=True, avg_hr=acc / n, max_hr=hi_good, replaced=replaced, samples=n)


def review_points(sport: str | None, points: list[dict]) -> HrReview | None:
    """``review_heart_rate`` over the parser's data points."""
    pts = [p for p in points if p.get("recorded_at") is not None]
    if not pts:
        return None
    t0 = pts[0]["recorded_at"]

    def num(v):
        return None if v is None else float(v)

    return review_heart_rate(
        sport,
        [(p["recorded_at"] - t0).total_seconds() for p in pts],
        [num(p.get("heart_rate")) for p in pts],
        [num(p.get("speed")) for p in pts],
        [num(p.get("altitude")) for p in pts],
        [num(p.get("cadence")) for p in pts],
    )
