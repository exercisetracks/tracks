// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Named themes for the activity heatmap.
// `color`   — polyline stroke color for all tracks.
// `opacity` — per-track opacity; sections traversed many times stack toward full brightness.
// Exported here so the future user-settings UI can import and switch themes.
export const HEATMAP_THEMES = {
  electric: { color: "#ff7700", opacity: 0.38 },
  fire:     { color: "#ff5500", opacity: 0.38 },
  plasma:   { color: "#cc4778", opacity: 0.38 },
  cool:     { color: "#0891b2", opacity: 0.38 },
};
