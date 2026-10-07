// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// A saved workout, saved flow or planned session, turned into what the guided
// players run — the web's copy of the phone's SessionLogic.plan and FlowPlan
// (mobile/.../ui/strength/SessionLogic.kt, ui/flexibility/FlowPlan.kt), so a
// template runs the same at a desk as on a phone.
//
// The players know nothing about blocks. Storage repeats a group's uid, kind,
// rounds and rest on every member, and consecutive rows sharing a group_uid
// form the block (see lib/blocks.js); a rest block is a row of its own. So
// blocks are resolved here:
//
//   Strength: a group member gets one set per round, and afterSetDone walks
//   the round from member to member. A rest block lengthens the rest after
//   the exercise before it.
//   Flows: blocks are unrolled, one hold per member per round, because a
//   timed sequence has no "where next" to decide — it plays in order.
//
// Pure functions, so they are tested without React (test/SessionPlan.test.js).

const MAX_SETS = 12;
const MAX_FLOW_SETS = 6;
// The phone's defaults (FlexibilityViewModel), for rows that do not say.
const DEFAULT_HOLD_SECONDS = 30;
const DEFAULT_FLOW_REST_SECONDS = 10;
const DEFAULT_REST_SECONDS = 90;

const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));

const groupOf = (row) => row.group_uid
  ? {
      uid: row.group_uid,
      kind: row.group_kind || "repeat",
      rounds: clamp(row.group_rounds || 1, 1, MAX_SETS),
      rest: row.group_rest_seconds || 0,
    }
  : null;

const isRestRow = (row) => row.item_kind === "rest" || row.type === "rest";

const byOrder = (rows) => [...(rows || [])].sort((a, b) => (a.order_index ?? 0) - (b.order_index ?? 0));

// ── Strength ────────────────────────────────────────────────────────────────

/**
 * The starting weight for one exercise of a saved template, in kg.
 *
 * The template's own prescription comes first — it is what the lifter wrote
 * down — then the last weight lifted, which is what the phone opens on. A
 * percentage with no e1RM yet has nothing to be a percentage of, so it falls
 * back to last time too. Rounded to half a kilo: 0.75 × an estimate is
 * otherwise a weight no rack holds.
 */
export function prescribedWeightKg(row, standing) {
  const last = standing?.last_weight_kg || 0;
  if (row.weight_method === "fixed" && row.weight_value > 0) return row.weight_value;
  if (row.weight_method === "percentage_e1rm" && row.weight_value > 0 && standing?.estimated_1rm_kg) {
    return Math.round(standing.estimated_1rm_kg * row.weight_value * 2) / 2;
  }
  return last;
}

/**
 * A saved workout's rows (or a planned session's steps) as runner exercises.
 *
 * Each: { name, sets, reps, weight_kg, rest_seconds, rest_after_seconds,
 * target_rpe, group, cues, primary_muscles }. `library` maps exercise name →
 * library row, for cues and muscles; `progress` maps name → the
 * /strength/progress entry. A name the library does not have is skipped when
 * `library` is given, as on the phone: a custom exercise can be deleted out
 * from under a template, and logging it would bring back a strength record
 * for an exercise that no longer exists.
 */
export function planStrength(rows, { library = null, progress = {} } = {}) {
  const out = [];
  for (const row of byOrder(rows)) {
    if (isRestRow(row)) {
      const last = out[out.length - 1];
      if (last) last.rest_after_seconds += row.rest_seconds || row.duration_seconds || 0;
      continue;
    }
    // Saved workouts call it exercise_name and target_*; planned steps, name.
    const name = row.exercise_name || row.name;
    if (!name) continue;
    if (row.type && row.type !== "strength_exercise") continue;
    const lib = library ? library[name] : null;
    if (library && !lib) continue;
    const group = groupOf(row);
    const standing = progress[name];
    out.push({
      name,
      sets: clamp(group ? group.rounds : (row.target_sets ?? row.sets ?? 3), 1, MAX_SETS),
      reps: row.target_reps ?? row.reps ?? lib?.default_reps ?? 8,
      weight_kg: row.weight_kg ?? prescribedWeightKg(row, standing),
      rest_seconds: row.rest_seconds ?? DEFAULT_REST_SECONDS,
      rest_after_seconds: 0,
      target_rpe: row.target_rpe ?? null,
      group,
      cues: row.cues || lib?.cues || [],
      primary_muscles: row.primary_muscles || lib?.primary_muscles || [],
    });
  }
  return out;
}

