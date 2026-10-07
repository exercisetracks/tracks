// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Pure (non-React) date helpers for the Health page's injury and logging
// pieces. Kept framework-free so they're trivially testable.

import { localIso } from "./scales";

export function fmtDate(s) {
  if (!s) return "—";
  return new Date(s + "T00:00:00").toLocaleDateString(undefined, {
    month: "short", day: "numeric", year: "numeric",
  });
}

// Today in the account's zone (lib/today.js). It was toISOString().slice(0, 10), which is
// the UTC date — so an entry logged on a Tuesday evening west of Greenwich was
// filed under Wednesday, and never appeared on Tuesday's dials.
export function isoToday() { return localIso(); }
