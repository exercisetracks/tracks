// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The same cases as the phone's BuilderBlocksTest: the two editors must agree.
import { describe, it, expect } from "vitest";
import { toEditor, fromEditor, moveItem, moveUnit, units, addGroup, setGroup, removeGroup, remove } from "./blocks";

const g = { group_uid: "g", group_kind: "repeat", group_rounds: 3, group_rest_seconds: 60 };
const s = { group_uid: "s", group_kind: "superset", group_rounds: 3, group_rest_seconds: 90 };
/** Rows as short strings: rows by id, markers as `[g` and `g]`. */
const shape = (rows) => rows.map(r => r.item_kind === "group_head" ? `[${r.group.uid}`
  : r.item_kind === "group_end" ? `${r.group_uid}]` : r._id);
const uids = (rows) => rows.map(r => r.group_uid ?? null);

describe("blocks", () => {
  it("opens a group wrapped in its markers and folds it back unchanged", () => {
    const rows = [{ _id: "a" }, { _id: "b", ...g }, { _id: "c", ...g }, { _id: "d" }];
    const ed = toEditor(rows);
    expect(shape(ed)).toEqual(["a", "[g", "b", "c", "g]", "d"]);
    expect(uids(fromEditor(ed))).toEqual([null, "g", "g", null]);
    expect(fromEditor(ed)[1].group_rounds).toBe(3);
  });

  it("puts a row dragged past a group's head into that group — membership is where it drops", () => {
    const ed = toEditor([{ _id: "a" }, { _id: "b", ...g }, { _id: "c" }]);
    const moved = moveItem(ed, 0, 1);
    expect(shape(moved)).toEqual(["[g", "a", "b", "g]", "c"]);
    expect(uids(fromEditor(moved))).toEqual(["g", "g", null]);
  });

  it("takes a row dragged past a group's end out of it", () => {
    const ed = toEditor([{ _id: "a", ...g }, { _id: "b", ...g }, { _id: "c" }]);
    const moved = moveItem(ed, 2, 3);
    expect(uids(fromEditor(moved))).toEqual(["g", null, null]);
  });

  it("keeps an empty group as a drop target, and drops it on save", () => {
    const ed = addGroup(toEditor([{ _id: "a" }]), "superset", "n");
    expect(shape(ed)).toEqual(["a", "[n", "n]"]);
    expect(fromEditor(ed)).toHaveLength(1);
    const moved = moveItem(ed, 0, 1);
    expect(uids(fromEditor(moved))).toEqual(["n"]);
    expect(shape(remove(moved, "a"))).toEqual(["[n", "n]"]);
  });

  it("moves a group whole, past another group whole", () => {
    const ed = toEditor([{ _id: "a", ...g }, { _id: "b", ...g }, { _id: "c", ...s }, { _id: "d" }]);
    expect(units(ed)).toEqual([[0, 3], [4, 6], [7, 7]]);
    expect(shape(moveUnit(ed, 0, 1))).toEqual(["[s", "c", "s]", "[g", "a", "b", "g]", "d"]);
  });

  it("hops a full superset rather than entering it", () => {
    const ed = toEditor([{ _id: "x" }, { _id: "a", ...s }, { _id: "b", ...s }, { _id: "c", ...s }, { _id: "y" }]);
    expect(shape(moveItem(ed, 0, 1))).toEqual(["[s", "a", "b", "c", "s]", "x", "y"]);
    expect(shape(moveItem(ed, 6, 5))).toEqual(["x", "y", "[s", "a", "b", "c", "s]"]);
  });

  it("keeps a removed group's members, loose, in place", () => {
    const ed = removeGroup(toEditor([{ _id: "a", ...g }, { _id: "b", ...g }]), "g");
    expect(uids(fromEditor(ed))).toEqual([null, null]);
  });

  it("writes a group's new rounds to every member on save", () => {
    const ed = toEditor([{ _id: "a", ...g }, { _id: "b", ...g }]);
    const out = fromEditor(setGroup(ed, { uid: "g", kind: "repeat", rounds: 5, rest: 60 }));
    expect(out.map(r => r.group_rounds)).toEqual([5, 5]);
  });

  it("refuses to make a group of four a superset", () => {
    const ed = toEditor(["a", "b", "c", "d"].map(_id => ({ _id, ...g })));
    expect(setGroup(ed, { uid: "g", kind: "superset", rounds: 3, rest: 60 })).toBe(ed);
  });
});