/**
 * After set `setIndex` of exercise `index` is ticked: where to go, and how
 * long to rest first. `done(i, s)` says whether set s of exercise i is done.
 *
 * On its own: rest between sets, and after the last set any rest block that
 * follows. In a circuit or superset: straight on to the next member at the
 * same round with no rest; after the round's last member, the group's rest
 * and back to the first member. The phone's SessionLogic.afterSetDone.
 */
export function afterSetDone(exercises, index, setIndex, done) {
  const ex = exercises[index];
  if (!ex) return { moveTo: null, rest: 0 };
  const g = ex.group;
  if (!g) {
    const last = setIndex === ex.sets - 1;
    return { moveTo: null, rest: last ? ex.rest_after_seconds : ex.rest_seconds };
  }
  let start = index;
  while (start > 0 && exercises[start - 1].group?.uid === g.uid) start--;
  let end = index;
  while (end < exercises.length - 1 && exercises[end + 1].group?.uid === g.uid) end++;
  for (let i = index + 1; i <= end; i++) {
    if (setIndex < exercises[i].sets && !done(i, setIndex)) return { moveTo: i, rest: 0 };
  }
  let lastRound = 0;
  for (let i = start; i <= end; i++) lastRound = Math.max(lastRound, exercises[i].sets - 1);
  return setIndex >= lastRound
    ? { moveTo: null, rest: exercises[end].rest_after_seconds }
    : { moveTo: start, rest: g.rest };
}

// ── Flows ───────────────────────────────────────────────────────────────────

/**
 * A saved flow's stretches as `mobility_exercise` steps for the flow player,
 * blocks unrolled. `library` maps stretch name → library row.
 *
 * Each step carries `rest_seconds` (after every hold of it, both sides
 * included, as on the phone) and `rest_after_seconds` (once more after its
 * last hold: a rest block, or a group's rest at the end of a round). Inside
 * a group the next member follows at once.
 */
export function planFlow(stretches, library = {}) {
  const out = [];
  const restAfterLast = (seconds) => {
    const last = out[out.length - 1];
    if (last && seconds > 0) last.rest_after_seconds += seconds;
  };
  const hold = (entry, sets, inGroup) => {
    const name = entry.exercise_name;
    if (!name) return;
    const s = library[name] || {};
    out.push({
      type: "mobility_exercise",
      name,
      duration_seconds: entry.duration_seconds || s.duration_per_side_sec || DEFAULT_HOLD_SECONDS,
      sets,
      each_side: !!s.each_side,
      rest_seconds: inGroup ? 0 : (entry.rest_seconds ?? DEFAULT_FLOW_REST_SECONDS),
      rest_after_seconds: 0,
      muscles: s.primary_muscles || [],
      cues: s.cues || [],
      breath_cue: s.breath_cue || null,
      position: s.position || null,
    });
  };

  const rows = byOrder(stretches);
  for (let i = 0; i < rows.length;) {
    const g = groupOf(rows[i]);
    if (!g) {
      const row = rows[i++];
      if (isRestRow(row)) restAfterLast(row.duration_seconds || 0);
      else hold(row, clamp(row.sets ?? library[row.exercise_name]?.sets ?? 1, 1, MAX_FLOW_SETS), false);
      continue;
    }
    const members = [];
    while (i < rows.length && rows[i].group_uid === g.uid) members.push(rows[i++]);
    if (!members.some((m) => !isRestRow(m))) continue;
    for (let r = 0; r < clamp(g.rounds, 1, MAX_FLOW_SETS); r++) {
      for (const m of members) {
        if (isRestRow(m)) restAfterLast(m.duration_seconds || 0);
        else hold(m, 1, true);
      }
      restAfterLast(g.rest);
    }
  }
  return out;
}
