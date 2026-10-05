// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import React from "react";

const SPORT_GRADIENTS = {
  running:           "from-blue-600/80 to-blue-900/80",
  hiking:            "from-green-600/80 to-green-900/80",
  cycling:           "from-amber-600/80 to-amber-900/80",
  mtb:               "from-amber-600/80 to-amber-900/80",
  indoor_cycling:    "from-orange-600/80 to-orange-900/80",
  strength:          "from-purple-600/80 to-purple-900/80",
  bouldering:        "from-accent-600/80 to-accent-900/80",
  climbing:          "from-accent-600/80 to-accent-900/80",
  swimming:          "from-cyan-600/80 to-cyan-900/80",
  rowing:            "from-sky-600/80 to-sky-900/80",
  triathlon:         "from-violet-600/80 to-violet-900/80",
  skiing:            "from-sky-400/80 to-blue-700/80",
  nordic_skiing:     "from-indigo-600/80 to-indigo-900/80",
  paddling:          "from-teal-600/80 to-teal-900/80",
  golf:              "from-green-700/80 to-green-900/80",
  team_sports:       "from-rose-600/80 to-rose-900/80",
  fitness_equipment: "from-orange-500/80 to-orange-800/80",
  mind_body:         "from-violet-400/80 to-purple-700/80",
  other:             "from-slate-600/80 to-slate-900/80",
};

export function StatPill({ value, label, sub, className = "" }) {
  if (value == null || value === "") return null;

  return (
    <div className={`flex flex-col items-center gap-0.5 min-w-0 ${className}`}>
      <span className="font-semibold text-white tabular-nums text-base leading-tight">
        {value}
      </span>
      <span className="text-xs text-white/60 uppercase tracking-wider text-center">
        {label}
      </span>
      {sub && (
        <span className="text-xs text-white/40 text-center">{sub}</span>
      )}
    </div>
  );
}

export function StatPillsStrip({ children, sportType = "other", className = "" }) {
  const gradient = SPORT_GRADIENTS[sportType] || SPORT_GRADIENTS.other;

  return (
    <div className={`bg-gradient-to-r ${gradient} rounded-xl p-3.5 shadow-sm ${className}`}>
      <div className="flex flex-wrap justify-around gap-4">
        {children}
      </div>
    </div>
  );
}

export function StatPillsGrid({ children, cols, sportType = "other", className = "" }) {
  const gradient = SPORT_GRADIENTS[sportType] || SPORT_GRADIENTS.other;
  const gridClass = cols
    ? `grid grid-cols-${cols} gap-4`
    : "flex flex-wrap gap-4";

  return (
    <div className={`bg-gradient-to-r ${gradient} rounded-xl p-3.5 shadow-sm ${className}`}>
      <div className={`${gridClass}`}>
        {children}
      </div>
    </div>
  );
}
