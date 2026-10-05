// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The JavaScript equipment/experience wrappers must agree with the shared
 * golden corpus.
 *
 * spec/fixtures/strength.json is the same file the Python suite
 * (backend/tests/test_spec/test_strength.py) and the Kotlin suite run. The
 * experience-level expected values came from the ORIGINAL
 * backend/app/calculators/strength_plan/leveling.py (backend is authoritative
 * here), so this pins that experienceLevels.js's own default-tier logic,
 * sourced from the shared table, still agrees with the backend.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";

import { EQUIPMENT_OPTIONS } from "../lib/equipment";
import { EXPERIENCE_OPTIONS, EXPERIENCE_LABEL, experienceDefaultTier } from "../lib/experienceLevels";
import { EXPERIENCE_LEVELS, EXPERIENCE_TABLE, VALID_EQUIPMENT } from "../spec/strength";

const FIXTURES = "/spec/fixtures/strength.json";
const available = existsSync(FIXTURES);
const data = available ? JSON.parse(readFileSync(FIXTURES, "utf8")) : null;

describe.skipIf(!available)("shared golden corpus", () => {
  it("has the same 8 equipment values as the backend", () => {
    expect(EQUIPMENT_OPTIONS.map((e) => e.value).sort()).toEqual(
      data.equipment.map((e) => e.value).sort(),
    );
    expect(EQUIPMENT_OPTIONS).toEqual(data.equipment);
  });

  it("matches the backend's default_tier for every case, where defined", () => {
    // The frontend's experienceDefaultTier returns null for an unrecognised
    // experience; the backend returns UNKNOWN_FALLBACK_TIER (3). That is the
    // ORIGINAL, deliberate divergence (see src/spec/experience.js's header),
    // so only known levels are compared here.
    const known = data.default_tier_cases.filter((c) => EXPERIENCE_LEVELS.includes(c.experience));
    const mismatches = known
      .map((c) => ({ ...c, got: experienceDefaultTier(c.experience, c.endurance_goal) }))
      .filter((c) => c.got !== c.expected);
    expect(mismatches).toEqual([]);
  });

  it("returns null for an experience not in the table", () => {
    const unknownCases = data.default_tier_cases.filter((c) => !EXPERIENCE_LEVELS.includes(c.experience));
    expect(unknownCases.length).toBeGreaterThan(0);
    for (const c of unknownCases) {
      expect(experienceDefaultTier(c.experience, c.endurance_goal)).toBeNull();
    }
  });
});

describe("generated table shape", () => {
  it("keeps VALID_EQUIPMENT and EQUIPMENT_OPTIONS the same 8 values", () => {
    expect(new Set(VALID_EQUIPMENT)).toEqual(new Set(EQUIPMENT_OPTIONS.map((e) => e.value)));
    expect(VALID_EQUIPMENT.length).toBe(8);
  });

  it("keeps EXPERIENCE_OPTIONS and EXPERIENCE_LABEL derived in level order", () => {
    expect(EXPERIENCE_OPTIONS.map((o) => o.value)).toEqual(EXPERIENCE_LEVELS);
    for (const o of EXPERIENCE_OPTIONS) {
      expect(EXPERIENCE_LABEL[o.value]).toBe(o.label);
      expect(o.blurb).toBeTruthy();
    }
  });

  it("gives brand_new the only reduced endurance tier", () => {
    expect(EXPERIENCE_TABLE.brand_new.default_tier.endurance).toBe(1);
    for (const level of ["returning", "regular", "advanced"]) {
      expect(EXPERIENCE_TABLE[level].default_tier.endurance).toBe(2);
    }
  });

  it("caps difficulty only for brand_new and returning", () => {
    expect(EXPERIENCE_TABLE.brand_new.max_difficulty).toBe(2);
    expect(EXPERIENCE_TABLE.returning.max_difficulty).toBe(3);
    expect(EXPERIENCE_TABLE.regular.max_difficulty).toBeNull();
    expect(EXPERIENCE_TABLE.advanced.max_difficulty).toBeNull();
  });
});
