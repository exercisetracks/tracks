// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
export default function LayerPanel({ groups, toggles, onToggle, is3D, wildfireEnabled }) {
  const base = groups.filter((g) => !g.section);
  const live = groups.filter((g) => g.section === "live");
  const highres = groups.filter((g) => g.section === "highres");

  const renderToggle = (g) => {
    const active = toggles[g.id];
    // The grid is a flat screen-space overlay — it can't drape on tilted terrain,
    // so it's force-hidden in 3D. Show it disabled with a note rather than letting
    // the user toggle a control that would do nothing.
    // Live overlays (wildfires/smoke) stay disabled until the account-level
    // third-party-data opt-in is on, with a pointer to Settings.
    const gridDisabled = g.id === "grid" && is3D;
    const liveDisabled = g.section === "live" && !wildfireEnabled;
    const disabled = gridDisabled || liveDisabled;
    return (
      <button
        key={g.id}
        onClick={() => !disabled && onToggle(g.id, !active)}
        disabled={disabled}
        title={liveDisabled ? "Enable live wildfire data in Settings first" : undefined}
        className={`w-full flex items-center gap-2 px-2 py-1 rounded text-xs font-medium transition-colors ${
          disabled
            ? "text-slate-300 dark:text-slate-600 cursor-not-allowed"
            : active
            ? "bg-accent-50 dark:bg-accent-900/20 text-accent-700 dark:text-accent-400"
            : "text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800"
        }`}
      >
        <span className="flex-1 text-left">{g.label}</span>
        {disabled ? (
          <span className="text-[9px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500 shrink-0">
            {gridDisabled ? <>2D&nbsp;only</> : "Settings"}
          </span>
        ) : active ? (
          <svg className="w-3 h-3 shrink-0 text-accent-500" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
          </svg>
        ) : null}
      </button>
    );
  };

  return (
    <div className="p-2.5 space-y-0.5">
      {base.map(renderToggle)}

      {live.length > 0 && (
        <>
          <div
            className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500 mt-3 mb-1.5"
            title="Live overlays query third-party services (NIFC / NOAA) — opt in under Settings"
          >
            Live Data
          </div>
          {live.map(renderToggle)}
        </>
      )}

      {highres.length > 0 && (
        <>
          <div
            className="text-[10px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500 mt-3 mb-1.5"
            title="These layers only have data inside downloaded high-resolution areas"
          >
            High-Resolution Areas
          </div>
          {highres.map(renderToggle)}
        </>
      )}
    </div>
  );
}
