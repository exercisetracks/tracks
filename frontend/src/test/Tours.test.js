// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The tutorial against the app it describes.
 *
 * A tip whose `data-tour` target has been renamed or deleted does not fail —
 * TourTooltip quietly shows it centred, pointing at nothing — so nobody would
 * notice until a new user did. The source scan is what catches that. The
 * phone's copy of this test is mobile/app/.../ui/tour/TourContentTest.kt.
 */
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { ROUTE_TOURS, TOURS, tourIdForPath } from "../components/tour/tours";

const SRC = join(__dirname, "..");

function sources(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "test" ? [] : sources(path);
    return /\.(jsx?|tsx?)$/.test(name) ? [path] : [];
  });
}

// Every name given to a data-tour attribute or a dataTour prop, anywhere.
function placedAnchors() {
  const names = new Set();
  for (const file of sources(SRC)) {
    const text = readFileSync(file, "utf8");
    for (const m of text.matchAll(/data(?:-tour|Tour)[=:]\s*["']([a-z0-9-]+)["']/g)) names.add(m[1]);
    // dataTour values handed through a data object (health/metrics.jsx).
    for (const m of text.matchAll(/dataTour:\s*["']([a-z0-9-]+)["']/g)) names.add(m[1]);
  }
  return names;
}

describe("tours", () => {
  it("every anchor a tip points at is placed somewhere in the app", () => {
    const placed = placedAnchors();
    const missing = Object.entries(TOURS).flatMap(([id, steps]) =>
      steps
        .filter((s) => s.anchor)
        .map((s) => s.anchor.match(/data-tour="([^"]+)"/)[1])
        .filter((name) => !placed.has(name))
        .map((name) => `${id} → ${name}`),
    );
    expect(missing).toEqual([]);
  });

  it("every route's tour exists and says something", () => {
    for (const id of Object.values(ROUTE_TOURS)) {
      expect(TOURS[id]?.length, id).toBeGreaterThan(0);
      for (const step of TOURS[id]) expect(step.title && step.body, id).toBeTruthy();
    }
  });

  it("the activity page has a tour and the other detail pages do not", () => {
    expect(tourIdForPath("/activities/42")).toBe("activity");
    expect(tourIdForPath("/activities")).toBe("activities");
    expect(tourIdForPath("/race-plans/3")).toBeNull();
    expect(tourIdForPath("/music/")).toBe("music");
  });
});
