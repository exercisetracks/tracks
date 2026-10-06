// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared presentational primitives for the Health page: the titled Section
// wrapper, the Card shell (rendered as a button when clickable), and the injury
// SeverityBadge. Purely visual — no state, no data fetching.

import { SEV_COLORS } from "./constants";

// Titled section with an optional right-aligned action element.
export function Section({ title, children, action }) {
  return (
    <div>
      <div className="flex items-center justify-between mb-3">
        <h2 className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </div>
  );
}

// Card shell. When `onClick` is supplied it renders as a full-width button.
export function Card({ children, className = "", onClick }) {
  const base = "bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-800 p-3.5";
  if (onClick) {
    return (
      <button onClick={onClick} className={`${base} text-left w-full ${className}`}>
        {children}
      </button>
    );
  }
  return <div className={`${base} ${className}`}>{children}</div>;
}

// Pill showing injury severity (1–10) coloured by SEV_COLORS.
export function SeverityBadge({ severity }) {
  return (
    <span className={`inline-flex items-center rounded-full px-1.5 py-0.5 text-xs font-semibold ${SEV_COLORS[severity] ?? SEV_COLORS[5]}`}>
      {severity}/10
    </span>
  );
}

// The "?" that opens an explanation, beside a heading. One shape for "what is
// this" across the Health page: the section headings, the Sleep card and the
// dial history popups all use it, so it reads as one control everywhere.
export function InfoButton({ open, onToggle, label, size = "sm" }) {
  const dims = size === "sm" ? "w-4 h-4 text-[10px]" : "w-5 h-5 text-[11px]";
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-expanded={open}
      aria-label={label}
      className={`${dims} rounded-full font-bold flex items-center justify-center transition-colors ${
        open
          ? "bg-slate-700 text-white dark:bg-slate-200 dark:text-slate-900"
          : "bg-slate-100 text-slate-500 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-400 dark:hover:bg-slate-700"
      }`}
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
