// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ============================================================
// SORTABLE COLUMN HEADER  (presentational)
// ============================================================
// A single <th> for the Activities table that can be clicked to
// sort. Shows the active sort direction with an arrow, and a
// dimmed ↕ hint on inactive columns.
//
// Fully controlled / stateless: the parent owns the current
// sort column + order and the click handler. This keeps all
// sort state in one place (the page's URL params) and lets this
// component stay a pure function of its props.
// ============================================================

export default function SortHeader({ label, col, align, sort, order, onClick }) {
  const active  = sort === col;                                   // Is this the current sort column?
  const arrow   = active ? (order === "asc" ? "↑" : "↓") : "";    // Sort direction arrow
  const justify = align === "right" ? "justify-end" : "justify-start";

  return (
    <th className={`${align === "right" ? "text-right" : "text-left"} px-3.5 py-2.5`}>
      <button
        type="button"
        onClick={() => onClick(col)}
        className={`inline-flex items-center gap-1 ${justify} w-full select-none transition-colors ${
          active
            ? "text-slate-700 dark:text-slate-200"
            : "text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300"
        }`}
      >
        <span>{label}</span>
        <span className={`text-[10px] tabular-nums w-2 ${active ? "opacity-100" : "opacity-60"}`}>
          {arrow || "↕"}
        </span>
      </button>
    </th>
  );
}
