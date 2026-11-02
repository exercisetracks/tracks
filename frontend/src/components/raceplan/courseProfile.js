// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The race's profile: the plan's target by distance — with the course's
// elevation behind it when a course is loaded, so the plot shows how the
// target answers the hills — plus the HR ceiling where the plan sets one.
// It replaced the lap table, which ran to forty rows on a marathon and was
// read far less than a picture of the same numbers. Pure maths, no React, and the phone's twin is
// mobile/core/.../race/CourseProfile.kt — the two are kept to the same
// algorithm and the same test numbers.
//
// Which target is drawn is the sport's own coaching number: pace for running,
// power for cycling (and the bike laps of a triathlon), the HR ceiling for
// mountain biking, whose laps carry no watts; a triathlon's swim leg is drawn
// per 100 m. A swim race on its own has no plot: flat water and a single lap.
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
  swim:  { field: "target_sec_per_km" },   // drawn per 100 m
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

/** Without a course: enough evenly spaced points for a smooth line and a readout anywhere. */
const LAP_SAMPLES = 200;

/** The lap at each of `positions`, laps laid end to end over `length` metres. */
function lapsAt(laps, positions, length) {
  const total = laps.reduce((s, l) => s + (l.distance_m || 0), 0) || 1;
  const ends = [];
  let run = 0;
  for (const l of laps) { run += (l.distance_m || 0) / total * length; ends.push(run); }
  let k = 0;
  return positions.map((m) => {
    while (k < laps.length - 1 && m > ends[k]) k++;
    return laps[k];
  });
}

/**
 * Points for the plot: `{ m, ele, target, hr, lap, lat, lon }`, `m` metres
 * along the race and `lap` the lap there (for the readout's ground and
 * gradient). With a course carrying elevation, one point per course point;
 * without, the laps alone, evenly sampled, with no elevation. Null when there
 * is neither a course nor a lap.
 */
export function courseProfile(coursePath, laps, metric) {
  const field = metric ? METRICS[metric].field : null;
  const hasLaps = laps?.length > 0;
  const withHr = hasLaps && metric !== "hr" && laps.some((l) => l.hr_ceiling != null);
  let positions, eles = null, coords = null, length;
  if (coursePath?.length > 1 && coursePath.some((p) => p[2] != null)) {
    const cum = [0];
    for (let i = 1; i < coursePath.length; i++) cum.push(cum[i - 1] + haversine(coursePath[i - 1], coursePath[i]));
    length = cum[cum.length - 1];
    const step = Math.max(1, Math.ceil(coursePath.length / MAX_POINTS));
    const idx = [];
    for (let i = 0; i < coursePath.length; i += step) if (coursePath[i][2] != null) idx.push(i);
    // The finish is always drawn, whatever the sampling step skipped.
    const last = coursePath.length - 1;
    if (idx[idx.length - 1] !== last && coursePath[last][2] != null) idx.push(last);
    positions = idx.map((i) => cum[i]);
    eles = idx.map((i) => coursePath[i][2]);
    coords = idx.map((i) => [coursePath[i][0], coursePath[i][1]]);
  } else if (hasLaps) {
    length = laps.reduce((s, l) => s + (l.distance_m || 0), 0);
    if (!length) return null;
    positions = Array.from({ length: LAP_SAMPLES + 1 }, (_, i) => (i / LAP_SAMPLES) * length);
  } else {
    return null;
  }
  const targets = field && hasLaps ? anchors(laps, field, length) : [];
  const hrs = withHr ? anchors(laps, "hr_ceiling", length) : [];
  const at = hasLaps ? lapsAt(laps, positions, length) : positions.map(() => null);
  const pts = positions.map((m, i) => ({
    m, ele: eles ? eles[i] : null,
    target: interpolate(targets, m), hr: withHr ? interpolate(hrs, m) : null,
    lap: at[i], lat: coords ? coords[i][0] : null, lon: coords ? coords[i][1] : null,
  }));
  return pts.length > 1 ? pts : null;
}
