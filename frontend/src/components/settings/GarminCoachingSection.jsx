// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Watch coaching section: a single toggle for VDOT-derived pace alerts that get
// embedded into each workout step uploaded to a Garmin watch.
import InfoTooltip from "../ui/InfoTooltip";
import { useState } from "react";
import { api } from "../../api/client";
import { Section } from "./primitives";

export default function GarminCoachingSection({ settings, onSaved }) {
  const [saving, setSaving] = useState(false);
  const enabled = settings?.pace_coaching ?? false;

  async function toggle() {
    setSaving(true);
    try {
      await api.updateSettings({ pace_coaching: !enabled });
      await onSaved();
    } finally {
      setSaving(false);
    }
  }

  return (
    <Section title="Watch Coaching">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-800 dark:text-slate-200 inline-flex items-center gap-1.5">
            Pace coaching
            <InfoTooltip label="About pace coaching">
              When enabled, each workout step uploaded to your Garmin watch includes a
              ±10 sec/km pace alert zone derived from your VDOT. The watch will alert
              you when you drift outside the target range. Re-sync your watch after
              changing this to push updated workout files.
            </InfoTooltip>
          </p>
        </div>
        <button
          type="button"
          onClick={toggle}
          disabled={saving || settings === null}
          className={`shrink-0 px-2.5 py-1 rounded-lg text-xs font-medium border transition-colors disabled:opacity-50 ${
            enabled
              ? "border-accent-300 dark:border-accent-700 bg-accent-50 dark:bg-accent-900/30 text-accent-700 dark:text-accent-400"
              : "border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300"
          }`}
        >
          {saving ? "…" : enabled ? "On" : "Off"}
        </button>
      </div>
    </Section>
  );
}
