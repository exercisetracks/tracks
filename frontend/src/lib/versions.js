// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Release versions, compared exactly as the server and the phone compare them
// (backend app/services/update_check.py, mobile core ReleaseVersion), so the
// three never disagree about which side is behind.
//
// Numbers, not strings: as text 1.10.0 sorts below 1.9.0, and the panel would
// say "update" to an older release. A pre-release sorts below its release.
// Found anywhere in the text, so "android/1.2.0 (10200)" and "v1.2.0" parse.

const PATTERN = /v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?/;

export function parseVersion(text) {
  const m = PATTERN.exec(text ?? "");
  if (!m) return null;
  return { major: +m[1], minor: +m[2], patch: +m[3], pre: m[4] ?? null };
}

function compare(a, b) {
  for (const k of ["major", "minor", "patch"]) {
    if (a[k] !== b[k]) return a[k] - b[k];
  }
  if (a.pre === b.pre) return 0;
  if (a.pre === null) return 1;   // the release is above its pre-releases
  if (b.pre === null) return -1;
  return a.pre < b.pre ? -1 : 1;
}

// True only when both parse and `candidate` is strictly newer. An unparseable
// version is never an update: a "please update" that can never be satisfied
// teaches people to ignore the panel.
export function isNewer(candidate, than) {
  const a = parseVersion(candidate), b = parseVersion(than);
  return a !== null && b !== null && compare(a, b) > 0;
}

// "X.Y.Z" out of a client string, for display. A debug build's "-debug" is
// not a pre-release label (mobile ReleaseVersion.ofBuild), so it is dropped.
export function versionOf(clientString) {
  const v = parseVersion((clientString ?? "").replace(/-debug\b/, ""));
  if (!v) return null;
  return `${v.major}.${v.minor}.${v.patch}${v.pre ? `-${v.pre}` : ""}`;
}
