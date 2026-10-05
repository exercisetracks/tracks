// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Deterministic per-feature zoom gating — the pan-stable replacement for
// collision culling. Collision detection is disabled style-wide
// (withStablePlacement in ./index.js) because it re-places symbols based on
// what else is in the viewport: panning reshuffles which labels win, so the
// map "reflows" as you move. Instead, layers with a baked per-feature
// importance grade (min_zoom for places/POIs/public lands, tier for trail
// badges — lower = more important) gate each feature on it directly: a symbol
// renders iff zoom >= its grade. The visible set at a given zoom is a pure
// function of the data, so panning never changes it, and zooming in densifies
// by importance exactly like collision used to — minus the shuffling.
//
// MapLibre only allows ["zoom"] at the top level of an expression, so the
// grade-vs-zoom comparison can't be written directly. gateByMinZoom() builds
// the legal equivalent: a top-level ["step", ["zoom"], …] with one output per
// integer zoom, each a ["case"] comparing the feature's grade to that zoom
// literal. (Symbol layout re-evaluates at integer zooms, so integer steps lose
// nothing.)
//
//   prop    feature property holding the grade (e.g. "min_zoom", "tier")
//   def     grade assumed when the property is missing
//   value   expression to emit when the feature qualifies — or a function
//           (z) => expr for values that themselves vary by zoom band
//   from/to integer zoom range to enumerate (clamp to the layer's own range;
//           below `from` the `from` case applies)
//   hidden  emitted when the feature doesn't qualify ("" = no text/icon)
export function gateByMinZoom(prop, def, value, { from = 3, to = 16, hidden = "" } = {}) {
  const valueAt = typeof value === "function" ? value : () => value;
  const caseFor = (z) =>
    ["case", ["<=", ["coalesce", ["get", prop], def], z], valueAt(z), hidden];
  const step = ["step", ["zoom"], caseFor(from)];
  for (let z = from + 1; z <= to; z++) step.push(z, caseFor(z));
  return step;
}
