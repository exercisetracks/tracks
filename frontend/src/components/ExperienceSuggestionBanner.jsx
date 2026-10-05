// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// "Coach note" banner: surfaces the history-derived experience-level suggestion
// (backend leveling.infer_experience_suggestion). Never auto-applies — the user
// accepts (updates their level + regenerates the plan) or dismisses it.
import { useState } from "react";
import { api } from "../api/client";
import { EXPERIENCE_LABEL } from "../lib/experienceLevels";

export default function ExperienceSuggestionBanner({ settings, onChanged }) {
  const [busy, setBusy] = useState(false);
  const suggested = settings?.experience_suggestion;
  const dismissed = settings?.experience_suggestion_dismissed;
  if (!suggested || dismissed) return null;

  async function accept() {
    setBusy(true);
    try {
      await api.updateSettings({ strength_experience: suggested });
      await onChanged?.();
    } finally { setBusy(false); }
  }
  async function dismiss() {
    setBusy(true);
    try {
      await api.updateSettings({ experience_suggestion_dismissed: true });
      await onChanged?.();
    } finally { setBusy(false); }
  }

  return (
    <div className="rounded-xl border border-accent-200 dark:border-accent-800 bg-accent-50/70 dark:bg-accent-900/20 p-3.5 flex flex-col sm:flex-row sm:items-center gap-3">
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold text-accent-800 dark:text-accent-200">Coach note</p>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          {settings.experience_suggestion_reason ||
            `Ready to move to ${EXPERIENCE_LABEL[suggested] || suggested}?`}
        </p>
      </div>
      <div className="flex gap-2 shrink-0">
        <button onClick={dismiss} disabled={busy}
          className="btn btn-neutral btn-sm">
          Not now
        </button>
        <button onClick={accept} disabled={busy}
          className="btn btn-primary btn-sm">
          Switch to {EXPERIENCE_LABEL[suggested] || suggested}
        </button>
      </div>
    </div>
  );
}
