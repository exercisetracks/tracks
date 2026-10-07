// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * "Today" is the account's day. The web used to take it from
 * `toISOString()`, the UTC date, so from 17:00 in California the plan
 * calendar marked tomorrow as today (2026-10-06).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { isoOf, isoOfDay, setAccountZone, todayDate, todayIso } from "../lib/today";

// 18:04 PDT on Wednesday 30 September 2026 — already 1 October in UTC.
const EVENING_IN_LA = new Date("2026-10-01T01:04:00Z");

describe("today", () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(EVENING_IN_LA); });
  afterEach(() => { vi.useRealTimers(); setAccountZone(null); });

  it("is the day in the account's zone, not the UTC day", () => {
    setAccountZone("America/Los_Angeles");
    expect(todayIso()).toBe("2026-09-30");
    setAccountZone("Europe/Berlin");
    expect(todayIso()).toBe("2026-10-01");
  });

  it("as a Date, carries the account's day in its own fields", () => {
    setAccountZone("America/Los_Angeles");
    expect(isoOfDay(todayDate())).toBe("2026-09-30");
  });

  it("puts a server timestamp on the account's day", () => {
    setAccountZone("Pacific/Auckland");
    expect(isoOf(EVENING_IN_LA)).toBe("2026-10-01");
    setAccountZone("America/Los_Angeles");
    expect(isoOf(EVENING_IN_LA)).toBe("2026-09-30");
  });

  it("ignores a zone the browser does not know", () => {
    setAccountZone("America/Los_Angeles");
    setAccountZone("Not/AZone");
    // Back to the browser's own zone rather than throwing on every date.
    expect(todayIso()).toBe(isoOf(new Date()));
  });

  it("reads a calendar cell from its own fields, not through UTC", () => {
    // toISOString() on this is 6 October anywhere east of Greenwich.
    expect(isoOfDay(new Date(2026, 9, 7))).toBe("2026-10-07");
  });
});

describe("the source", () => {
  it("never takes a date from toISOString()", () => {
    const SRC = join(__dirname, "..");
    const files = (dir) => readdirSync(dir).flatMap((name) => {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) return name === "test" ? [] : files(path);
      return /\.(jsx?|tsx?)$/.test(name) ? [path] : [];
    });
    const offenders = files(SRC)
      .filter((f) => !f.endsWith("lib/today.js"))
      .filter((f) => readFileSync(f, "utf8").split("\n")
        .some((line) => !line.trim().startsWith("//") && /toISOString\(\)\.(slice|substring)\(0, ?10\)|toISOString\(\)\.split\("T"\)/.test(line)))
      .map((f) => relative(SRC, f));
    // The UTC date, which is tomorrow every evening west of Greenwich. Use
    // todayIso() / isoOf() / isoOfDay() from lib/today.js instead.
    expect(offenders).toEqual([]);
  });
});
