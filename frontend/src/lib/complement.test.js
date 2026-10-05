// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from "vitest";
import { complementOf } from "./complement";

const hex = (h) => [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));

// The same vectors as mobile ComplementTest, so both apps agree.
const VECTORS = [
  ["059669", false, "#CF0745"], ["34D399", true, "#E2799F"],
  ["2563EB", false, "#C48C12"], ["60A5FA", true, "#FAB561"],
  ["7C3AED", false, "#83C412"], ["A78BFA", true, "#D2F863"],
  ["E11D48", false, "#18BE99"], ["FB7185", true, "#60FBE4"],
  ["D97706", false, "#0664D0"], ["FBBF24", true, "#5F8BFC"],
];

describe("complementOf", () => {
  it.each(VECTORS)("the complement of %s (dark=%s) matches the phone", (accent, dark, expected) => {
    expect(complementOf(hex(accent), dark)).toBe(expected);
  });
});
