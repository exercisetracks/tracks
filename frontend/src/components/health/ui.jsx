// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Presentational pieces the Health page shares: the injury SeverityBadge and
// the click-to-open "?" with its panel. Purely visual — no state, no data
// fetching.

import { SEV_COLORS } from "./constants";

// The section and card are the app's (ui/Section); re-exported because every
// Health component reaches for them here.
export { Section, Card } from "../ui/Section";

// Pill showing injury severity (1–10) coloured by SEV_COLORS.
export function SeverityBadge({ severity }) {
  return (
    <span className={`badge ${SEV_COLORS[severity] ?? SEV_COLORS[5]}`}>
      {severity}/10
    </span>
  );
}

// The "?" that opens an explanation, beside a heading. One shape for "what is
// this" across the Health page: the section headings, the Sleep card and the
// dial history popups all use it, so it reads as one control everywhere.
export function InfoButton({ open, onToggle, label, size = "sm" }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      aria-pressed={open}
      aria-label={label}
      className={`info-dot ${size === "sm" ? "" : "w-5 h-5 text-[11px]"}`}
    >
      ?
    </button>
  );
}

// The explanation an InfoButton opens: paragraphs on a quiet panel.
export function InfoPanel({ body, className = "" }) {
  return (
    <div className={`rounded-xl bg-slate-50 dark:bg-slate-800/60 px-4 py-3 space-y-2 text-sm text-slate-600 dark:text-slate-300 ${className}`}>
      {body.map((p, i) => <p key={i}>{p}</p>)}
    </div>
  );
}
