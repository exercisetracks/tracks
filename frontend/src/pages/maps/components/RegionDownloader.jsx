// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Region-download control panel for the map page. When the user draws an area
// it shows the size estimate + a name field (auto-suggested from the bbox) and a
// Download button; while a download runs it renders the live per-phase progress
// card (colour-coded via the TONES table below). It also lists already-saved
// regions with highlight / zoom / delete actions. All state/orchestration lives
// in the parent's useRegionDownload hook — this component is presentational and
// drives it purely through the on* callbacks passed in as props.
import { useEffect, useState } from "react";
import { api } from "../../../api/client";

// Per-phase label + colour for the active-region progress card. Full literal
// class strings (no interpolation) so Tailwind keeps them at build time.
const TONES = {
  sky: {
    card: "from-sky-50 to-blue-50 dark:from-sky-950/30 dark:to-blue-950/20 border-sky-200 dark:border-sky-800",
    name: "text-sky-800 dark:text-sky-300", accent: "text-sky-600 dark:text-sky-400",
    spin: "border-sky-500", track: "bg-sky-200 dark:bg-sky-800/50",
    bar: "from-sky-400 via-sky-500 to-sky-400", sub: "text-sky-500/80 dark:text-sky-400/70",
  },
  amber: {
    card: "from-amber-50 to-orange-50 dark:from-amber-950/30 dark:to-orange-950/20 border-amber-200 dark:border-amber-800",
    name: "text-amber-800 dark:text-amber-300", accent: "text-amber-600 dark:text-amber-400",
    spin: "border-amber-500", track: "bg-amber-200 dark:bg-amber-800/50",
    bar: "from-amber-400 via-amber-500 to-amber-400", sub: "text-amber-500/80 dark:text-amber-400/70",
  },
  emerald: {
    card: "from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/20 border-emerald-200 dark:border-emerald-800",
    name: "text-emerald-800 dark:text-emerald-300", accent: "text-emerald-600 dark:text-emerald-400",
    spin: "border-emerald-500", track: "bg-emerald-200 dark:bg-emerald-800/50",
    bar: "from-emerald-400 via-emerald-500 to-emerald-400", sub: "text-emerald-500/80 dark:text-emerald-400/70",
  },
  violet: {
    card: "from-violet-50 to-purple-50 dark:from-violet-950/30 dark:to-purple-950/20 border-violet-200 dark:border-violet-800",
    name: "text-violet-800 dark:text-violet-300", accent: "text-violet-600 dark:text-violet-400",
    spin: "border-violet-500", track: "bg-violet-200 dark:bg-violet-800/50",
    bar: "from-violet-400 via-violet-500 to-violet-400", sub: "text-violet-500/80 dark:text-violet-400/70",
  },
  rose: {
    card: "from-rose-50 to-red-50 dark:from-rose-950/30 dark:to-red-950/20 border-rose-200 dark:border-rose-800",
    name: "text-rose-800 dark:text-rose-300", accent: "text-rose-600 dark:text-rose-400",
    spin: "border-rose-500", track: "bg-rose-200 dark:bg-rose-800/50",
    bar: "from-rose-400 via-rose-500 to-rose-400", sub: "text-rose-500/80 dark:text-rose-400/70",
  },
};
const PHASES = {
  downloading:     { label: "Downloading basemap",   tone: "sky" },
  downloading_dem: { label: "Downloading terrain",   tone: "sky" },
  merging:         { label: "Merging tiles",         tone: "amber", indeterminate: true },
  trails:          { label: "Generating trails",     tone: "emerald" },
  contours:        { label: "Generating contours",   tone: "violet" },
  combining:       { label: "Merging areas",         tone: "amber" },
  cancelling:      { label: "Cancelling",            tone: "rose", indeterminate: true },
};

