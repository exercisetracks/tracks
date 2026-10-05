// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Small shared presentational primitives for the Dashboard page.
//
// No coupling to any page state — every dashboard block is wrapped in a
// <Section> so the uppercase section headings look identical everywhere.

// Labelled section wrapper: a muted uppercase heading above its children.
export function Section({ title, children }) {
  return (
    <div>
      <h2 className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-3">
        {title}
      </h2>
      {children}
    </div>
  );
}
