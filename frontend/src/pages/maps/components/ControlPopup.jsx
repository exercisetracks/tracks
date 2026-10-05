// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Generic bottom-left popup shell for the map control bar (Layers, Areas) — a
// themed sibling of the Legend popup: anchored above the control bar, a sticky
// title + close header, scrollable body. The Legend keeps its own paper-styled
// popup; this is the standard app-themed one.
export default function ControlPopup({ title, onClose, width = "w-[248px]", children }) {
  return (
    <div className={`absolute bottom-4 left-16 z-40 ${width} max-w-[calc(100vw-5rem)] max-h-[72vh] overflow-y-auto bg-white dark:bg-slate-900 rounded-xl shadow-xl border border-slate-200 dark:border-slate-700`}>
      <div className="sticky top-0 z-10 flex items-center justify-between px-2.5 py-1.5 bg-white dark:bg-slate-900 border-b border-slate-100 dark:border-slate-800 rounded-t-xl">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">{title}</h3>
        <button onClick={onClose} className="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      {children}
    </div>
  );
}
