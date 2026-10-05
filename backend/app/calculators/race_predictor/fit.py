# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
FIT race-workout generation (garmin-fit-sdk based).

Encodes a race plan's per-km lap paces into a Garmin ``.fit`` workout file so a
Fenix-class watch lists, schedules, and runs it exactly like a Garmin-Connect-
pushed workout. The file structure is:

  * one OPEN warm-up step,
  * one DISTANCE-based active step per lap (1 km / final partial km) carrying,
    optionally, a ±15 sec/km SPEED-range target (``pace_coaching``) or a
    heart-rate range target (``hr_coaching`` + ``max_hr``),
  * one OPEN cool-down step.

Encoding is delegated entirely to the SDK-based encoder in
``app.calculators.fit_workout`` — the same ``garmin_fit_sdk.Encoder`` +
``_build_step`` / ``_write_steps`` / ``_write_*`` helpers that
``generate_workout_fit`` uses. No hand-rolled FIT byte writers live here (the
old raw ``_data_record`` / ``_crc16`` path was removed when the encoders were
migrated to ``garmin-fit-sdk``).
"""
from __future__ import annotations

import math


def generate_race_fit(
    name: str,
    sport: str,
    lap_paces: list[dict],
    paces_dict: dict[str, float] | None = None,
    pace_coaching: bool = True,
    hr_coaching: bool = False,
    max_hr: int | None = None,
    fuel_items: list[dict] | None = None,
    time_created_ms: int | None = None,
) -> bytes:
    """
    Generate a FIT workout file for a race with per-lap targets.

    Each lap in ``lap_paces`` becomes one DISTANCE-based active step (1 km / the
    last partial km), bookended by an OPEN warm-up and an OPEN cool-down step.

    Targets (priority HR > speed, matching the SDK step builder):
      * ``pace_coaching=True``  → a ±15 sec/km SPEED range around the lap's
        ``target_sec_per_km``.
      * ``hr_coaching=True`` with ``max_hr`` → a heart-rate range ending at the
        lap's ``hr_ceiling`` (bpm) when present.

    Expected ``lap_paces`` dict keys (as emitted by ``compute_lap_paces`` /
    ``compute_cycling_lap_targets``): ``lap`` (int), ``distance_m`` (metres),
    ``target_sec_per_km`` (float), and an optional ``hr_ceiling`` (bpm | None).

    ``paces_dict`` is accepted for signature compatibility but unused — lap
    targets come directly from each lap's own ``target_sec_per_km``.

    ``fuel_items`` are the fuelling timeline's items (calculators/fuel_plan.py,
    each with a ``distance_m``): every lap step whose distance range holds one
    or more gets them appended to its name — "Km 5 · Gel" — which is what the
    watch shows as that step begins. A step name is the one alert a workout
    step reliably carries; step notes are not shown mid-run by the workout
    player. Verified by fixtures only, not yet on a watch.

    ``time_created_ms`` pins file_id.time_created; None means now, as before.
    The phone's encoder (com.tracks.core.fit.RaceFit) passes it so its bytes
    can be held to this function's.

    Returns raw FIT bytes ready to save and upload to Garmin.
    """
    # Reuse the SDK-based encoder + its step/file helpers. Importing here keeps
    # the race-predictor package free of an import-time dependency on the FIT
    # SDK (only pulled in when a FIT is actually requested).
    from app.calculators.fit_workout import (
        _ENDURANCE_SPORT_ROUTING,
        _build_step,
        _write_file_creator,
        _write_file_id,
        _write_steps,
        _write_workout_mesg,
        finish_encoder,
    )
    from garmin_fit_sdk import Encoder

    MARGIN = 15  # ±15 sec/km speed window

    # Sport → (sport, sub_sport) FIT enum names. Unknown sports fall back to
    # running, matching generate_workout_fit's convention.
    sport_v, sub_sport_v = _ENDURANCE_SPORT_ROUTING.get(
        (sport or "running").lower(), _ENDURANCE_SPORT_ROUTING["running"]
    )

    steps: list[dict] = []
    idx = 0

    # ── Open warm-up ─────────────────────────────────────────────────────────
    steps.append(_build_step(idx, name="Warm Up", intensity="warmup",
                             duration_type="open", duration_value=0))
    idx += 1

    # ── Per-lap distance steps ───────────────────────────────────────────────
    lap_start_m = 0.0
    for lap in lap_paces:
        dist_cm = int(round(lap["distance_m"] * 100))          # FIT distance = cm
        label   = f"Km {lap.get('lap', idx)}"
        lap_end_m = lap_start_m + float(lap["distance_m"])
        fuel = [
            f.get("name") or f"{int(math.floor(float(f['carbs_g']) + 0.5))}g carbs"
            for f in (fuel_items or [])
            if f.get("distance_m") is not None and lap_start_m <= f["distance_m"] < lap_end_m
        ]
        if fuel:
            label = label + " · " + ", ".join(fuel)
        lap_start_m = lap_end_m

        lo_speed = hi_speed = 0
        lo_hr = hi_hr = 0

        if hr_coaching and max_hr:
            hr_ceiling = lap.get("hr_ceiling")
            if hr_ceiling:
                hi_hr = int(hr_ceiling)
                lo_hr = max(1, hi_hr - 10)     # 10-bpm band ending at the ceiling
        elif pace_coaching:
            pace = lap.get("target_sec_per_km")
            if pace and pace > 0:
                # speed bounds in mm/s for a ±MARGIN sec/km window
                lo_speed = int(1_000_000 / (pace + MARGIN))
                hi_speed = int(1_000_000 / max(1.0, pace - MARGIN))

        steps.append(_build_step(
            idx, name=label, intensity="active",
            duration_type="distance", duration_value=dist_cm,
            lo_speed_mms=lo_speed, hi_speed_mms=hi_speed,
            lo_hr_bpm=lo_hr, hi_hr_bpm=hi_hr,
        ))
        idx += 1

    # ── Open cool-down ───────────────────────────────────────────────────────
    steps.append(_build_step(idx, name="Cool Down", intensity="cooldown",
                             duration_type="open", duration_value=0))
    idx += 1

    # ── Assemble file via the shared SDK encoder helpers ─────────────────────
    enc = Encoder()
    _write_file_id(enc, workout_id=0, time_created_ms=time_created_ms)
    _write_file_creator(enc)
    _write_workout_mesg(enc, name=name, sport=sport_v, sub_sport=sub_sport_v,
                        num_valid_steps=len(steps))
    _write_steps(enc, steps)
    return finish_encoder(enc)
