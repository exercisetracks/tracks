// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * What day it is, in the account's time zone.
 *
 * The zone in Settings is the one every day is counted in — the server buckets
 * activities by it (backend calculators/local_day.py) and the phone keeps it in
 * step with where it is. The web ignored it: "today" was worked out as
 * `toISOString().slice(0, 10)` in some places, which is the UTC date and turns
 * over at 17:00 in California, and from the browser's own clock in others. So
 * on an October evening the plan calendar marked tomorrow as today (found
 * 2026-10-06, 20:30 PDT).
 *
 * The zone is set once, from the settings row, when the app signs in
 * (AuthContext), and again when it is changed in Settings. Until then — or for
 * an account that never chose one — it is the browser's.
 *
 * Two kinds of date go through here, and they are different:
 *  - an *instant* (now, a timestamp from the server) has a day only in some
 *    zone, so it goes through `isoOf`;
 *  - a calendar cell built with `new Date(y, m, d)` already *is* a day, so it
 *    is read back with `isoOfDay`, from its own fields. `toISOString()` on one
 *    shifts it into UTC, which east of Greenwich is the day before.
 */

let accountZone = null;

function valid(tz) {
  if (!tz) return false;
  try {
    new Intl.DateTimeFormat("en-CA", { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/** Set the account's zone (an IANA name). An unknown one is ignored. */
export function setAccountZone(tz) {
  accountZone = valid(tz) ? tz : null;
}

export function getAccountZone() {
  return accountZone;
}

/** The YYYY-MM-DD day an instant falls on, in the account's zone. */
export function isoOf(at = new Date()) {
  // en-CA formats as YYYY-MM-DD.
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: accountZone ?? undefined,
    year: "numeric", month: "2-digit", day: "2-digit",
  }).format(at);
}

/** Today, as YYYY-MM-DD, in the account's zone. */
export function todayIso() {
  return isoOf(new Date());
}

/**
 * Today as a local-midnight Date, for the calendar code that steps days with
 * `setDate` and reads them back with `isoOfDay`. Its fields are the account's
 * today, whatever the browser's clock says.
 */
export function todayDate() {
  const [y, m, d] = todayIso().split("-").map(Number);
  return new Date(y, m - 1, d);
}

/** A calendar-cell Date (built from y, m, d) as YYYY-MM-DD, from its own fields. */
export function isoOfDay(d) {
  const p = n => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
