// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Race-day fueling calculator.
 *
 * The thresholds are no longer literals here — they live in
 * spec/fueling.yaml and are generated into src/spec/fueling.js, the same
 * table mobile/core's spec/Fueling.kt uses for the no-network race-day
 * screen. This file stays the hand-written algorithm and the oracle
 * spec/fixtures/fueling.json was baselined against (there is no backend
 * equivalent to be authoritative instead — this has always been a
 * frontend-only calculator).
 *
 * To change a threshold, edit spec/fueling.yaml and run
 * `python3 spec/codegen.py`. Editing the generated file does nothing.
 */
import {
  CARB_BREAKPOINTS,
  DEFAULT_CARBS_PER_HOUR,
  HEAT_THRESHOLD_C,
  VERY_HOT_THRESHOLD_C,
  HUMIDITY_THRESHOLD_PCT,
  HUMIDITY_MIN_TEMP_C,
  BANDS,
} from "../spec/fueling";

export function defaultCarbsPerHour(durationHours) {
  for (const bp of CARB_BREAKPOINTS) {
    if (durationHours <= bp.max_hours) return bp.carbs_per_hour;
  }
  return DEFAULT_CARBS_PER_HOUR;
}

export function computeFuelingParams(durationHours, weather) {
  const temp = weather?.temperature_c;
  const humidity = weather?.humidity_pct;

  const isHot = temp != null && temp >= HEAT_THRESHOLD_C;
  const isVeryHot = temp != null && temp >= VERY_HOT_THRESHOLD_C;
  const isHumid = humidity != null && humidity >= HUMIDITY_THRESHOLD_PCT && (temp ?? HUMIDITY_MIN_TEMP_C) >= HUMIDITY_MIN_TEMP_C;
  const heatAdjusted = isHot || isHumid;

  const band = isVeryHot ? BANDS.very_hot : heatAdjusted ? BANDS.hot : BANDS.normal;
  const concentrationPct = band.concentration_pct;
  const sipIntervalMin = band.sip_interval_min;
  // The original computes firstSipMin separately from sipIntervalMin, but
  // they are equal in every branch — see spec/fueling.yaml's note.
  const firstSipMin = sipIntervalMin;
  const saltIdx = band.salt_index;

  return {
    concentrationPct,
    concentration: concentrationPct / 100,
    sipIntervalMin,
    firstSipMin,
    heatAdjusted,
    isHot,
    isVeryHot,
    isHumid,
    saltIdx,
  };
}

export function computeSipMl(carbsPerHour, concentration, sipIntervalMin) {
  const volumePerHourMl = carbsPerHour / concentration;
  const sipsPerHour = 60 / sipIntervalMin;
  return Math.round((volumePerHourMl / sipsPerHour) / 10) * 10;
}

export function computePerLapDrinks(laps, sipIntervalMin, firstSipMin, perSipMl) {
  if (!laps?.length) return [];

  const drinks = new Array(laps.length).fill(null);
  let cumulativeSec = 0;
  let nextSipSec = firstSipMin * 60;

  for (let i = 0; i < laps.length; i++) {
    const lap = laps[i];
    if (lap.target_sec_per_km == null || lap.distance_m == null || lap.distance_m <= 0) {
      cumulativeSec += 0;
      continue;
    }
    const lapTime = (lap.distance_m / 1000) * lap.target_sec_per_km;
    const end = cumulativeSec + lapTime;

    while (nextSipSec <= end) {
      drinks[i] = { sipMl: perSipMl, atMin: Math.round(nextSipSec / 60) };
      nextSipSec += sipIntervalMin * 60;
    }

    cumulativeSec += lapTime;
  }

  return drinks;
}

export function getPerLapDrinks(laps, predictedSeconds, fuelingPlanCarbs, weather) {
  if (!laps?.length || !predictedSeconds) return [];

  const durationHours = predictedSeconds / 3600;
  const carbsPerHour = fuelingPlanCarbs ?? defaultCarbsPerHour(durationHours);
  const params = computeFuelingParams(durationHours, weather);
  const perSipMl = computeSipMl(carbsPerHour, params.concentration, params.sipIntervalMin);
  return computePerLapDrinks(laps, params.sipIntervalMin, params.firstSipMin, perSipMl);
}
