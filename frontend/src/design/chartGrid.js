// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The one chart grid, the phone's (mobile ui/dashboard/Charts.kt,
// `solidGuideline`): solid hairlines, horizontal only, in a faint grey from
// the theme.
//
// Every chart used to bring its own — dashed or solid, slate-800 in both
// themes (all but invisible on white), slate-200 at half opacity, with or
// without vertical lines — so two charts side by side never had the same
// background. Dashes in particular are kept for lines that mean something
// (a threshold, an average, a goal); spent on the grid, the two kinds of line
// were indistinguishable. Vertical lines go because the x axis on every chart
// here is time or distance, read along a trace rather than against a column.
//
// The colour is `--chart-grid` in index.css, so it follows the theme.
// StyleKit.test.js fails on a <CartesianGrid> that does not spread this.

/** Spread onto every recharts <CartesianGrid>. */
export const CHART_GRID = {
  stroke: "var(--chart-grid)",
  strokeWidth: 1,
  vertical: false,
};
