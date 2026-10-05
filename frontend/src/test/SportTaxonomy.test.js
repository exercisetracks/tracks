// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The JavaScript evaluator must agree with the shared golden corpus.
 *
 * This reads the same spec/fixtures/sport_taxonomy.json that the Python suite
 * (backend/tests/test_spec/) and the Kotlin suite run. Its expected values came
 * from the original sportUtils.js, so these tests pin two things: that moving
 * the rules into the spec changed no behaviour, and that the three
 * hand-written evaluators still agree with each other.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";

import { SPORT_TYPES, getSportType, isCycling, isMTB } from "../utils/sportUtils";
import { FALLBACK, RULES, SPORT_TYPE_LIST } from "../spec/sportTaxonomy";

// Mounted read-only into the container — see docker-compose.yml.
const FIXTURES = "/spec/fixtures/sport_taxonomy.json";
const available = existsSync(FIXTURES);

const cases = available
  ? JSON.parse(readFileSync(FIXTURES, "utf8")).cases
  : [];

describe.skipIf(!available)("shared golden corpus", () => {
  it("is present and covers every sport type", () => {
    // A corpus that silently shrank would make the test below pass without
    // checking anything.
    expect(cases.length).toBeGreaterThanOrEqual(50);
    expect(new Set(cases.map((c) => c.expected))).toEqual(new Set(SPORT_TYPE_LIST));
  });

  it("classifies every case the same way", () => {
    const mismatches = cases
      .map((c) => ({ ...c, got: getSportType({ sport: c.sport, sub_sport: c.sub_sport }) }))
      .filter((c) => c.got !== c.expected)
      .map((c) => `("${c.sport}", "${c.sub_sport}"): expected ${c.expected}, got ${c.got}`);
    expect(mismatches).toEqual([]);
  });
});

describe("ordering invariants", () => {
  const type = (sport, sub_sport) => getSportType({ sport, sub_sport });

  it("puts bouldering before climbing", () => {
    expect(type("rock_climbing", "bouldering")).toBe("bouldering");
    expect(type("rock_climbing", "indoor_climbing")).toBe("climbing");
  });

  it("lets a yoga sub-sport beat bare training", () => {
    // Garmin files a logged yoga session as training/yoga; the sub-sport says what it was.
    expect(type("training", "yoga")).toBe("mind_body");
    expect(type("training", "pilates")).toBe("mind_body");
    expect(type("fitness_equipment", "yoga")).toBe("mind_body");
    expect(type("training", "")).toBe("strength");
  });

  it("puts mtb before generic cycling", () => {
    expect(type("mountain_biking", "")).toBe("mtb");
    expect(type("cycling", "road")).toBe("cycling");
  });

  it("puts indoor before mtb and cycling", () => {
    expect(type("cycling", "virtual")).toBe("indoor_cycling");
    expect(type("cycling", "indoor_cycling")).toBe("indoor_cycling");
  });
});

describe("app-facing helpers", () => {
  it("exposes SCREAMING_SNAKE constants for every type", () => {
    for (const t of SPORT_TYPE_LIST) {
      expect(SPORT_TYPES[t.toUpperCase()]).toBe(t);
    }
  });

  it("treats MTB as cycling but not the reverse", () => {
    const mtb = { sport: "mountain_biking" };
    const road = { sport: "cycling", sub_sport: "road" };
    expect(isCycling(mtb)).toBe(true);
    expect(isMTB(mtb)).toBe(true);
    expect(isCycling(road)).toBe(true);
    expect(isMTB(road)).toBe(false);
  });

  it("survives missing and malformed input", () => {
    expect(getSportType(undefined)).toBe(FALLBACK);
    expect(getSportType({})).toBe(FALLBACK);
    expect(getSportType({ sport: null, sub_sport: null })).toBe(FALLBACK);
    expect(getSportType({ sport: "Rock Climbing", sub_sport: "Indoor Climbing" }))
      .toBe("climbing");
  });
});

describe("generated rule table", () => {
  it("only produces declared types", () => {
    const declared = new Set(SPORT_TYPE_LIST);
    for (const rule of RULES) expect(declared.has(rule.type)).toBe(true);
    expect(declared.has(FALLBACK)).toBe(true);
  });

  it("gives every rule at least one match condition", () => {
    for (const rule of RULES) expect(rule.any.length).toBeGreaterThan(0);
  });
});
