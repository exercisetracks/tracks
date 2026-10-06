// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from "react";
import { api } from "../../../api/client";
import ElevationProfileCard from "./ElevationProfileCard";

// Long-trail detail sheet: opened by clicking a trail/section in the point-info
// panel. Shows the parent trail's identity, its sections (click to switch), and an
// elevation profile + distance/gain/loss for the selected section — geometry and
// distances come from the per-route index, elevation is DEM-sampled on demand.
// The elevation chart itself is the shared ElevationProfileCard.

export default function RouteDetailPanel({ routeId, sectionId, imperial, onClose }) {
  const [detail, setDetail] = useState(null);
  const [activeSection, setActiveSection] = useState(sectionId || null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);

  // Reset selection when a different trail is opened.
  useEffect(() => { setActiveSection(sectionId || null); }, [routeId, sectionId]);

  useEffect(() => {
    if (!routeId) return;
    let cancelled = false;
    setLoading(true); setError(false);
    api.getRouteDetail(routeId, activeSection)
      .then((d) => { if (!cancelled) { setDetail(d); setActiveSection(d.selected_section || null); setLoading(false); } })
      .catch(() => { if (!cancelled) { setError(true); setLoading(false); } });
    return () => { cancelled = true; };
  }, [routeId, activeSection]);

  if (!routeId) return null;

  const km = (m) => m == null ? "—" : (imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`);
  const ft = (m) => m == null ? "—" : (imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m).toLocaleString()} m`);
  const prof = detail?.profile;
  const sections = detail?.sections || [];
  const curName = detail?.selected_name || sections.find((s) => s.id === activeSection)?.name;

  return (
    // Outer wrapper spans the width but is click-through (pointer-events-none) so
    // the bottom-left/right corners stay interactive; only the centered card itself
    // captures clicks and carries the background.
    <div className="absolute bottom-0 inset-x-0 z-30 flex justify-center pointer-events-none">
      <div className="pointer-events-auto w-full max-w-[50rem] mx-2 px-3.5 py-2
                      bg-white/80 dark:bg-slate-900/80 backdrop-blur-md
                      border border-b-0 border-slate-200/80 dark:border-slate-700/80 rounded-t-xl
                      shadow-[0_-4px_20px_rgba(0,0,0,0.10)]">
        {/* Header */}
        <div className="flex items-center justify-between gap-3 mb-1.5">
          <div className="flex items-center gap-2 min-w-0">
            <span className="px-1.5 py-0.5 rounded text-[11px] font-bold text-white shrink-0"
                  style={{ backgroundColor: detail?.color || "#666" }}>
              {detail?.abbr || "···"}
            </span>
            <div className="min-w-0">
              <div className="font-semibold text-slate-800 dark:text-slate-100 truncate">
                {detail?.name || (loading ? "Loading…" : "Trail")}
              </div>
              {curName && curName !== detail?.name && (
                <div className="text-[11px] text-slate-500 dark:text-slate-400 truncate">{curName}</div>
              )}
            </div>
          </div>
          <div className="flex items-center gap-3 shrink-0 text-[12px] text-slate-600 dark:text-slate-300">
            {prof && (
              <>
                <span title="section length">{km(prof.distance_m)}</span>
                <span className="text-emerald-600 dark:text-emerald-400" title="elevation gain">↑{ft(prof.gain_m)}</span>
                <span className="text-rose-500 dark:text-rose-400" title="elevation loss">↓{ft(prof.loss_m)}</span>
              </>
            )}
            {detail?.distance_m != null && (
              <span className="text-slate-400" title="full trail length">· {km(detail.distance_m)} total</span>
            )}
            <button onClick={onClose} className="icon-btn">×</button>
          </div>
        </div>

        {error ? (
          <div className="h-[120px] flex items-center justify-center text-xs text-slate-400">
            Couldn’t load trail details.
          </div>
        ) : (
          <ElevationProfileCard profile={prof} imperial={imperial} />
        )}

        {/* Section chips */}
        {sections.length > 1 && (
          <div className="mt-1.5 flex gap-1 overflow-x-auto pb-1 -mx-1 px-1">
            {sections.map((s) => (
              <button key={s.id} type="button" aria-pressed={s.id === activeSection}
                onClick={() => setActiveSection(s.id)} className="bar-pill shrink-0">
                {s.name} <span className="opacity-60">{km(s.distance_m)}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
