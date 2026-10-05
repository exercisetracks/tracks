// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * setDataHelpers.js
 * ------------------------------------------------------------------------
 * Pure, React-free data-transform helpers for the Strength activity layout.
 *
 * A strength activity is stored as a flat, time-ordered list of "sets", where
 * each set is either an `active` set (a working set: weight/reps/duration) or a
 * `rest` set (recovery between working sets). Every function here takes that raw
 * `sets` array (optionally paired with the GPS/HR `track`) and reshapes it into
 * the specific structure a downstream chart, card, or timeline component wants.
 *
 * These were extracted verbatim from StrengthLayout.jsx so the layout component
 * stays focused on render/state wiring. They are:
 *   - Strength-specific (sets/reps/exercise domain), so they live under
 *     `strength/` rather than the generic `shared/` folder.
 *   - Pure and side-effect free: same input -> same output, safe to call inside
 *     render or a useMemo without any dependency surprises.
 *
 * NOTE: behaviour is intentionally identical to the pre-refactor inline
 * versions — do not "improve" the maths here without matching the charts.
 * ------------------------------------------------------------------------
 */

// A raw set's duration defaults to 30s when the device didn't record one. Kept
// as a single constant so every elapsed-time accumulator below stays consistent.
const DEFAULT_SET_SECONDS = 30;

/**
 * Group the flat set list into contiguous "exercise" blocks for the per-set log.
 *
 * Walks the sets in order and starts a new group whenever the resolved exercise
 * name changes. Rest sets are attached to the current group so the log can show
 * the recovery that followed each movement.
 *
 * Name resolution priority: explicit `exercise_name` -> humanised
 * `exercise_category` -> an auto-numbered "Movement N" fallback for
 * unclassified runs (numbering only advances per contiguous unknown block, so a
 * single unclassified movement keeps one label). Muscle attribution for those
 * unknowns comes from the body diagram, not this name.
 *
 * @param {Array} sets - raw ordered set list
 * @returns {Array<{name,category,_unknown,sets,activeSets,maxWeight}>}
 */
export function groupSetsByExercise(sets) {
  const groups = [];
  let currentGroup = null;
  let unknownIdx = 0;

  // A field is "useful" as a name only if it's non-empty, not a bare numeric id,
  // and not the literal placeholder "unknown".
  const isUseful = (v) => v && !/^\d+$/.test(String(v)) && String(v).toLowerCase() !== "unknown";

  for (const set of sets) {
    if (set.set_type === "active") {
      let exerciseName;
      if (isUseful(set.exercise_name)) {
        exerciseName = set.exercise_name;
      } else if (isUseful(set.exercise_category)) {
        exerciseName = String(set.exercise_category).replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
      } else {
        // Unclassified movement — give each contiguous run its own label so
        // the per-set log stays readable. Muscle attribution comes from the
        // body diagram, not from this name.
        if (!currentGroup || currentGroup._unknown !== true) {
          unknownIdx += 1;
        }
        exerciseName = `Movement ${unknownIdx}`;
      }
      const category = isUseful(set.exercise_category) ? set.exercise_category : "general";

      const isUnknown = exerciseName.startsWith("Movement ");
      if (currentGroup && currentGroup.name === exerciseName) {
        currentGroup.sets.push(set);
        if (set.weight_kg && set.weight_kg > currentGroup.maxWeight) {
          currentGroup.maxWeight = set.weight_kg;
        }
        currentGroup.activeSets.push(set);
      } else {
        currentGroup = {
          name: exerciseName,
          category,
          _unknown: isUnknown,
          sets: [set],
          activeSets: [set],
          maxWeight: set.weight_kg || 0,
        };
        groups.push(currentGroup);
      }
    } else if (set.set_type === "rest" && currentGroup) {
      currentGroup.sets.push(set);
    }
  }

  return groups;
}

/**
 * Reshape sets into the split shape the ActivityTimeline strip expects
 * (duration + type + peak HR per segment).
 */
export function formatSetsForTimeline(sets) {
  return sets.map((set) => ({
    duration_seconds: set.duration_seconds || DEFAULT_SET_SECONDS,
    split_type: set.set_type,
    max_heart_rate: set.heart_rate,
  }));
}

/**
 * Per-active-set weight series vs cumulative elapsed time.
 * @returns {Array<{elapsed,weight,setIndex}>}
 */
export function buildWeightData(sets) {
  let elapsed = 0;
  return sets
    .filter((s) => s.set_type === "active")
    .map((set, i) => {
      const data = { elapsed, weight: set.weight_kg || 0, setIndex: i + 1 };
      elapsed += (set.duration_seconds || DEFAULT_SET_SECONDS);
      return data;
    });
}

/**
 * Per-active-set training volume (weight × reps) vs cumulative elapsed time.
 * @returns {Array<{elapsed,volume,setIndex}>}
 */
