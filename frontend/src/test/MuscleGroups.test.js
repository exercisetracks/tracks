// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The JavaScript muscle-activation evaluator must agree with the shared
 * golden corpus.
 *
 * This reads the same spec/fixtures/muscle_groups.json the Python suite
 * (backend/tests/test_spec/test_muscle_groups.py) and the Kotlin suite run.
 * Its expected values came from the original frontend/src/utils/muscleGroups.js
 * — evaluated before this move — so these tests pin that moving the table
 * into the spec and the algorithm into spec/muscleActivation.js changed no
 * behaviour.
 */
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";

import { computeMuscleActivation, categoryLabel, muscleLabel, CATEGORY_MUSCLES } from "../utils/muscleGroups";
import { MUSCLE_LABELS, CATEGORY_LABELS } from "../spec/muscleGroups";

const FIXTURES = "/spec/fixtures/muscle_groups.json";
const available = existsSync(FIXTURES);
const data = available ? JSON.parse(readFileSync(FIXTURES, "utf8")) : null;

describe.skipIf(!available)("shared golden corpus", () => {
  it("is present and substantial", () => {
    expect(data.activation_cases.length).toBeGreaterThanOrEqual(10);
    expect(data.category_label_cases.length).toBeGreaterThanOrEqual(8);
    expect(data.muscle_label_cases.length).toBeGreaterThanOrEqual(5);
  });

  it("computes activation identically for every case", () => {
    const mismatches = data.activation_cases
      .map((c) => ({ ...c, got: computeMuscleActivation(c.sets) }))
      .filter((c) => (
        JSON.stringify(c.got.activation) !== JSON.stringify(c.expected.activation) ||
        JSON.stringify(c.got.totals) !== JSON.stringify(c.expected.totals) ||
        JSON.stringify(c.got.categoryCounts) !== JSON.stringify(c.expected.category_counts)
      ))
      .map((c) => `${c.name}: expected ${JSON.stringify(c.expected)}, got ${JSON.stringify(c.got)}`);
    expect(mismatches).toEqual([]);
  });

  it("labels every category identically", () => {
    const mismatches = data.category_label_cases
      .filter((c) => categoryLabel(c.key) !== c.expected)
      .map((c) => `${JSON.stringify(c.key)}: expected ${c.expected}, got ${categoryLabel(c.key)}`);
    expect(mismatches).toEqual([]);
  });

  it("labels every muscle identically", () => {
    const mismatches = data.muscle_label_cases
      .filter((c) => muscleLabel(c.key) !== c.expected)
      .map((c) => `${c.key}: expected ${c.expected}, got ${muscleLabel(c.key)}`);
    expect(mismatches).toEqual([]);
  });
});

describe("generated table shape", () => {
  it("covers roughly 35 exercise categories", () => {
    expect(Object.keys(CATEGORY_MUSCLES).length).toBeGreaterThanOrEqual(30);
  });

  it("keeps Garmin firmware extensions out of category_labels", () => {
    for (const key of ["hip_hinge", "push", "pull"]) {
      expect(CATEGORY_MUSCLES[key]).toBeDefined();
      expect(CATEGORY_LABELS[key]).toBeUndefined();
    }
  });

  it("labels the library-only muscles instead of showing their raw keys", () => {
    // 'shoulders' (total_body's secondary list) and the keys only the
    // exercise/stretch libraries use have no body-diagram region, but they
    // are shown as tags — unlabelled, the raw key reached the screen.
    expect(muscleLabel("shoulders")).toBe("Shoulders");
    expect(muscleLabel("hip_external_rotators")).toBe("Hip External Rotators");
    expect(muscleLabel("totally_unknown_key")).toBe("totally_unknown_key");
    expect(MUSCLE_LABELS.shoulders).toBeDefined();
  });
});

describe("edge cases", () => {
  it("treats a zero repetitions the same as missing (JS falsy-zero quirk)", () => {
    const withZero = computeMuscleActivation([{ set_type: "active", exercise_category: "curl", repetitions: 0 }]);
    const withMissing = computeMuscleActivation([{ set_type: "active", exercise_category: "curl" }]);
    expect(withZero).toEqual(withMissing);
    expect(withZero.totals.biceps).toBe(10);
  });

  it("returns Unknown for a null or blank category label", () => {
    expect(categoryLabel(null)).toBe("Unknown");
    expect(categoryLabel("")).toBe("Unknown");
  });

  it("title-cases an unrecognised category from its key", () => {
    expect(categoryLabel("some_custom_category")).toBe("Some Custom Category");
  });
});
