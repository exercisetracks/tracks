// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../../api/client";
import ElevationProfileCard from "../../../pages/maps/components/ElevationProfileCard";

// Detail view for a "merged trip" activity (built on the maps page from several
// activities/tracks). It carries totals only — no per-second data — so instead of
// a sport layout we show the combined stats + the linked track's elevation profile.
// A merged trip never contributes to training metrics.
function StatCard({ label, value, tint }) {
  return (
    <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 px-3.5 py-2.5">
      <div className="text-[11px] uppercase tracking-wide text-slate-400">{label}</div>
      <div className={`text-lg font-semibold ${tint || "text-slate-800 dark:text-slate-100"}`}>{value}</div>
    </div>
  );
}

export default function MergedTripLayout({ activity, imperial }) {
  const [track, setTrack] = useState(null);
  const trackId = activity?.extra?.custom_track_id;

  useEffect(() => {
    if (trackId == null) return;
    let cancelled = false;
    api.getCourse(trackId).then((t) => { if (!cancelled) setTrack(t); }).catch(() => {});
    return () => { cancelled = true; };
  }, [trackId]);

  const km = (m) => m == null ? "—" : (imperial ? `${(m / 1609.34).toFixed(1)} mi` : `${(m / 1000).toFixed(1)} km`);
  const ft = (m) => m == null ? "—" : (imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m).toLocaleString()} m`);
  const dur = (s) => {
    if (!s) return "—";
    const h = Math.floor(s / 3600), m = Math.round((s % 3600) / 60);
    return h ? `${h}h ${m}m` : `${m}m`;
  };
  const nSeg = (activity?.extra?.source_activity_ids?.length || 0) + (activity?.extra?.source_track_ids?.length || 0);
  const date = activity?.started_at ? new Date(activity.started_at).toLocaleDateString() : null;

  return (
    <div className="p-3.5 sm:p-5 max-w-4xl mx-auto">
      <div className="flex items-center gap-2 flex-wrap mb-1">
        <h1 className="text-xl font-semibold text-slate-900 dark:text-white">{activity?.name || "Merged trip"}</h1>
        <span className="px-1.5 py-0.5 rounded-full text-[10px] font-bold bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300">
          MERGED TRIP
        </span>
      </div>
      <p className="text-sm text-slate-500 dark:text-slate-400 mb-1">
        {date && <span>{date} · </span>}{nSeg} segment{nSeg === 1 ? "" : "s"} combined
      </p>
      <p className="text-[12px] text-slate-400 dark:text-slate-500 mb-5">
        Totals only — this trip is excluded from all training metrics.
      </p>

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
        <StatCard label="Distance" value={km(activity?.distance_meters)} />
        <StatCard label="Ascent" value={ft(activity?.total_ascent)} tint="text-emerald-600 dark:text-emerald-400" />
        <StatCard label="Descent" value={ft(activity?.total_descent)} tint="text-rose-500 dark:text-rose-400" />
        <StatCard label="Moving time" value={dur(activity?.duration_seconds)} />
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden mb-4">
        <div className="px-3.5 pt-2.5 text-[11px] font-semibold uppercase tracking-wide text-slate-400">Elevation</div>
        <ElevationProfileCard profile={track?.profile} imperial={imperial} />
      </div>

      <Link to="/maps" className="inline-flex items-center gap-1.5 text-sm text-accent-600 dark:text-accent-400 hover:underline">
        View the merged track on the map
        <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" d="M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3" />
        </svg>
      </Link>
    </div>
  );
}
