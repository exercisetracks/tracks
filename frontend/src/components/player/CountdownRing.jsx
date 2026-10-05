// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Circular countdown indicator: an SVG ring that empties as `seconds` runs down
// from `total`, with the remaining seconds in the middle.
export default function CountdownRing({ seconds, total, paused = false, size = 132 }) {
  const r = size / 2 - 8;
  const circ = 2 * Math.PI * r;
  const frac = total > 0 ? Math.max(0, Math.min(1, seconds / total)) : 0;
  const mm = Math.floor(seconds / 60);
  const ss = seconds % 60;
  const label = mm > 0 ? `${mm}:${String(ss).padStart(2, "0")}` : String(ss);

  return (
    <div className="relative" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth="8"
          className="stroke-slate-200 dark:stroke-slate-800" />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth="8"
          strokeLinecap="round"
          className="stroke-accent-500 transition-[stroke-dashoffset] duration-1000 ease-linear"
          strokeDasharray={circ}
          strokeDashoffset={circ * (1 - frac)} />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-3xl font-bold tabular-nums text-slate-800 dark:text-white">{label}</span>
        {paused && <span className="text-[10px] uppercase tracking-wide text-slate-400">Paused</span>}
      </div>
    </div>
  );
}
