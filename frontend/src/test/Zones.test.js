// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Zone tables now come from the shared spec (spec/zones.yaml) instead of being
 * written out in each consumer. These tests pin the values that were previously
 * literals, so "sourced from the spec" can't quietly become "sourced from the
 * spec, but different".
 */
import { describe, expect, it } from "vitest";

import { BOUNDARIES, ZONES, zoneFor } from "../components/Charts/fitnessChartData";
import { HR_MODELS, POWER_MODELS, TSB_BANDS } from "../spec/zones";

describe("TSB form bands", () => {
  it("keeps the five bands in high-to-low order", () => {
    expect(ZONES.map((z) => z.label)).toEqual([
      "Transition", "Fresh", "Grey Zone", "Optimal", "High Risk",
    ]);
  });

  it("keeps the original boundaries", () => {
    expect(BOUNDARIES).toEqual([25, 5, -5, -30]);
  });

  it("maps the unbounded ends to ±Infinity for Recharts", () => {
    expect(ZONES[0].y2).toBe(Infinity);
    expect(ZONES[ZONES.length - 1].y1).toBe(-Infinity);
  });

  it("classifies exactly as the old if-ladder did", () => {
    // The original: >25 Transition, >5 Fresh, >-5 Grey, >-30 Optimal, else High Risk.
    expect(zoneFor(30).label).toBe("Transition");
    expect(zoneFor(25.1).label).toBe("Transition");
    expect(zoneFor(25).label).toBe("Fresh");     // boundary is exclusive
    expect(zoneFor(6).label).toBe("Fresh");
    expect(zoneFor(5).label).toBe("Grey Zone");
    expect(zoneFor(0).label).toBe("Grey Zone");
    expect(zoneFor(-5).label).toBe("Optimal");
    expect(zoneFor(-30).label).toBe("High Risk");
    expect(zoneFor(-100).label).toBe("High Risk");
  });

  it("falls back to the neutral band for a missing value", () => {
    expect(zoneFor(null).label).toBe("Grey Zone");
    expect(zoneFor(undefined).label).toBe("Grey Zone");
  });
});

describe("zone model tables", () => {
  it("keeps the two HR models distinct and separately named", () => {
    // Collapsing these would change what the activity histogram has always
    // drawn — see the note in spec/zones.yaml.
    expect(HR_MODELS.friel_lthr_run.basis).toBe("lthr");
    expect(HR_MODELS.display_maxhr.basis).toBe("max_hr");
    expect(HR_MODELS.friel_lthr_run.zones).toHaveLength(7);
    expect(HR_MODELS.display_maxhr.zones).toHaveLength(5);
  });

  it("keeps the cycling/running boundary difference", () => {
    expect(HR_MODELS.friel_lthr_run.zones[0].max_pct).toBe(0.85);
    expect(HR_MODELS.friel_lthr_bike.zones[0].max_pct).toBe(0.81);
  });

  it("tiles every model without gaps or overlaps", () => {
    // A gap silently drops samples out of every zone total, which looks like
    // missing data rather than a table error.
    for (const [name, model] of Object.entries({ ...HR_MODELS, ...POWER_MODELS })) {
      const zones = model.zones;
      expect(zones[0].min_pct, `${name} must start at 0`).toBe(0);
      expect(zones[zones.length - 1].max_pct, `${name} must be open-ended`).toBeNull();
      for (let i = 1; i < zones.length; i++) {
        expect(zones[i].min_pct, `${name} zone ${i + 1} must abut zone ${i}`)
          .toBe(zones[i - 1].max_pct);
      }
    }
  });

  it("numbers zones consecutively from 1", () => {
    for (const model of [...Object.values(HR_MODELS), ...Object.values(POWER_MODELS)]) {
      expect(model.zones.map((z) => z.number)).toEqual(
        model.zones.map((_, i) => i + 1),
      );
    }
  });

  it("gives the display model a colour for every zone", () => {
    for (const z of HR_MODELS.display_maxhr.zones) {
      expect(z.color).toMatch(/^#[0-9a-f]{6}$/i);
    }
  });

  it("names a fallback band that exists", () => {
    expect(TSB_BANDS.bands.map((b) => b.key)).toContain(TSB_BANDS.unknown);
  });
});
