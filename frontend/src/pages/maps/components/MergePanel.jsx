// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState } from "react";
import {
  DndContext, PointerSensor, useSensor, useSensors, closestCenter,
} from "@dnd-kit/core";
import {
  SortableContext, useSortable, arrayMove, verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

// Docked (bottom-left) merge panel — a sibling of the Legend/Layers popups so the
// map and the tracks being merged stay fully visible. Sources are added by
// ctrl/⌘-clicking (or plain-clicking while merge mode is on) tracks/activities on
// the map; here they're reordered by drag (@dnd-kit), reversed, removed, then
// merged into one new Custom Track (+ optional non-contributing trip activity).

const KIND_BADGE = {
  track:    ["Track",    "bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300"],
  activity: ["Activity", "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300"],
};

function fmtDist(m, imperial) {
  if (m == null) return null;
  return imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`;
}

function SortableRow({ s, i, imperial, onReverse, onRemove }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: s.key });
  const badge = KIND_BADGE[s.kind];
  const dist = fmtDist(s.dist, imperial);
  return (
    <div ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={`flex items-center gap-1.5 px-1.5 py-1 rounded-lg bg-slate-50 dark:bg-slate-800 ${
        isDragging ? "opacity-60 shadow-lg ring-1 ring-accent-300 dark:ring-accent-700" : ""}`}>
      <button {...listeners} {...attributes} title="Drag to reorder"
        className="shrink-0 text-slate-300 dark:text-slate-600 hover:text-slate-500 cursor-grab active:cursor-grabbing touch-none">
        <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24">
          <circle cx="8" cy="6" r="1.6" /><circle cx="8" cy="12" r="1.6" /><circle cx="8" cy="18" r="1.6" />
          <circle cx="15" cy="6" r="1.6" /><circle cx="15" cy="12" r="1.6" /><circle cx="15" cy="18" r="1.6" />
        </svg>
      </button>
      <span className="text-[10px] font-mono text-slate-400 w-3 text-right">{i + 1}</span>
      <span className={`shrink-0 px-1 py-0.5 rounded text-[9px] font-semibold ${badge[1]}`}>{badge[0]}</span>
      <div className="min-w-0 flex-1">
        <div className="text-xs text-slate-700 dark:text-slate-200 truncate">{s.name || "Untitled"}</div>
        {dist && <div className="text-[10px] text-slate-400">{dist}</div>}
      </div>
      <button onClick={() => onReverse(s.key)} title={s.reverse ? "Reversed — click to undo" : "Reverse this segment"}
        className={`shrink-0 text-[10px] font-semibold px-1 py-0.5 rounded ${
          s.reverse ? "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300" : "text-slate-400 hover:text-slate-600"}`}>
        ⇄
      </button>
      <button onClick={() => onRemove(s.key)} title="Remove" className="icon-btn icon-btn-sm icon-btn-danger shrink-0">×</button>
    </div>
  );
}

export default function MergePanel({
  sources, imperial, busy, error, onReorder, onReverse, onRemove, onClear, onMerge, onClose,
}) {
  const [name, setName] = useState("");
  const [createActivity, setCreateActivity] = useState(true);
  const [hideSources, setHideSources] = useState(true);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));

  const onDragEnd = (e) => {
    const { active, over } = e;
    if (!over || active.id === over.id) return;
    const from = sources.findIndex((s) => s.key === active.id);
    const to = sources.findIndex((s) => s.key === over.id);
    if (from !== -1 && to !== -1) onReorder(arrayMove(sources, from, to));
  };

  const merge = () => onMerge({
    name: name.trim() || "Merged Track",
    create_activity: createActivity,
    hide_sources: hideSources,
    sources: sources.map((s) => ({ kind: s.kind, id: s.id, reverse: s.reverse })),
  });

  return (
    <div className="absolute bottom-4 left-16 z-40 w-[300px] max-w-[calc(100vw-5rem)] flex flex-col max-h-[72vh]
                    bg-white dark:bg-slate-900 rounded-xl shadow-xl border border-slate-200 dark:border-slate-700">
      {/* Header */}
      <div className="flex items-center justify-between px-2.5 py-1.5 border-b border-slate-100 dark:border-slate-800">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">
          Merge tracks {sources.length > 0 && <span className="text-accent-600 dark:text-accent-400">({sources.length})</span>}
        </h3>
        <button onClick={onClose} className="icon-btn">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>
        </button>
      </div>

      {error && <div className="px-2.5 py-1 text-[11px] text-rose-600 bg-rose-50 dark:bg-rose-900/20">{error}</div>}

      {/* Source list (drag to reorder) */}
      <div className="overflow-y-auto p-1.5 flex-1 min-h-[60px]">
        {sources.length === 0 ? (
          <p className="text-[11px] text-slate-400 px-1.5 py-3.5 leading-relaxed text-center">
            <span className="font-medium text-slate-500 dark:text-slate-300">Ctrl/⌘-click tracks on the map</span> to add
            them here (plain click works while this panel is open). Turn on the <em>Activity Tracks</em> layer to merge
            recorded activities too.
          </p>
        ) : (
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
            <SortableContext items={sources.map((s) => s.key)} strategy={verticalListSortingStrategy}>
              <div className="space-y-1">
                {sources.map((s, i) => (
                  <SortableRow key={s.key} s={s} i={i} imperial={imperial} onReverse={onReverse} onRemove={onRemove} />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </div>

      {/* Options + actions */}
      <div className="border-t border-slate-100 dark:border-slate-800 px-2.5 py-2 space-y-2">
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Merged track name"
          className="field field-sm" />
        <div className="flex flex-col gap-1.5 text-[11px] text-slate-600 dark:text-slate-300">
          <Check on={createActivity} onClick={() => setCreateActivity((v) => !v)} label="Also create a trip activity" />
          <Check on={hideSources} onClick={() => setHideSources((v) => !v)} label="Hide original tracks" />
        </div>
        <div className="flex gap-1.5 pt-0.5">
          <button onClick={merge} disabled={busy || sources.length < 2}
            className="btn btn-primary btn-sm flex-1">
            {busy ? "Merging…" : `Merge ${sources.length >= 2 ? sources.length : ""} into one`}
          </button>
          <button onClick={onClear} disabled={sources.length === 0}
            className="btn btn-neutral btn-sm">
            Clear
          </button>
        </div>
      </div>
    </div>
  );
}

function Check({ on, onClick, label }) {
  return (
    <label onClick={onClick} className="flex items-center gap-1.5 cursor-pointer">
      <span className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors ${on ? "bg-accent-600" : "bg-slate-300 dark:bg-slate-600"}`}>
        <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${on ? "translate-x-3.5" : "translate-x-0.5"}`} />
      </span>
      {label}
    </label>
  );
}
