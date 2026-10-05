// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared store for the last N custom colors the user has picked ANYWHERE in the
// app (track colours, accent colour, …). Persisted in localStorage so the custom
// colour picker can offer them everywhere.
const KEY = "tracks_recent_colors";
const MAX = 20;

export function getRecentColors() {
  try {
    const v = JSON.parse(localStorage.getItem(KEY));
    return Array.isArray(v) ? v.slice(0, MAX) : [];
  } catch {
    return [];
  }
}

export function addRecentColor(hex) {
  if (!hex || typeof hex !== "string") return getRecentColors();
  const h = hex.toLowerCase();
  let list = getRecentColors().filter((c) => c.toLowerCase() !== h);
  list.unshift(h);
  list = list.slice(0, MAX);
  try { localStorage.setItem(KEY, JSON.stringify(list)); } catch { /* quota */ }
  return list;
}
