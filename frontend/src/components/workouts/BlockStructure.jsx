// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The structure of a workout or flow: rows, rest blocks, and Superset / Repeat
// containers. Shared by the Workouts and Flows tabs, which behave identically;
// the phone's builder page is the same design (mobile/.../ui/builder/BuilderPage.kt).
//
// `rows` is the editor's list from lib/blocks.js — a group is a head and an
// end marker with its members between — so every row, marker or not, is one
// sortable item, and a row dragged across a marker changes group. Dragging a
// head moves the whole group: its members are folded away for the drag so the
// one thing under the cursor is the one thing that moves. `renderRow(row,
// inGroup)` draws a workout's exercise or a flow's stretch.

import { useState } from "react";
import {
  DndContext, closestCenter, PointerSensor, useSensor, useSensors,
} from "@dnd-kit/core";
import { SortableContext, verticalListSortingStrategy, useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  enclosing, units, moveItem, moveUnit, addGroup, setGroup, removeGroup, remove, isHead, isEnd,
} from "../../lib/blocks";

function Stepper({ label, value, onMinus, onPlus }) {
  return (
    <div className="flex items-center gap-1 text-[11px] text-slate-600 dark:text-slate-400">
      <span>{label}</span>
      <button onClick={onMinus} className="px-1.5 rounded bg-slate-100 dark:bg-slate-700">−</button>
      <span className="font-semibold w-10 text-center">{value}</span>
      <button onClick={onPlus} className="px-1.5 rounded bg-slate-100 dark:bg-slate-700">+</button>
    </div>
  );
}

function SortableRow({ id, fixed, children }) {
  // An end marker is only a place to drop, never a thing to drag.
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id, disabled: fixed ? { draggable: true, droppable: false } : false });
  // Lifted: slightly larger and raised, so what is being moved is unmistakable.
  const t = transform && isDragging ? { ...transform, scaleX: 1.03, scaleY: 1.03 } : transform;
  return (
    <div ref={setNodeRef} {...attributes}
      style={{ transform: CSS.Transform.toString(t), transition, zIndex: isDragging ? 50 : undefined, position: "relative" }}
      className={isDragging ? "shadow-xl rounded-lg" : ""}>
      {children(listeners)}
    </div>
  );
}

const Handle = ({ listeners }) => (
  <button {...listeners} title="Drag to move"
    className="text-sm text-slate-400 hover:text-slate-600 cursor-grab active:cursor-grabbing select-none px-1 touch-none">⠿</button>
);

const CARD = "rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 p-2";
const TINT = "bg-accent-50 dark:bg-accent-950/40";
// The shared neutral pill (see components/ui/Button.jsx), small because the row
// is dense. Neutral and "Add …" like the phone builder's add row.
const pill = "btn btn-neutral btn-sm";

