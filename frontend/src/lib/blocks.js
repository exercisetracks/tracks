// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Editing rules for a workout's or flow's blocks — the same rules as the
// phone's BuilderBlocks (mobile/.../ui/builder/BuilderBlocks.kt), so a
// structure edited on either reads the same on the other.
//
// Stored, a repeat group or superset is not a row: its uid, kind, rounds and
// rest are repeated on every member (spec/sync.yaml, workout_exercise), and
// CONSECUTIVE rows sharing group_uid form the block. That does not change.
//
// Edited, Superset and Repeat are containers the user adds like a Rest block
// and drags rows into and out of. So the editor's list carries two marker
// rows per group — a head (holding the group's settings) and an end — and a
// row belongs to the group whose markers it sits between: membership is where
// the drop lands, with no separate "join" action to keep in step. toEditor /
// fromEditor convert at load and save; an empty group, which storage cannot
// hold, exists only in the editor as a drop target and vanishes on save.
// Rows carry a client-only `_id`.

export const SUPERSET_MAX = 3;

const HEAD = "group_head";
const END = "group_end";

export const isHead = (row) => row.item_kind === HEAD;
export const isEnd = (row) => row.item_kind === END;
export const isMarker = (row) => isHead(row) || isEnd(row);

const newUid = () => (globalThis.crypto?.randomUUID?.() ?? `g${Date.now()}${Math.random()}`);

const head = (g) => ({ _id: `head:${g.uid}`, item_kind: HEAD, group: g });
const end = (uid) => ({ _id: `end:${uid}`, item_kind: END, group_uid: uid });

const groupOf = (row) => row.group_uid
  ? { uid: row.group_uid, kind: row.group_kind || "repeat", rounds: row.group_rounds || 1, rest: row.group_rest_seconds || 0 }
  : null;

const withGroup = (row, g) => ({
  ...row,
  group_uid: g ? g.uid : null,
  group_kind: g ? g.kind : null,
  group_rounds: g ? g.rounds : null,
  group_rest_seconds: g ? g.rest : null,
});

/** Stored rows → the editor's list: each run of a group wrapped in its markers. */
export function toEditor(rows) {
  const out = [];
  let open = null;
  for (const row of rows) {
    const g = groupOf(row);
    if (open && (!g || g.uid !== open)) { out.push(end(open)); open = null; }
    if (g && open !== g.uid) { out.push(head(g)); open = g.uid; }
    out.push(row);
  }
  if (open) out.push(end(open));
  return out;
}

/** The editor's list → stored rows, each taking the group of the markers around it. */
export function fromEditor(rows) {
  const out = [];
  let open = null;
  for (const row of rows) {
    if (isHead(row)) open = row.group;
    else if (isEnd(row)) open = null;
    else out.push(withGroup(row, open));
  }
  return out;
}

/** For each row, the group it is inside (null for loose rows and the markers). */
export function enclosing(rows) {
  let open = null;
  return rows.map(row => {
    if (isHead(row)) { open = row.group; return null; }
    if (isEnd(row)) { open = null; return null; }
    return open;
  });
}

/** Units a group drag moves past: a loose row, or a group head..end, as [first, last] indices. */
export function units(rows) {
  const out = [];
  for (let i = 0; i < rows.length; i++) {
    if (isHead(rows[i])) {
      const uid = rows[i].group.uid;
      let j = i;
      while (j < rows.length && !(isEnd(rows[j]) && rows[j].group_uid === uid)) j++;
      j = Math.min(j, rows.length - 1);
      out.push([i, j]);
      i = j;
    } else out.push([i, i]);
  }
  return out;
}

const members = (rows, uid) => enclosing(rows).filter(g => g?.uid === uid).length;

/**
 * Move a row from `from` to `to` (remove, then insert). Crossing a marker is
 * what moves it into or out of a group; a full superset is hopped over whole
 * instead of entered.
 */
export function moveItem(rows, from, to) {
  if (from === to || from < 0 || to < 0 || from >= rows.length || to >= rows.length) return rows;
  if (isMarker(rows[from])) return rows;
  const out = [...rows];
  const [row] = out.splice(from, 1);
  out.splice(to, 0, row);
  const g = enclosing(out)[to];
  if (!g || g.kind !== "superset" || members(out, g.uid) <= SUPERSET_MAX) return out;
  out.splice(to, 1);
  const [first, last] = units(out).find(([a]) => isHead(out[a]) && out[a].group.uid === g.uid);
  out.splice(to > from ? last + 1 : first, 0, row);
  return out;
}

/** Move whole unit `from` to position `to` (unit indices). */
export function moveUnit(rows, from, to) {
  const us = units(rows).map(([a, b]) => rows.slice(a, b + 1));
  if (from === to || from < 0 || to < 0 || from >= us.length || to >= us.length) return rows;
  const [u] = us.splice(from, 1);
  us.splice(to, 0, u);
  return us.flat();
}

const DEFAULTS = { superset: { rounds: 3, rest: 90 }, repeat: { rounds: 3, rest: 60 } };

/** A new, empty group at the end, ready to have rows dragged into it. */
export const addGroup = (rows, kind, uid = newUid()) => {
  const g = { uid, kind, ...DEFAULTS[kind] };
  return [...rows, head(g), end(uid)];
};

/** Change a group's kind, rounds or rest; a superset refuses more than SUPERSET_MAX. */
export function setGroup(rows, g) {
  if (g.kind === "superset" && members(rows, g.uid) > SUPERSET_MAX) return rows;
  return rows.map(r => isHead(r) && r.group.uid === g.uid ? { ...r, group: g } : r);
}

/**
 * Remove a group's container; its members stay in place, loose. Losing a
 * whole superset to one click is the harder mistake to undo.
 */
export const removeGroup = (rows, uid) =>
  rows.filter(r => !((isHead(r) && r.group.uid === uid) || (isEnd(r) && r.group_uid === uid)));

/** Remove one row. Its group, even left empty, stays as a drop target. */
export const remove = (rows, id) => rows.filter(r => r._id !== id || isMarker(r));