export default function RegionDownloader({
  drawing, bbox, sizeEstimate, regions, pending,
  onStart, onCancel, onDownload, onDelete, onHighlight, onZoom,
}) {
  const [name, setName] = useState("");
  const [deleteError, setDeleteError] = useState("");
  const [showSaved, setShowSaved] = useState(false);

  useEffect(() => {
    if (!bbox) return;
    let cancelled = false;
    api.suggestRegionName(bbox).then((res) => {
      if (!cancelled) setName(res.name);
    }).catch(() => {});
    return () => { cancelled = true; };
  }, [bbox]);
  const downloading = regions.filter((r) =>
    ["downloading", "downloading_dem", "merging", "trails", "contours", "combining", "cancelling"].includes(r.status)
  );
  const deleting = regions.filter((r) => r.status === "deleting");
  const installed = regions.filter((r) => r.status === "installed");
  const errored = regions.filter((r) => r.status === "error");

  async function handleDelete(id) {
    setDeleteError("");
    try {
      await onDelete(id);
    } catch {
      setDeleteError("Delete failed — try again.");
    }
  }

  return (
    <div className="p-2.5 space-y-2">
      {!drawing ? (
        <button
          onClick={onStart}
          className="btn btn-primary btn-sm w-full gap-2"
        >
          <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          Download Area
        </button>
      ) : (
        <div className="space-y-2">
          <p className="text-[11px] text-slate-500 dark:text-slate-400 leading-relaxed">
            Click and drag to draw a rectangle. Drag corners to resize. Double-click to cancel.
          </p>
          {bbox && sizeEstimate && (
            <p className="text-[11px] text-slate-600 dark:text-slate-300 font-medium">
              Estimated size: {sizeEstimate}
            </p>
          )}
          {bbox && (
            <>
              <input
                type="text"
                placeholder="Area name (e.g. Yosemite)"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 text-[11px] px-1.5 py-1"
              />
              <div className="flex gap-1.5">
                <button
                  onClick={() => onDownload(name)}
                  disabled={pending}
                  className="btn btn-primary btn-sm flex-1"
                >
                  {pending ? "Downloading…" : "Confirm"}
                </button>
                <button
                  onClick={onCancel}
                  className="btn btn-neutral btn-sm"
                >
                  Cancel
                </button>
              </div>
            </>
          )}
        </div>
      )}

      {/* Active downloads — one card per region, real progress per phase */}
      {downloading.map((r) => {
        const phase = PHASES[r.status] || PHASES.downloading;
        // A build whose progress hasn't moved in a long time has no live
        // process left to finish it (crash, container restart, killed
        // subprocess) — show it as stalled rather than spinning forever.
        const tone = TONES[r.stale ? "rose" : phase.tone];
        const pct = Math.max(0, Math.min(100, r.progress || 0));
        // Show an indeterminate slide-bar for phases with no byte/tile progress
        // (the merge) and at the very start of a phase before the first tick.
        const indeterminate = !r.stale && (phase.indeterminate || pct <= 0);

        return (
          <div key={r.id} className={`px-2 py-1.5 rounded-lg bg-gradient-to-br ${tone.card} border space-y-2`}>
            <div className="flex items-center gap-2">
              {r.stale ? (
                <svg className="w-3 h-3 shrink-0 text-rose-500" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m0 3.75h.008M10.29 3.86L1.82 18a1.5 1.5 0 001.29 2.25h17.78a1.5 1.5 0 001.29-2.25L13.71 3.86a1.5 1.5 0 00-2.42 0z" />
                </svg>
              ) : (
                <div className={`w-3 h-3 border-2 ${tone.spin} border-t-transparent rounded-full animate-spin shrink-0`} />
              )}
              <span className={`flex-1 text-[11px] font-medium ${tone.name} truncate`}>{r.name}</span>
              <span className={`text-[10px] font-semibold tabular-nums ${tone.accent}`}>
                {r.stale ? "stalled" : indeterminate ? "…" : `${Math.round(pct)}%`}
              </span>
              {r.status !== "cancelling" && (
                <button
                  onClick={() => handleDelete(r.id)}
                  title={r.stale ? "Remove stalled download" : "Cancel download"}
                  className={`shrink-0 ${tone.accent} opacity-60 hover:opacity-100 transition-opacity`}
                >
                  <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              )}
            </div>

            {!r.stale && (
              <div className={`w-full h-1.5 rounded-full ${tone.track} overflow-hidden`}>
                {indeterminate ? (
                  <div className={`h-full rounded-full bg-gradient-to-r ${tone.bar} animate-slide-bar`} style={{ width: "60%" }} />
                ) : (
                  <div className={`h-full rounded-full bg-gradient-to-r ${tone.bar} transition-all duration-300`} style={{ width: `${pct}%` }} />
                )}
              </div>
            )}

            <div className="flex justify-between gap-2 text-[9px]">
              <span className={`${tone.sub} truncate`}>
                {r.stale ? "No progress for a while — remove and try again" : phase.label}
              </span>
              {!r.stale && r.detail && <span className="shrink-0 text-slate-400 dark:text-slate-500 tabular-nums">{r.detail}</span>}
            </div>
          </div>
        );
      })}

      {/* Deleting areas — same wheel style as a download, tinted for removal */}
      {deleting.map((r) => (
        <div key={r.id} className="px-2 py-1.5 rounded-lg bg-gradient-to-br from-rose-50 to-red-50 dark:from-rose-950/30 dark:to-red-950/20 border border-rose-200 dark:border-rose-800 space-y-2">
          <div className="flex items-center gap-2">
            <svg className="w-3.5 h-3.5 shrink-0 text-rose-500 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            <span className="flex-1 text-[11px] font-medium text-rose-800 dark:text-rose-300 truncate">{r.name}</span>
            <span className="text-[10px] font-semibold text-rose-600 dark:text-rose-400">removing</span>
          </div>

          <div className="w-full h-1.5 rounded-full bg-rose-200 dark:bg-rose-800/50 overflow-hidden">
            <div
              className="h-full rounded-full bg-gradient-to-r from-rose-400 via-rose-500 to-rose-400 animate-slide-bar"
              style={{ width: '60%' }}
            />
          </div>

          <div className="text-[9px] text-rose-500/80 dark:text-rose-400/70">
            Rebuilding tiles without this area…
          </div>
        </div>
      ))}

      {/* Saved areas — collapsed into a dropdown so the panel doesn't grow unbounded */}
      {installed.length > 0 && (
        <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden">
          <button
            onClick={() => setShowSaved((v) => !v)}
            className="w-full flex items-center gap-2 px-2 py-1.5 text-[11px] font-medium text-slate-600 dark:text-slate-300 bg-slate-50 dark:bg-slate-800 hover:bg-slate-100 dark:hover:bg-slate-700/70 transition-colors"
          >
            <svg className={`w-3 h-3 shrink-0 transition-transform ${showSaved ? "rotate-90" : ""}`} fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
            </svg>
            <span className="flex-1 text-left">Saved areas</span>
            <span className="px-1 py-0.5 rounded-full text-[10px] font-semibold bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
              {installed.length}
            </span>
          </button>
          {showSaved && (
            <ul className="divide-y divide-slate-100 dark:divide-slate-700/60 max-h-48 overflow-y-auto">
              {installed.map((r) => (
                <li
                  key={r.id}
                  onMouseEnter={() => onHighlight?.(r.id)}
                  onMouseLeave={() => onHighlight?.(null)}
                  onClick={() => onZoom?.(r)}
                  title="Click to zoom to this area"
                  className="flex items-center gap-2 px-2 py-1 bg-white dark:bg-slate-900/40 cursor-pointer hover:bg-sky-50 dark:hover:bg-sky-950/30 transition-colors"
                >
                  <svg className="w-3 h-3 shrink-0 text-emerald-500" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                  <span className="flex-1 text-[11px] text-slate-700 dark:text-slate-300 truncate">{r.name}</span>
                  <button
                    onClick={(e) => { e.stopPropagation(); handleDelete(r.id); }}
                    className="shrink-0 text-red-400 hover:text-red-600 dark:hover:text-red-400 transition-colors"
                    title="Delete"
                  >
                    <svg className="w-3 h-3" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {/* Errored regions */}
      {errored.map((r) => (
        <div key={r.id} className="flex items-center gap-2 px-1.5 py-1 rounded bg-red-50 dark:bg-red-900/20">
          <svg className="w-3 h-3 shrink-0 text-red-500" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01" />
          </svg>
          <span className="flex-1 text-[11px] text-red-700 dark:text-red-400 truncate">{r.name}</span>
          <button
            onClick={() => handleDelete(r.id)}
            className="btn btn-danger btn-sm shrink-0"
          >
            remove
          </button>
        </div>
      ))}

      {deleteError && (
        <p className="text-[10px] text-red-500 dark:text-red-400 text-center">{deleteError}</p>
      )}

      {regions.length === 0 && !drawing && (
        <p className="text-[10px] text-slate-400 dark:text-slate-500 text-center py-1.5">
          No areas downloaded yet.
        </p>
      )}
    </div>
  );
}