export function buildVolumeData(sets) {
  let elapsed = 0;
  return sets
    .filter((s) => s.set_type === "active")
    .map((set, i) => {
      const volume = (set.weight_kg || 0) * (set.repetitions || 0);
      const data = { elapsed, volume, setIndex: i + 1 };
      elapsed += (set.duration_seconds || DEFAULT_SET_SECONDS);
      return data;
    });
}

/**
 * Per-active-set rep count vs cumulative elapsed time.
 * @returns {Array<{elapsed,reps,setIndex}>}
 */
export function buildRepData(sets) {
  let elapsed = 0;
  return sets
    .filter((s) => s.set_type === "active")
    .map((set, i) => {
      const data = { elapsed, reps: set.repetitions || 0, setIndex: i + 1 };
      elapsed += (set.duration_seconds || DEFAULT_SET_SECONDS);
      return data;
    });
}

/**
 * Per-active-set average heart rate, sampled from the GPS/HR track.
 *
 * Sets carry no per-set HR, so for each active set we average the track HR
 * points whose timestamp falls within [start_time, start_time + duration]. If no
 * points ever overlap (e.g. clock skew between device and track), we fall back
 * to a rolling sample spread evenly across the track so the chart isn't empty.
 * Sets that still resolve to 0 HR are dropped.
 *
 * @param {Array} sets  - raw ordered set list
 * @param {Array} track - GPS/HR track points (may be null/empty)
 * @returns {Array<{elapsed,heart_rate,setIndex}>}
 */
export function buildHRData(sets, track) {
  // Sets don't carry per-set HR; sample it from the track using start_time + duration.
  const points = (track || []).filter((p) => p && p.heart_rate != null && p.timestamp);
  const out = [];
  let elapsed = 0;
  for (const set of sets) {
    if (set.set_type !== "active") {
      elapsed += (set.duration_seconds || DEFAULT_SET_SECONDS);
      continue;
    }
    let hr = 0;
    if (points.length && set.start_time) {
      const start = new Date(set.start_time).getTime();
      const end = start + (set.duration_seconds || DEFAULT_SET_SECONDS) * 1000;
      let sum = 0, n = 0;
      for (const p of points) {
        const t = new Date(p.timestamp).getTime();
        if (t >= start && t <= end) { sum += p.heart_rate; n++; }
      }
      if (n > 0) hr = Math.round(sum / n);
    }
    out.push({ elapsed, heart_rate: hr, setIndex: out.length + 1 });
    elapsed += (set.duration_seconds || DEFAULT_SET_SECONDS);
  }
  // If no overlap matched (e.g. clock skew), fall back to a rolling sample from the track.
  if (out.every((d) => d.heart_rate === 0) && points.length) {
    const activeSets = out.length;
    if (activeSets > 0) {
      const step = Math.max(1, Math.floor(points.length / activeSets));
      for (let i = 0; i < out.length; i++) {
        const p = points[Math.min(points.length - 1, i * step)];
        out[i].heart_rate = Math.round(p.heart_rate);
      }
    }
  }
  return out.filter((d) => d.heart_rate > 0);
}

/**
 * Collect the rest gaps between consecutive active sets, in seconds.
 *
 * Preferred source is the time between the two sets' `start_time`s; when those
 * are missing it falls back to the later set's own duration. Only positive gaps
 * are kept. Shared by both the histogram and the average-rest stat below so the
 * two never disagree.
 *
 * @returns {number[]} rest durations in seconds
 */
function collectRestTimes(sets) {
  const restTimes = [];
  for (let i = 1; i < sets.length; i++) {
    if (sets[i].set_type === "active" && sets[i - 1].set_type === "active") {
      const restDuration = (sets[i].start_time && sets[i - 1].start_time)
        ? (new Date(sets[i].start_time) - new Date(sets[i - 1].start_time)) / 1000
        : (sets[i].duration_seconds || 0);
      if (restDuration > 0) {
        restTimes.push(restDuration);
      }
    }
  }
  return restTimes;
}

/**
 * Bucket rest durations into fixed ranges for the RestHistogram.
 * Empty buckets are dropped so the chart only shows ranges that occurred.
 * @returns {Array<{range,min,max,count}>}
 */
export function buildRestHistogramData(sets) {
  const restTimes = collectRestTimes(sets);
  if (restTimes.length === 0) return [];

  const bins = [
    { range: "0–30s", min: 0, max: 30, count: 0 },
    { range: "30–60s", min: 30, max: 60, count: 0 },
    { range: "1–2m", min: 60, max: 120, count: 0 },
    { range: "2–3m", min: 120, max: 180, count: 0 },
    { range: "3m+", min: 180, max: Infinity, count: 0 },
  ];

  for (const rest of restTimes) {
    for (const bin of bins) {
      if (rest >= bin.min && rest < bin.max) {
        bin.count++;
        break;
      }
    }
  }

  return bins.filter((b) => b.count > 0);
}

/** Mean rest duration (seconds) between active sets, or 0 when there are none. */
export function calculateAvgRestTime(sets) {
  const restTimes = collectRestTimes(sets);
  return restTimes.length > 0 ? restTimes.reduce((a, b) => a + b, 0) / restTimes.length : 0;
}
