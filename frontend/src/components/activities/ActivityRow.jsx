// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ============================================================
// ACTIVITY TABLE ROW  (presentational)
// ============================================================
// One <tr> in the Activities table. Renders every column for a
// single activity: name + sport icon (+ MERGED badge + map-
// preview button), date, duration, distance, pace/speed, avg HR,
// elevation and calories.
//
// Stateless and routing-free: the parent supplies the `imperial`
// unit flag and two callbacks so navigation lives in the page,
// not here.
//   - onOpen()    → clicking the row opens the activity detail
//   - onPreview()  → clicking the map button previews the track
// Keeping the (visually heavy) row markup out of the page file
// makes the page's render tree much easier to scan.
// ============================================================

import { fmtDate, fmtDist, fmtDuration, fmtSpeed } from "./format";

export default function ActivityRow({ activity: a, imperial, onOpen, onPreview }) {
  return (
    <tr
      onClick={onOpen}
      className="cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
    >
      {/* Activity name, MERGED badge and map-preview button */}
      <td className="px-3.5 py-2.5">
        <div className="flex items-center gap-2">
          <div className="min-w-0">
            <p className="font-medium text-slate-800 dark:text-slate-100 leading-tight flex items-center gap-1.5">
              <span className="truncate">{a.name || "Untitled Activity"}</span>
              {a.is_merged && (
                <span className="shrink-0 px-1 py-0.5 rounded text-[9px] font-bold bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300">MERGED</span>
              )}
            </p>
            {a.sport && <p className="text-xs text-slate-400 dark:text-slate-500 capitalize">{a.is_merged ? "Merged trip" : a.sport}</p>}
          </div>
          {/* Map-preview shortcut — only for real (non-merged) activities that have a track */}
          {a.distance_meters > 0 && !a.is_merged && (
            <button
              onClick={(e) => { e.stopPropagation(); onPreview(); }}
              title="Preview on the map & turn into a track"
              className="ml-auto shrink-0 p-1 rounded-lg text-slate-300 dark:text-slate-600 hover:text-accent-600 dark:hover:text-accent-400 hover:bg-accent-50 dark:hover:bg-accent-900/20 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={1.8} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M9 6.75V15m6-6v8.25m.503 3.498 4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 0 0-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0Z" />
              </svg>
            </button>
          )}
        </div>
      </td>
      <td className="px-3.5 py-2.5 text-slate-500 dark:text-slate-400">{fmtDate(a.started_at)}</td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-700 dark:text-slate-300">{fmtDuration(a.duration_seconds)}</td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-700 dark:text-slate-300">{fmtDist(a.distance_meters, imperial)}</td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-500 dark:text-slate-400">{fmtSpeed(a.avg_speed, imperial, a.sport)}</td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-500 dark:text-slate-400">
        {a.avg_heart_rate ? `${a.avg_heart_rate} bpm` : "—"}
      </td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-500 dark:text-slate-400">
        {a.total_ascent != null
          ? imperial ? `${Math.round(a.total_ascent * 3.28084)} ft` : `${Math.round(a.total_ascent)} m`
          : "—"}
      </td>
      <td className="px-3.5 py-2.5 text-right tabular-nums text-slate-500 dark:text-slate-400">
        {a.total_calories ? `${a.total_calories} kcal` : "—"}
      </td>
    </tr>
  );
}
