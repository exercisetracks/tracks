// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ============================================================
// ACTIVITIES LIST — PURE FORMATTERS & LOOKUPS
// ============================================================
// Framework-free (no React) helpers used by the Activities list
// page and its row/header sub-components. Kept separate so the
// display logic is trivially unit-testable and reusable.
//
// NOTE: These intentionally differ from src/utils/formatUtils.js.
// The Activities list uses a compact, list-optimised style
// ("1h 30m" durations, a combined pace/speed cell that switches
// on sport). Do NOT swap these for the shared formatters — the
// output strings would change.
// ============================================================

/**
 * Format duration in seconds to human-readable format.
 * Shows hours/minutes or minutes/seconds depending on length.
 */
export function fmtDuration(s) {
  if (!s) return "—";
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec}s`;
  return `${sec}s`;
}

/**
 * Format ISO date string to readable date.
 * Example: "2024-01-15T10:30:00Z" -> "Jan 15, 2024"
 */
export function fmtDate(iso) {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

/**
 * Format distance in meters to miles or kilometers.
 */
export function fmtDist(m, imperial) {
  if (m == null) return "—";
  return imperial ? `${(m / 1609.34).toFixed(2)} mi` : `${(m / 1000).toFixed(2)} km`;
}

/**
 * Format speed/pace based on sport type.
 * Running/walking/hiking: shows pace (min per km/mi).
 * Other sports: shows speed (km/h or mph).
 */
export function fmtSpeed(mps, imperial, sport) {
  if (!mps) return "—";
  const isRunLike = /run|walk|hike/i.test(sport ?? "");
  if (isRunLike) {
    const minPerUnit = imperial ? 1609.34 / (mps * 60) : 1000 / (mps * 60);
    const mn = Math.floor(minPerUnit);
    const sc = Math.round((minPerUnit - mn) * 60).toString().padStart(2, "0");
    return imperial ? `${mn}:${sc} /mi` : `${mn}:${sc} /km`;
  }
  return imperial ? `${(mps * 2.23694).toFixed(1)} mph` : `${(mps * 3.6).toFixed(1)} km/h`;
}
