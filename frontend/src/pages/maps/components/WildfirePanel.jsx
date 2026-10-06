// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Detail card for a clicked wildfire (incident point or perimeter edge).
// Mirrors PointInfoPanel's top-left placement; the two are mutually exclusive
// because usePointInfo skips clicks that land on a fire.
// US fires are named NIFC incidents with containment; Canadian fires are CWFIS
// M3 satellite detections — footprint + dates only, so the panel says so
// instead of showing an empty containment bar.
export default function WildfirePanel({ fire, onClose }) {
  if (!fire) return null;

  const canada = fire.country === "CA";
  const containment = fire.containment != null ? Math.round(fire.containment) : null;
  const fmtDate = (ms) => {
    const n = Number(ms);
    if (!n) return null;
    return new Date(n).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  };

  const rows = [
    ["Size", fire.acres_label || (fire.acres ? `${Math.round(fire.acres).toLocaleString()} ac` : "Unknown")],
    ["Behavior", fire.behavior],
    [canada ? "First detected" : "Discovered", fmtDate(fire.discovered)],
    [canada ? "Last detected" : null, canada ? fmtDate(fire.updated) : null],
    ["State", fire.state?.replace(/^US-/, "")],
  ].filter(([k, v]) => k && v);

  return (
    <div className="absolute top-16 left-3 z-10 w-[300px] bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-700">
      <div className="flex items-center justify-between gap-2 px-2.5 py-2 border-b border-slate-100 dark:border-slate-800">
        <div className="flex items-center gap-2 min-w-0">
          <span className="shrink-0 w-2.5 h-2.5 rounded-full bg-red-500 ring-4 ring-orange-400/30" />
          <span className="text-sm font-semibold text-slate-800 dark:text-slate-200 truncate">
            {fire.name || "Wildfire"}{fire.complex ? " (Complex)" : ""}{canada ? " (Canada)" : ""}
          </span>
        </div>
        <button onClick={onClose} className="icon-btn shrink-0">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div className="px-2.5 py-2 space-y-2.5">
        {/* Containment bar — the number people check first. Canada's satellite
            feed carries no containment, so skip the bar rather than pinning an
            active fire at a misleading 0%. */}
        {!canada && (
          <div>
            <div className="flex items-baseline justify-between">
              <span className="text-[11px] font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500">Containment</span>
              <span className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                {containment != null ? `${containment}%` : "Not reported"}
              </span>
            </div>
            <div className="mt-1 h-1.5 rounded-full bg-red-100 dark:bg-red-900/40 overflow-hidden">
              <div
                className="h-full rounded-full bg-emerald-500 transition-all"
                style={{ width: `${containment ?? 0}%` }}
              />
            </div>
          </div>
        )}

        <dl className="space-y-1">
          {rows.map(([k, v]) => (
            <div key={k} className="flex items-baseline justify-between gap-3">
              <dt className="text-[11px] font-medium uppercase tracking-wide text-slate-400 dark:text-slate-500 shrink-0">{k}</dt>
              <dd className="text-xs text-slate-700 dark:text-slate-300 text-right">{v}</dd>
            </div>
          ))}
        </dl>

        <p className="text-[10px] text-slate-400 dark:text-slate-500 leading-snug pt-0.5 border-t border-slate-100 dark:border-slate-800">
          {canada
            ? "Satellite estimate from NRCan CWFIS — extent and dates only, no incident details."
            : `Live data from NIFC${fire.updated ? ` · updated ${fmtDate(fire.updated)}` : ""}.`}{" "}
          Always follow official evacuation guidance.
        </p>
      </div>
    </div>
  );
}
