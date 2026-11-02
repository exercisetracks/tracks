// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The race course's profile: elevation by distance with the plan's target
// alongside, so the plot under the map shows how the target answers the
// hills. Pure maths, no React, and the phone's twin is
// mobile/core/.../race/CourseProfile.kt — the two are kept to the same
// algorithm and the same test numbers.
//
// Which target is drawn is the sport's own coaching number: pace for running,
// power for cycling (and the bike laps of a triathlon), the HR ceiling for
// mountain biking, whose laps carry no watts. An open-water swim has no plot:
// flat water and a single lap.
//
// The target is drawn smooth, through the middle of each lap, rather than as
// steps: the user picked it as easier to read against a hill, at the cost of
// not showing exactly where a target changes (the lap table does that).

const R = 6_371_000;
/** Enough points for a smooth line at any plot width, few enough to stay quick to draw. */
const MAX_POINTS = 400;

/** The lap field each metric reads. */
export const METRICS = {
  pace:  { field: "target_sec_per_km" },
  power: { field: "target_watts" },
  hr:    { field: "hr_ceiling" },
};

/**
 * Which target the plot draws for a plan, or null for no plot. A sport whose
 * own number is missing from its laps (cycling without an FTP) falls back to
 * pace, which every lap carries.
 */
export function targetMetric(sport, laps) {
  if (sport === "swimming") return null;
  const has = (f) => laps?.some((l) => l[f] != null);
  if (sport === "mountain_biking" && has("hr_ceiling")) return "hr";
  if ((sport === "cycling" || sport === "triathlon") && has("target_watts")) return "power";
  return has("target_sec_per_km") ? "pace" : null;
}

function haversine(a, b) {
  const dLat = (b[0] - a[0]) * Math.PI / 180;
  const dLon = (b[1] - a[1]) * Math.PI / 180;
  const h = Math.sin(dLat / 2) ** 2
    + Math.cos(a[0] * Math.PI / 180) * Math.cos(b[0] * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

/**
 * The target at each lap's middle, placed on the course: laps are laid along
 * the plan's distance, which may be the goal's rather than the GPX's, so
 * positions are scaled to the course's length.
 */
function anchors(laps, field, courseM) {
  const total = laps.reduce((s, l) => s + (l.distance_m || 0), 0);
  if (!total) return [];
  const out = [];
  let start = 0;
  for (const l of laps) {
    const d = l.distance_m || 0;
    if (l[field] != null && d > 0) out.push([(start + d / 2) / total * courseM, l[field]]);
    start += d;
  }
  return out;
}

/** Linear between anchors, held level before the first and after the last. */
function interpolate(anchorList, x) {
  if (!anchorList.length) return null;
  if (x <= anchorList[0][0]) return anchorList[0][1];
  for (let i = 1; i < anchorList.length; i++) {
    const [x1, y1] = anchorList[i];
    if (x <= x1) {
      const [x0, y0] = anchorList[i - 1];
      return y0 + (y1 - y0) * (x - x0) / (x1 - x0 || 1);
    }
  }
  return anchorList[anchorList.length - 1][1];
}

/**
 * Points for the plot: `{ m, ele, target, lat, lon }`, with `m` metres along
 * the course. Null when the course carries no elevation — without the hills
 * there is nothing for the target to be read against.
 */
export function courseProfile(coursePath, laps, metric) {
  if (!(coursePath?.length > 1) || !coursePath.some((p) => p[2] != null)) return null;
  const cum = [0];
  for (let i = 1; i < coursePath.length; i++) cum.push(cum[i - 1] + haversine(coursePath[i - 1], coursePath[i]));
  const courseM = cum[cum.length - 1];
  const field = metric ? METRICS[metric].field : null;
  const anchorList = field && laps?.length ? anchors(laps, field, courseM) : [];
  const step = Math.max(1, Math.ceil(coursePath.length / MAX_POINTS));
  const pts = [];
  for (let i = 0; i < coursePath.length; i += step) {
    const p = coursePath[i];
    if (p[2] == null) continue;
    pts.push({ m: cum[i], ele: p[2], target: interpolate(anchorList, cum[i]), lat: p[0], lon: p[1] });
  }
  // The finish is always drawn, whatever the sampling step skipped.
  const last = coursePath[coursePath.length - 1];
  if (pts[pts.length - 1]?.m !== courseM && last[2] != null) {
    pts.push({ m: courseM, ele: last[2], target: interpolate(anchorList, courseM), lat: last[0], lon: last[1] });
  }
  return pts.length > 1 ? pts : null;
}