export default function BlockStructure({ rows, setRows, renderRow, restField, noun, restDefault = 60 }) {
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 3 } }));
  const [folded, setFolded] = useState(null); // uid of a group being dragged by its head
  const inside = enclosing(rows);
  const shown = rows.filter((r, i) => !folded || !((inside[i]?.uid === folded) || (isEnd(r) && r.group_uid === folded)));
  const fmt = (s) => s >= 60 ? `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}` : `${s}s`;
  const plural = noun.endsWith("ch") ? `${noun}es` : `${noun}s`;

  const onDragStart = ({ active }) => {
    const row = rows.find(r => r._id === active.id);
    if (row && isHead(row)) setFolded(row.group.uid);
  };
  const onDragEnd = ({ active, over }) => {
    setFolded(null);
    if (!over || active.id === over.id) return;
    const from = rows.findIndex(r => r._id === active.id);
    const to = rows.findIndex(r => r._id === over.id);
    if (isHead(rows[from])) {
      const us = units(rows);
      setRows(moveUnit(rows, us.findIndex(([a]) => a === from), us.findIndex(([a, b]) => a <= to && to <= b)));
    } else {
      setRows(moveItem(rows, from, to));
    }
  };
  const add = (row) => setRows([...rows, row]);

  return (
    <div className="space-y-2">
      {/* Every block is added the same way, at the end of the structure. */}
      <div className="flex flex-wrap gap-1.5">
        <button className={pill} onClick={() => add({ _id: `rest-${Date.now()}-${rows.length}`, item_kind: "rest", [restField]: restDefault })}>Add rest</button>
        <button className={pill} onClick={() => setRows(addGroup(rows, "superset"))}>Add superset</button>
        <button className={pill} onClick={() => setRows(addGroup(rows, "repeat"))}>Add repeat</button>
      </div>
      {/* The list on a card of its own, so it reads apart from the add
          buttons above; the rows are white on it. Same as the phone. */}
      <div className={CARD}>
      {rows.length === 0 ? (
        <p className="text-xs text-slate-400 py-3.5 px-1 text-center">No {plural} added yet</p>
      ) : (
        <DndContext sensors={sensors} collisionDetection={closestCenter}
          onDragStart={onDragStart} onDragEnd={onDragEnd} onDragCancel={() => setFolded(null)}>
          <SortableContext items={shown.map(r => r._id)} strategy={verticalListSortingStrategy}>
            <div>
              {shown.map((row) => {
                const i = rows.indexOf(row);
                const grouped = !!inside[i];
                return (
                  <SortableRow key={row._id} id={row._id} fixed={isEnd(row)}>
                    {(listeners) => {
                      if (isHead(row)) {
                        const g = row.group;
                        const superset = g.kind === "superset";
                        return (
                          <div className={`mt-1 rounded-t-xl ${TINT} px-2 pt-2 pb-1 flex items-center gap-2 flex-wrap`}>
                            <Handle listeners={listeners} />
                            <span className="text-xs font-semibold text-accent-700 dark:text-accent-400">{superset ? "Superset" : "Repeat"}</span>
                            <Stepper label={superset ? "Sets" : "Rounds"} value={`× ${g.rounds}`}
                              onMinus={() => setRows(setGroup(rows, { ...g, rounds: Math.max(1, g.rounds - 1) }))}
                              onPlus={() => setRows(setGroup(rows, { ...g, rounds: Math.min(20, g.rounds + 1) }))} />
                            <Stepper label="Rest" value={fmt(g.rest)}
                              onMinus={() => setRows(setGroup(rows, { ...g, rest: Math.max(0, g.rest - 15) }))}
                              onPlus={() => setRows(setGroup(rows, { ...g, rest: Math.min(900, g.rest + 15) }))} />
                            <button onClick={() => setRows(removeGroup(rows, g.uid))} title={`Remove ${superset ? "superset" : "repeat"} (keeps its ${plural})`}
                              className="ml-auto text-sm text-red-500 hover:text-red-700 px-1">✕</button>
                          </div>
                        );
                      }
                      if (isEnd(row)) {
                        const empty = i > 0 && isHead(rows[i - 1]);
                        return (
                          <div className={`mb-1 rounded-b-xl ${TINT} ${empty ? "py-3 text-center text-[11px] text-slate-500" : "h-2"}`}>
                            {empty && `Drag ${plural} here by ⠿`}
                          </div>
                        );
                      }
                      const body = row.item_kind === "rest" ? (() => {
                        const s = row[restField] || restDefault;
                        const setS = (v) => setRows(rows.map(r => r._id === row._id ? { ...r, [restField]: v } : r));
                        return (
                          <div className="flex items-center gap-2 rounded-lg border border-dashed border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-2 py-1.5">
                            <Handle listeners={listeners} />
                            <span className="text-xs font-medium text-slate-700 dark:text-slate-300 flex-1">Rest</span>
                            <Stepper label="" value={fmt(s)} onMinus={() => setS(Math.max(15, s - 15))} onPlus={() => setS(Math.min(900, s + 15))} />
                            <button onClick={() => setRows(remove(rows, row._id))} className="text-sm text-red-500 px-1">✕</button>
                          </div>
                        );
                      })() : (
                        <div className="flex items-start gap-1">
                          <div className="pt-2"><Handle listeners={listeners} /></div>
                          <div className="flex-1">{renderRow(row, grouped)}</div>
                        </div>
                      );
                      return <div className={grouped ? `${TINT} px-2 py-1` : "py-1"}>{body}</div>;
                    }}
                  </SortableRow>
                );
              })}
            </div>
          </SortableContext>
        </DndContext>
      )}
      </div>
    </div>
  );
}
