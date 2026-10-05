// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The fueling calculator must agree with the shared golden corpus.
 *
 * Unlike the other specs, this one has no Python side — fueling has always
 * been a client-side race-day calculator — so spec/fixtures/fueling.json is
 * shared by exactly two implementations: this file's subject
 * (src/utils/fuelingUtils.js, the original the corpus was baselined against)
 * and mobile/core's spec/Fueling.kt, which runs the same numbers on a phone
 * with no network.
 *
 * Two implementations and one corpus means this suite is half of the only
 * thing keeping them together. That matters more here than it sounds: the
 * failure it prevents is a drink schedule on a start line that disagrees with
 * the plan the athlete reviewed in a browser the night before.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";

import {
  computeFuelingParams,
  computePerLapDrinks,
  computeSipMl,
  defaultCarbsPerHour,
  getPerLapDrinks,
} from "../utils/fuelingUtils";

const FIXTURES = "/spec/fixtures/fueling.json";
const available = existsSync(FIXTURES);
const data = available ? JSON.parse(readFileSync(FIXTURES, "utf8")) : null;

describe.skipIf(!available)("shared golden corpus", () => {
  it("has a corpus that has not silently shrunk", () => {
    // Guards the suites below: a corpus trimmed to nothing would let every
    // one of them pass while checking nothing at all.
    expect(data.default_carbs_per_hour_cases.length).toBeGreaterThanOrEqual(8);
    expect(data.fueling_params_cases.length).toBeGreaterThanOrEqual(10);
  });

  it("matches default carbs/hour at every breakpoint", () => {
    const mismatches = data.default_carbs_per_hour_cases
      .map((c) => ({ ...c, got: defaultCarbsPerHour(c.duration_hours) }))
      .filter((c) => c.got !== c.expected);
    expect(mismatches).toEqual([]);
  });

  it("classifies heat and selects the band identically", () => {
    const mismatches = data.fueling_params_cases
      .map((c) => ({
        name: c.name,
        expected: c.expected,
        got: computeFuelingParams(c.duration_hours, c.weather),
      }))
      .filter((c) => JSON.stringify(c.got) !== JSON.stringify(c.expected));
    expect(mismatches).toEqual([]);
  });

  it("rounds per-sip volume identically", () => {
    const mismatches = data.sip_ml_cases
      .map((c) => ({
        ...c,
        got: computeSipMl(c.carbsPerHour, c.concentration, c.sipIntervalMin),
      }))
      .filter((c) => c.got !== c.expected);
    expect(mismatches).toEqual([]);
  });

  it("lands drinks on the same laps", () => {
    const mismatches = data.per_lap_drinks_cases
      .map((c) => ({
        name: c.name,
        expected: c.expected,
        got: computePerLapDrinks(c.laps, c.sip_interval_min, c.first_sip_min, c.per_sip_ml),
      }))
      .filter((c) => JSON.stringify(c.got) !== JSON.stringify(c.expected));
    expect(mismatches).toEqual([]);
  });

  it("produces the same end-to-end schedule", () => {
    const mismatches = data.get_per_lap_drinks_cases
      .map((c) => ({
        name: c.name,
        expected: c.expected,
        got: getPerLapDrinks(c.laps, c.predicted_seconds, c.fueling_plan_carbs, c.weather),
      }))
      .filter((c) => JSON.stringify(c.got) !== JSON.stringify(c.expected));
    expect(mismatches).toEqual([]);
  });
});

describe("thresholds come from the spec, not from literals here", () => {
  it("treats 25C as hot and 32C as very hot", () => {
    expect(computeFuelingParams(2, { temperature_c: 24.9 }).isHot).toBe(false);
    expect(computeFuelingParams(2, { temperature_c: 25 }).isHot).toBe(true);
    expect(computeFuelingParams(2, { temperature_c: 31.9 }).isVeryHot).toBe(false);
    expect(computeFuelingParams(2, { temperature_c: 32 }).isVeryHot).toBe(true);
  });

  it("only counts humidity as heat when it is also warm enough", () => {
    // 80% humidity at 10C is not a fueling problem; at 25C it is. Pinned
    // because the compound condition is easy to simplify incorrectly.
    expect(computeFuelingParams(2, { temperature_c: 10, humidity_pct: 80 }).isHumid).toBe(false);
    expect(computeFuelingParams(2, { temperature_c: 25, humidity_pct: 80 }).isHumid).toBe(true);
  });

  it("returns no drinks without laps or a predicted duration", () => {
    expect(getPerLapDrinks([], 1500, null, null)).toEqual([]);
    expect(getPerLapDrinks([{ target_sec_per_km: 300, distance_m: 1000 }], null, null, null))
      .toEqual([]);
  });
});
