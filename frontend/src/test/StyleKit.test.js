// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The desktop's chrome comes from one kit — the classes in src/design/kit.js
 * and the components in components/ui/ — the way the phone's comes from its
 * shared Compose components. Before the kit, every feature folder carried its
 * own copy of the card, the section heading, the input, the switch and the
 * dialog, each a few pixels or a shade off the last, and pages drifted apart
 * one reasonable-looking edit at a time.
 *
 * These tests fail when a hand-written copy of a kit piece comes back. Each
 * names the piece to use instead. They read source text rather than render
 * pages, so they are cheap and catch the copy at the moment it is pasted.
 */
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

const SRC = join(__dirname, "..");

function sources(dir = SRC) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === "test" ? [] : sources(path);
    return /\.jsx?$/.test(name) ? [path] : [];
  });
}

const files = sources().map((path) => ({ path: relative(SRC, path), text: readFileSync(path, "utf8") }));

/** Files whose source matches `pattern`, minus the ones allowed to. */
function offenders(pattern, allowed = []) {
  return files
    .filter(({ path }) => !allowed.some((a) => path.startsWith(a)))
    .filter(({ text }) => pattern.test(text))
    .map(({ path }) => path);
}

// Every class in a quoted string, as a set — so the order classes were
// written in does not hide a copy.
function classLists(text) {
  return [...text.matchAll(/["`]([^"`]*)["`]/g)].map((m) => new Set(m[1].split(/\s+/)));
}

describe("the desktop style kit", () => {
  it("draws every card with .card rather than a copy of its classes", () => {
    const card = ["bg-white", "dark:bg-slate-900", "rounded-xl", "border", "border-slate-200", "dark:border-slate-800"];
    const copies = files
      .filter(({ text }) => classLists(text).some((set) => card.every((c) => set.has(c))))
      .map(({ path }) => path);
    expect(copies).toEqual([]);
  });

  it("dims the page behind a dialog with .modal-backdrop, not a private overlay", () => {
    // The two workout players are full-screen pages, not dialogs.
    const copies = offenders(/fixed inset-0[^"`]*bg-(black|slate-900)\//, ["components/player/"]);
    expect(copies).toEqual([]);
  });

  it("leaves checkboxes to the base style instead of forms-plugin classes", () => {
    // focus:ring-* and text-accent-* on a checkbox only mean something with
    // @tailwindcss/forms, which the app does not install; they were why every
    // checkbox rendered as the browser's bare white box.
    const copies = files
      .filter(({ text }) => /type="checkbox"[^>]*className="[^"]*(focus:ring|text-accent)/s.test(text))
      .map(({ path }) => path);
    expect(copies).toEqual([]);
  });

  it("has one switch, ui/Switch", () => {
    expect(offenders(/role="switch"/, ["components/ui/Switch.jsx", "design/"])).toEqual([]);
  });

  it("styles text inputs with .field rather than a local class string", () => {
    // INPUT / SELECT constants may remain as names for "field"; a constant
    // holding its own border and padding is a copy.
    const copies = offenders(/(INPUT|SELECT|inputCls|INPUT_CLS)\s*=\s*"[^"]*\bborder\b/);
    expect(copies).toEqual([]);
  });

  it("keeps one Section, one InfoTooltip and one confirmation dialog", () => {
    const local = offenders(/^(export )?function (Section|InfoTooltip|ConfirmModal)\b/m, [
      "components/ui/",
      // Thin wrappers over the shared Section, keeping their call sites' props.
      "components/settings/primitives.jsx",
      "components/raceplan/ui.jsx",
      // The map legend is drawn on the map's own paper palette.
      "pages/maps/components/MapLegend.jsx",
    ]);
    expect(local).toEqual([]);
  });
});
