// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from "vitest";
import { ageFromBirthYear, birthYearFromAge, MAX_AGE, MIN_AGE } from "./age";

// Anchored to a fixed "now" passed in, never to today's date.
const NOW = new Date(2030, 5, 1);

describe("age", () => {
  it("an age and the birth year it stores round-trip", () => {
    expect(birthYearFromAge(34, NOW)).toBe(1996);
    expect(ageFromBirthYear(1996, NOW)).toBe(34);
  });

  it("a stored birth year reads a year older the next year, without an edit", () => {
    const nextYear = new Date(NOW.getFullYear() + 1, 0, 1);
    expect(ageFromBirthYear(birthYearFromAge(34, NOW), nextYear)).toBe(35);
  });

  it("an age typed as text is accepted", () => {
    expect(birthYearFromAge(" 34 ", NOW)).toBe(1996);
  });

  it("an implausible or partial age is refused rather than saved", () => {
    // "3" is what the field holds on the way to typing "34".
    for (const bad of ["", "3", "34.5", "abc", MIN_AGE - 1, MAX_AGE + 1]) {
      expect(birthYearFromAge(bad, NOW)).toBeNull();
    }
  });

  it("no birth year is no age", () => {
    expect(ageFromBirthYear(null, NOW)).toBeNull();
  });
});
